// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.absentMetadataTuple;
import static com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi.manifestStructTuple;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.ordinalRevertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumSyncCommitteeProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.QueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.VerifiedBundle;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor) returns (bytes)} for the Ethereum verifier
 * system contract (EVM address {@code 0x171}).
 *
 * <p>The {@code bundlePayload} is the RLP-encoded sync-committee bundle payload; the
 * {@code trustAnchor} is the verifier's self-contained anchor
 * ({@code [syncCommittee, genesisValidatorsRoot, forkVersion, serviceAddress]}), passed  to
 * {@link EthereumSyncCommitteeProofVerifier#verifyBundle(byte[], byte[])}. The verifier reads the service contract
 * address from the anchor itself, so this call needs no external configuration.
 * <p>
 * The Ethereum verifier proves the queue metadata via execution-layer storage proofs and computes any trust-anchor
 * rotation itself, so this call enforces two service-level invariants before returning:
 * <ul>
 *   <li>the relayed {@code metadata} must equal the Merkle-proven queue metadata; and</li>
 *   <li>{@code new_trust_anchor} is set iff the payload carried a verified sync-committee rotation,
 *       and is always the verifier-computed successor anchor (never the relay's claim).</li>
 * </ul>
 */
public class EthereumVerifyBundleCall extends AbstractCall {

    private static final Logger log = LogManager.getLogger(EthereumVerifyBundleCall.class);
    private static final long GAS_REQUIREMENT = 100_000L;

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;

    @Nullable
    private final byte[] channelContext;

    public EthereumVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = null;
    }

    public EthereumVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = requireNonNull(channelContext);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        log.info(
                "[EthereumVerifier] verifyBundle ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);

        final VerifiedBundle verified = runVerifier();
        if (verified == null) {
            return fail();
        }

        // Manifest-only recovery bundle (spec §8.1.4): the verifier proved an endpoint manifest with empty
        // bundle content and no queue state. Accepted only on the V3 (endpoint-manifest-enabled) path so the
        // CLPR Service can apply the manifest update out-of-band — no gRPC to any (stale) endpoint. With the
        // feature off this stays a hard rejection. Purely additive: normal bundles carry non-empty content, and
        // an empty-content bundle without a proven manifest falls through to the existing missing-metadata
        // failure path below (so the config flag is only consulted when a manifest is actually present).
        if (verified.bundleContentBytes().length == 0 && verified.newEndpointManifest() != null) {
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            return manifestEnabled ? manifestOnlySuccess(verified.newEndpointManifest()) : fail();
        }

        final ClprBundleContent content = parseBundleContent(verified);
        if (content == null) {
            return fail();
        }

        if (!relayedMetadataMatchesProven(content, verified.queueMetadata())) {
            return fail();
        }

        final ClprBundleContent outContent = reconcileTrustAnchorRotation(content, verified);
        if (outContent == null) {
            return fail();
        }

        // Thread the proof-verified endpoint manifest onto the content, then bind to the channel
        // context (V2) or return the config-only V1 shape. On the V2 path the manifest is surfaced as a
        // trailing return member when the endpoint-manifest feature is on (mirrors the Hiero/Besu
        // verifiers' VERIFY_BUNDLE_V3 return); V1 already carries it inside the serialized content.
        final ClprBundleContent finalContent = reconcileEndpointManifest(outContent, verified);
        if (channelContext == null) {
            return v1Success(verified, finalContent);
        }
        final boolean manifestEnabled =
                configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
        return v2Success(verified, finalContent, manifestEnabled);
    }

    /**
     * Threads the proof-verified endpoint manifest onto the returned content (spec §4.9). The verifier-proven manifest
     * always wins — any relay claim is discarded, since the manifest is re-derived from the storage proof against the
     * authenticated state root. Absent when the bundle carried no manifest advance; the Step-1b version guard
     * downstream (itself gated on {@code clpr.endpointManifestEnabled}) simply skips it.
     */
    @NonNull
    private ClprBundleContent reconcileEndpointManifest(
            @NonNull final ClprBundleContent content, @NonNull final VerifiedBundle verified) {
        if (verified.newEndpointManifest() == null) {
            return content;
        }
        return content.copyBuilder()
                .newEndpointManifest(verified.newEndpointManifest())
                .build();
    }

    /**
     * Runs the (BLS-deferred) proof verifier. Returns the verified bundle, or {@code null} when the proof is rejected
     * or the verifier errors unexpectedly.
     */
    @Nullable
    private VerifiedBundle runVerifier() {
        final var verifier = new EthereumSyncCommitteeProofVerifier(FakeBlsSignatureVerifier.INSTANCE);
        final VerifiedBundle verified;
        try {
            verified = verifier.verifyBundle(bundlePayload, trustAnchor);
        } catch (final ProofException e) {
            log.warn("[EthereumVerifier] verifyBundle: proof rejected", e);
            return null;
        } catch (final Exception e) {
            log.warn("[EthereumVerifier] verifyBundle: unexpected error", e);
            return null;
        }
        final byte[] nextAnchor = verified.nextTrustAnchor();
        log.info(
                "[EthereumVerifier] verifyBundle: verifier returned beaconBlockRoot={}, bundleContent={} bytes, provenMetadata={}, rotation={}",
                Bytes.wrap(verified.beaconBlockRoot32()),
                verified.bundleContentBytes().length,
                metadataSummary(verified.queueMetadata()),
                nextAnchor == null ? "none" : nextAnchor.length + " bytes");
        return verified;
    }

    /**
     * Parses the verifier's verbatim {@code ClprBundleContent} bytes. Returns {@code null} when they are not a valid
     * {@code ClprBundleContent}.
     */
    @Nullable
    private ClprBundleContent parseBundleContent(@NonNull final VerifiedBundle verified) {
        try {
            return ClprBundleContent.PROTOBUF.parse(
                    Bytes.wrap(verified.bundleContentBytes()).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("[EthereumVerifier] verifyBundle: inner bytes are not a valid ClprBundleContent", e);
            return null;
        }
    }

    /**
     * Checks that the relayed {@code content.metadata()} equals the Merkle-proven queue state exactly — the relay
     * cannot be trusted to report the queue counters/running hashes, so they must match what the storage proofs proved.
     */
    private boolean relayedMetadataMatchesProven(
            @NonNull final ClprBundleContent content, @NonNull final QueueMetadata proven) {
        final ClprQueueMetadata metadata = content.metadata();
        if (metadata == null) {
            log.warn("[EthereumVerifier] verifyBundle: bundle content metadata is missing");
            return false;
        }
        if (metadata.nextMessageId() != proven.nextMessageId()
                || metadata.receivedMessageId() != proven.receivedMessageId()
                || metadata.status().protoOrdinal() != proven.status()
                || !metadata.sentRunningHash().equals(Bytes.wrap(proven.sentRunningHash()))
                || !metadata.receivedRunningHash().equals(Bytes.wrap(proven.receivedRunningHash()))) {
            log.warn(
                    "[EthereumVerifier] verifyBundle: bundle content metadata does not match proven queue state: content={}, proven={}",
                    metadataSummary(metadata),
                    metadataSummary(proven));
            return false;
        }
        log.info(
                "[EthereumVerifier] verifyBundle: bundle content parsed: messages={}, contentMetadata={}, claimedNewTrustAnchor={} bytes",
                content.messages().size(),
                metadataSummary(metadata),
                content.newTrustAnchor().length());
        return true;
    }

    /**
     * Reconciles the trust anchor rotation provided by the verified bundle with the current bundle content.
     *
     * <p>When {@code verified.nextTrustAnchor()} is non-null the verifier has already confirmed both the
     * cryptographic proof and that the period has advanced; the content's claimed anchor (if any) must exactly match
     * the proven successor. When it is {@code null} (no proof, or the period has not yet advanced) the content must
     * not claim a rotation.
     */
    @Nullable
    private ClprBundleContent reconcileTrustAnchorRotation(
            @NonNull final ClprBundleContent content, @NonNull final VerifiedBundle verified) {
        final byte[] nextAnchor = verified.nextTrustAnchor();
        final byte[] nextAnchorId = verified.nextTrustAnchorId();
        if (nextAnchor == null || nextAnchorId == null) {
            if (content.newTrustAnchor().length() != 0) {
                log.warn("[EthereumVerifier] verifyBundle: content claims a rotation without a proven rotation");
                return null;
            }
            return content;
        }

        // Verifier confirmed the rotation — activate the proven successor anchor.
        final var provenAnchor = Bytes.wrap(nextAnchor);
        final var provenAnchorId = Bytes.wrap(nextAnchorId);
        if (content.newTrustAnchor().length() != 0 && !content.newTrustAnchor().equals(provenAnchor)) {
            log.warn(
                    "[EthereumVerifier] verifyBundle: content new_trust_anchor differs from proven successor:"
                            + " claimed={} bytes, proven={} bytes",
                    content.newTrustAnchor().length(),
                    nextAnchor.length);
            return null;
        }
        if (content.newTrustAnchorId().length() != 0
                && !content.newTrustAnchorId().equals(provenAnchorId)) {
            log.warn(
                    "[EthereumVerifier] verifyBundle: content new_trust_anchor_id differs from proven successor:"
                            + " claimed={} bytes, proven={} bytes",
                    content.newTrustAnchorId().length(),
                    nextAnchorId.length);
        }
        return content.copyBuilder()
                .newTrustAnchor(provenAnchor)
                .newTrustAnchorId(provenAnchorId)
                .build();
    }

    @NonNull
    private PricedResult v1Success(
            @NonNull final VerifiedBundle verified, @NonNull final ClprBundleContent outContent) {
        final var contentBytesOut = ClprBundleContent.PROTOBUF.toBytes(outContent);
        log.info(
                "[EthereumVerifier] verifyBundle EXIT: SUCCESS beaconBlockRoot={} content={} bytes",
                Bytes.wrap(verified.beaconBlockRoot32()),
                contentBytesOut.length());
        return gasOnly(
                successResult(
                        EthereumVerifyBundleTranslator.VERIFY_BUNDLE
                                .getOutputs()
                                .encode(Tuple.singleton(contentBytesOut.toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult v2Success(
            @NonNull final VerifiedBundle verified,
            @NonNull final ClprBundleContent outContent,
            final boolean manifestEnabled) {
        final ClprQueueMetadata meta = outContent.metadataOrElse(ClprQueueMetadata.DEFAULT);
        final Tuple metaTuple = Tuple.of(
                BigInteger.valueOf(meta.nextMessageId()),
                meta.sentRunningHash().toByteArray(),
                BigInteger.valueOf(meta.receivedMessageId()),
                meta.receivedRunningHash().toByteArray(),
                meta.status().protoOrdinal());
        final byte[][] messageBytes = outContent.messages().stream()
                .map(msg -> ClprMessagePayload.PROTOBUF.toBytes(msg).toByteArray())
                .toArray(byte[][]::new);
        final byte[] newTrustAnchor = outContent.newTrustAnchor().toByteArray();
        final byte[] newTrustAnchorId = outContent.newTrustAnchorId().toByteArray();
        log.info(
                "[EthereumVerifier] verifyBundle V2 EXIT: SUCCESS beaconBlockRoot={} messages={} manifestEnabled={}",
                Bytes.wrap(verified.beaconBlockRoot32()),
                messageBytes.length,
                manifestEnabled);
        if (manifestEnabled) {
            // §4.2 Step 1b: append the extracted manifest as the 5th member (DEFAULT → version 0 = absent).
            final Tuple manifestTuple =
                    manifestStructTuple(outContent.newEndpointManifestOrElse(ClprEndpointManifest.DEFAULT));
            return gasOnly(
                    successResult(
                            VERIFY_BUNDLE_V3_RETURN.encode(
                                    Tuple.of(metaTuple, messageBytes, newTrustAnchor, newTrustAnchorId, manifestTuple)),
                            GAS_REQUIREMENT),
                    SUCCESS,
                    false);
        }
        return gasOnly(
                successResult(
                        EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2
                                .getOutputs()
                                .encode(Tuple.of(metaTuple, messageBytes, newTrustAnchor, newTrustAnchorId)),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    /**
     * V3 success return for a manifest-only recovery bundle (spec §8.1.4): the endpoint manifest with an empty
     * message set and no trust-anchor rotation. Metadata is signalled absent via a zero {@code nextMessageId}
     * sentinel (a normal bundle's is always {@code >= 1}), which
     * {@link com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier} decodes to a null metadata so
     * {@code ClprSubmitBundleHandler} takes its state-update-only path (applying the already-proven manifest).
     */
    @NonNull
    private PricedResult manifestOnlySuccess(@NonNull final ClprEndpointManifest manifest) {
        final Tuple absentMetadata = absentMetadataTuple();
        return gasOnly(
                successResult(
                        VERIFY_BUNDLE_V3_RETURN.encode(Tuple.of(
                                absentMetadata,
                                new byte[0][],
                                new byte[0],
                                new byte[0],
                                manifestStructTuple(manifest))),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_BUNDLE_VERIFICATION_FAILED, GAS_REQUIREMENT),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                false);
    }

    @NonNull
    private static String metadataSummary(@NonNull final ClprQueueMetadata metadata) {
        return "nextMessageId=" + metadata.nextMessageId()
                + ", receivedMessageId=" + metadata.receivedMessageId()
                + ", status=" + metadata.status().protoOrdinal()
                + ", sentRunningHash=" + metadata.sentRunningHash()
                + ", receivedRunningHash=" + metadata.receivedRunningHash()
                + ", trustAnchorId=" + metadata.trustAnchorId();
    }

    @NonNull
    private static String metadataSummary(@NonNull final QueueMetadata metadata) {
        return "nextMessageId=" + metadata.nextMessageId()
                + ", receivedMessageId=" + metadata.receivedMessageId()
                + ", status=" + metadata.status()
                + ", sentRunningHash=" + Bytes.wrap(metadata.sentRunningHash())
                + ", receivedRunningHash=" + Bytes.wrap(metadata.receivedRunningHash())
                + ", lastMessageRunningHash=" + Bytes.wrap(metadata.lastMessageRunningHash());
    }
}
