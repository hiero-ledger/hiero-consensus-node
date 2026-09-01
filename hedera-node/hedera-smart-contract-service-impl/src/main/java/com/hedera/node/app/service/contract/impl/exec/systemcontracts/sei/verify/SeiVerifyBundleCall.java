// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

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
import com.hedera.node.app.service.clpr.impl.verifier.sei.SeiCometBftProofVerifier;
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
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor) returns (bytes)} for the
 * Sei verifier system contract (EVM address {@code 0x170}).
 *
 * <p>The {@code bundlePayload} is a proto-encoded {@code ClprSeiBundlePayload}; the
 * {@code trustAnchor} is the proto-encoded {@code SeiTrustAnchor} stored on the Channel.
 * Verification is delegated to {@link SeiCometBftProofVerifier#verifyBundle(byte[], byte[])}.
 *
 * <p>Unlike the QBFT verifier — which returns the relayed {@code ClprBundleContent} verbatim —
 * the Sei verifier enforces two service-level invariants before returning, because a Sei bundle's
 * trust anchor is <i>computed</i> from proven validator-set rotation rather than echoed from the
 * payload:
 * <ul>
 *   <li>the relayed {@code metadata} must equal the Merkle-proven queue metadata; and</li>
 *   <li>{@code new_trust_anchor} is set on the returned content iff the payload carried verified
 *       rotation evidence, and is always the verifier-computed successor (never the relay's claim).</li>
 * </ul>
 */
public class SeiVerifyBundleCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(SeiVerifyBundleCall.class);
    private static final long GAS_REQUIREMENT = 100_000L;

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;

    @Nullable
    private final byte[] channelContext;

    public SeiVerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = null;
    }

    public SeiVerifyBundleCall(
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
                "verifyBundle (Sei) ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);

        final SeiCometBftProofVerifier.VerifiedBundle verified;
        try {
            verified = SeiCometBftProofVerifier.verifyBundle(bundlePayload, trustAnchor);
        } catch (final ProofException e) {
            log.warn("verifyBundle (Sei): proof rejected: {}", e.getMessage());
            return fail();
        } catch (final Exception e) {
            log.warn("verifyBundle (Sei): unexpected error", e);
            return fail();
        }
        log.info(
                "verifyBundle (Sei): verifier returned blockHash={}, bundleContent={}, provenMetadata={}, rotation={}",
                verified.blockHash32() == null ? "none" : Bytes.wrap(verified.blockHash32()),
                verified.bundleContentBytes() == null ? "none" : verified.bundleContentBytes().length + " bytes",
                verified.queueMetadata() == null ? "none" : metadataSummary(verified.queueMetadata()),
                verified.newTrustAnchor() == null ? "none" : verified.newTrustAnchor().length + " bytes");

        // Content-less result: a state-update-only bundle with no queue content — either a manifest-only
        // recovery (spec §8.1.4) or a trust-anchor rotation. Its queue metadata is absent, so it flows
        // through the SAME selector-aware return path as a normal bundle (legacy bytes vs V2/V3 tuple):
        // v2Success emits the zero-nextMessageId sentinel for the null metadata, which EvmClprVerifier
        // decodes back to null metadata so ClprSubmitBundleHandler takes its state-update-only path. (This
        // wire shape is not yet produced by the clpr-evm-endpoint relay — cross-repo follow-up.)
        if (verified.bundleContentBytes() == null) {
            final boolean hasManifest = verified.newEndpointManifestBytes().length > 0;
            final boolean hasRotation = verified.newTrustAnchor() != null;
            if (!hasManifest && !hasRotation) {
                log.warn("verifyBundle (Sei): bundle proved neither content, a trust-anchor rotation, nor a manifest");
                return fail();
            }
            // Only the manifest check and the tuple (v2Success) path need the flag; the legacy trust-anchor
            // return does not, so avoid the config lookup there.
            final boolean manifestEnabled = (hasManifest || channelContext != null)
                    && configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            // Manifest-only recovery is a V3-only feature; reject it when the flag is off.
            if (hasManifest && !manifestEnabled) {
                log.warn("verifyBundle (Sei): manifest-only recovery bundle rejected (endpoint-manifest feature off)");
                return fail();
            }
            // Build state-update-only content: queue metadata omitted (→ absent sentinel), no messages.
            final var stateBuilder = ClprBundleContent.newBuilder();
            if (hasRotation) {
                stateBuilder
                        .newTrustAnchor(Bytes.wrap(verified.newTrustAnchor()))
                        .newTrustAnchorId(Bytes.wrap(requireNonNull(verified.newTrustAnchorId())));
            }
            if (hasManifest) {
                // Carried inline so the legacy (bytes) return conveys it; the V2/V3 tuple sources the
                // manifest from `verified` in v2Success.
                final ClprEndpointManifest manifest;
                try {
                    manifest = ClprEndpointManifest.PROTOBUF.parse(
                            Bytes.wrap(verified.newEndpointManifestBytes()).toReadableSequentialData());
                } catch (final Exception e) {
                    // The verifier already strict-parsed this preimage; a re-parse failure is a defect,
                    // not a proof failure — surface as a bundle verification failure rather than escaping.
                    log.warn("verifyBundle (Sei): manifest-only recovery bytes are not a ClprEndpointManifest", e);
                    return fail();
                }
                stateBuilder.newEndpointManifest(manifest);
            }
            final var stateUpdate = stateBuilder.build();
            // Return the selector-correct shape, exactly like a normal bundle — no special case.
            return channelContext == null
                    ? legacyBytesSuccess(stateUpdate, verified)
                    : v2Success(stateUpdate, verified, manifestEnabled);
        }

        final ClprBundleContent content;
        try {
            content = ClprBundleContent.PROTOBUF.parse(
                    Bytes.wrap(verified.bundleContentBytes()).toReadableSequentialData());
        } catch (final Exception e) {
            log.warn("verifyBundle (Sei): inner bytes are not a valid ClprBundleContent: {}", e.getMessage());
            return fail();
        }

        // 1. The relayed metadata must match the Merkle-proven queue state exactly.
        final ClprQueueMetadata metadata = content.metadata();
        final SeiCometBftProofVerifier.QueueMetadata proven = verified.queueMetadata();
        if (metadata == null) {
            log.warn("verifyBundle (Sei): bundle content metadata is missing");
            return fail();
        }
        if (!proven.hasLastMessageProof() && !content.messages().isEmpty()) {
            log.warn(
                    "verifyBundle (Sei): message-bearing bundle is missing the last-message running-hash proof: messages={}",
                    content.messages().size());
            return fail();
        }
        final int contentStatus = metadata.status().protoOrdinal();
        if (metadata.nextMessageId() != proven.nextMessageId()
                || metadata.receivedMessageId() != proven.receivedMessageId()
                || contentStatus != proven.status()
                || !metadata.sentRunningHash().equals(Bytes.wrap(proven.sentRunningHash()))
                || !metadata.receivedRunningHash().equals(Bytes.wrap(proven.receivedRunningHash()))) {
            log.warn(
                    "verifyBundle (Sei): bundle content metadata does not match proven queue state: content={}, proven={}",
                    metadataSummary(metadata),
                    metadataSummary(proven));
            return fail();
        }
        log.info(
                "verifyBundle (Sei): bundle content parsed: messages={}, contentMetadata={}, claimedNewTrustAnchor={} bytes, claimedNewTrustAnchorId=0x{}",
                content.messages().size(),
                metadataSummary(metadata),
                content.newTrustAnchor().length(),
                content.newTrustAnchorId().toHex());

        // 2. Reconcile trust-anchor rotation: the verifier-computed successor is authoritative.
        final ClprBundleContent outContent;
        if (verified.newTrustAnchor() == null) {
            if (content.newTrustAnchor().length() != 0) {
                log.warn(
                        "verifyBundle (Sei): content claims a rotation without proven evidence: claimedNewTrustAnchor={} bytes, claimedNewTrustAnchorId=0x{}",
                        content.newTrustAnchor().length(),
                        content.newTrustAnchorId().toHex());
                return fail();
            }
            outContent = content;
        } else {
            final var provenAnchor = Bytes.wrap(verified.newTrustAnchor());
            if (content.newTrustAnchor().length() != 0
                    && !content.newTrustAnchor().equals(provenAnchor)) {
                log.warn(
                        "verifyBundle (Sei): content new_trust_anchor differs from proven successor: claimed={} bytes id=0x{}, proven={} bytes id=0x{}",
                        content.newTrustAnchor().length(),
                        content.newTrustAnchorId().toHex(),
                        verified.newTrustAnchor().length,
                        Bytes.wrap(requireNonNull(verified.newTrustAnchorId())).toHex());
                return fail();
            }
            outContent = content.copyBuilder()
                    .newTrustAnchor(provenAnchor)
                    .newTrustAnchorId(Bytes.wrap(requireNonNull(verified.newTrustAnchorId())))
                    .build();
        }

        if (channelContext != null) {
            // On the V2 path the manifest advance is surfaced as a trailing return member when the
            // endpoint-manifest feature is on (mirrors the Hiero/Besu VERIFY_BUNDLE_V3 return); the
            // shared EvmClprVerifier decodes 4-member (off) vs 5-member (on) keyed on the same flag.
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            return v2Success(outContent, verified, manifestEnabled);
        }
        return legacyBytesSuccess(outContent, verified);
    }

    /**
     * Legacy {@code verifyBundle(bytes,bytes) -> (bytes)} success return: {@code outContent}
     * protobuf-serialized into a single {@code bytes}. Used for the 2-arg method (no channel context).
     * An absent {@code metadata} field on {@code outContent} conveys a state-update-only bundle.
     */
    @NonNull
    private PricedResult legacyBytesSuccess(
            @NonNull final ClprBundleContent outContent,
            @NonNull final SeiCometBftProofVerifier.VerifiedBundle verified) {
        final var contentBytesOut = ClprBundleContent.PROTOBUF.toBytes(outContent);
        log.info(
                "verifyBundle (Sei) EXIT: SUCCESS (legacy bytes) blockHash={} content={} bytes",
                verified.blockHash32() == null ? "none" : Bytes.wrap(verified.blockHash32()),
                contentBytesOut.length());
        return gasOnly(
                successResult(
                        SeiVerifyBundleTranslator.VERIFY_BUNDLE
                                .getOutputs()
                                .encode(Tuple.singleton(contentBytesOut.toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                false);
    }

    @NonNull
    private PricedResult v2Success(
            @NonNull final ClprBundleContent outContent,
            @NonNull final SeiCometBftProofVerifier.VerifiedBundle verified,
            final boolean manifestEnabled) {
        // Metadata absent (a state-update-only bundle) → the zero-nextMessageId sentinel, with full
        // 32-byte zero hashes so it encodes against the bytes32 fields. Otherwise the proven queue state.
        final ClprQueueMetadata meta = outContent.metadata();
        final Tuple metaTuple = meta == null
                ? absentMetadataTuple()
                : Tuple.of(
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
                "verifyBundle V2 (Sei) EXIT: SUCCESS blockHash={} messages={} manifestEnabled={}",
                verified.blockHash32() == null ? "none" : Bytes.wrap(verified.blockHash32()),
                messageBytes.length,
                manifestEnabled);
        if (manifestEnabled) {
            // §4.2 Step 1b: append the extracted manifest as the 5th member (DEFAULT → version 0 = absent).
            ClprEndpointManifest manifest = ClprEndpointManifest.DEFAULT;
            final byte[] manifestBytes = verified.newEndpointManifestBytes();
            if (manifestBytes.length > 0) {
                try {
                    manifest = ClprEndpointManifest.PROTOBUF.parse(
                            Bytes.wrap(manifestBytes).toReadableSequentialData());
                } catch (final Exception e) {
                    // The verifier already strict-parsed this preimage; a re-parse failure is a defect, not
                    // a proof failure — surface as a bundle verification failure rather than escaping.
                    log.warn("verifyBundle (Sei): proven manifest bytes are not a ClprEndpointManifest", e);
                    return fail();
                }
            }
            return gasOnly(
                    successResult(
                            VERIFY_BUNDLE_V3_RETURN.encode(Tuple.of(
                                    metaTuple,
                                    messageBytes,
                                    newTrustAnchor,
                                    newTrustAnchorId,
                                    manifestStructTuple(manifest))),
                            GAS_REQUIREMENT),
                    SUCCESS,
                    false);
        }
        return gasOnly(
                successResult(
                        SeiVerifyBundleTranslator.VERIFY_BUNDLE_V2
                                .getOutputs()
                                .encode(Tuple.of(metaTuple, messageBytes, newTrustAnchor, newTrustAnchorId)),
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
    private static String metadataSummary(@NonNull final SeiCometBftProofVerifier.QueueMetadata metadata) {
        return "nextMessageId=" + metadata.nextMessageId()
                + ", receivedMessageId=" + metadata.receivedMessageId()
                + ", status=" + metadata.status()
                + ", sentRunningHash=" + Bytes.wrap(metadata.sentRunningHash())
                + ", receivedRunningHash=" + Bytes.wrap(metadata.receivedRunningHash())
                + ", lastMessageRunningHash=" + Bytes.wrap(metadata.lastMessageRunningHash());
    }
}
