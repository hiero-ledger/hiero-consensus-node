// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

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
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements {@code verifyBundle(bytes bundlePayload, bytes trustAnchor) returns (bytes)}.
 *
 * <p>For Hiero TSS the {@code trustAnchor} parameter carries the peer ledger_id (as stored in
 * {@code Channel.trust_anchor}). The TSS aggregate signature check is delegated to the
 * injected {@link TssVerifier} as {@code verifyTss(ledgerId, signature, blockRootHash)}.
 * Because bundle paths are independent leaf-to-block-root paths, the block root hash is
 * computed from the first state-item-leaf path via
 * {@link StateProofVerifier#computeBlockRootHashFromPath}; each remaining path is then
 * independently verified against that root before its state value is dispatched on tag
 * (482 = channel, 498 = message). Returns the serialized {@link ClprBundleContent} on
 * success. {@code new_trust_anchor} / {@code new_trust_anchor_id} are populated only when the
 * proof carries a state-proven ledger-ID succession; under normal operation both are empty
 * because the Hiero TSS ledger_id does not rotate.
 */
public class VerifyBundleCall extends AbstractCall {
    private static final Logger log = LogManager.getLogger(VerifyBundleCall.class);
    private static final long GAS_REQUIREMENT = 100_000L;

    private final byte[] bundlePayload;
    private final byte[] trustAnchor;
    private final TssVerifier tssVerifier;

    @Nullable
    private final byte[] channelContext;

    public VerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final TssVerifier tssVerifier) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.tssVerifier = requireNonNull(tssVerifier);
        this.channelContext = null;
    }

    public VerifyBundleCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext,
            @NonNull final TssVerifier tssVerifier) {
        super(gasCalculator, enhancement, true);
        this.bundlePayload = requireNonNull(bundlePayload);
        this.trustAnchor = requireNonNull(trustAnchor);
        this.channelContext = requireNonNull(channelContext);
        this.tssVerifier = requireNonNull(tssVerifier);
    }

    @Override
    public boolean allowsStaticFrame() {
        return true;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final var trustAnchorBytes = Bytes.wrap(trustAnchor);
        log.info(
                "verifyBundle (receiver) ENTER trustAnchor={} ({} bytes), bundlePayload={} bytes, gasRequirement={}",
                trustAnchorBytes,
                trustAnchor.length,
                bundlePayload.length,
                GAS_REQUIREMENT);

        final StateProof proof;
        try {
            proof = StateProof.PROTOBUF.parse(Bytes.wrap(bundlePayload).toReadableSequentialData());
        } catch (final Exception e) {
            log.error("verifyBundle: failed to parse StateProof for trustAnchor {}", trustAnchorBytes, e);
            return fail();
        }

        // 1. The bundle paths are independent leaf-to-block-root paths, so we cannot use
        //    computeBlockRootHash (which assumes a single connected proof tree). Each path
        //    independently authenticates to the block root, so we compute the block root hash
        //    from the first state-item-leaf path and then verify the remaining paths against it.
        if (!proof.hasSignedBlockProof()) {
            return fail();
        }
        final var signature = proof.signedBlockProofOrThrow().blockSignature();
        if (signature.length() == 0) {
            return fail();
        }

        byte[] blockRootHash = null;
        for (final var path : proof.paths()) {
            if (!path.hasStateItemLeaf()) {
                continue;
            }
            try {
                blockRootHash = StateProofVerifier.computeBlockRootHashFromPath(path);
            } catch (final IllegalStateException e) {
                log.error("verifyBundle: structurally invalid path for trustAnchor {}", trustAnchorBytes, e);
                return fail();
            }
            break;
        }
        if (blockRootHash == null) {
            log.error("verifyBundle: no state-item leaf paths in proof for trustAnchor {}", trustAnchorBytes);
            return fail();
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "verifyBundle (receiver): verifyTss inputs — trustAnchor={} ({} bytes), sigLen={}, rootLen={}",
                    trustAnchorBytes,
                    trustAnchor.length,
                    signature.length(),
                    blockRootHash.length);
        }
        // For Hiero TSS the trust_anchor is the peer's ledger_id; pass it through to verifyTss.
        if (!tssVerifier.verifyTss(trustAnchorBytes, signature, Bytes.wrap(blockRootHash))) {
            log.error("verifyBundle: TSS verification failed for trustAnchor {}", trustAnchorBytes);
            return fail();
        }

        // 2. Validate each path against the verified block root and accumulate channel +
        //    message payloads + optional endpoint manifest (spec §4.9).
        final byte[] expectedBlockRoot = blockRootHash;
        ClprChannel channel = null;
        final var messages = new ArrayList<ClprMessagePayload>();
        Bytes lastRunningHash = Bytes.EMPTY;
        ClprEndpointManifest newEndpointManifest = null;

        for (final var path : proof.paths()) {
            if (!path.hasStateItemLeaf()) {
                continue;
            }
            if (!StateProofVerifier.verifyPath(path, expectedBlockRoot)) {
                log.error("verifyBundle: Merkle path failed verification for trustAnchor {}", trustAnchorBytes);
                return fail();
            }
            final var valueBytes = ClprProofExtraction.extractStateItemValue(path.stateItemLeafOrThrow());
            if (valueBytes == null) {
                continue;
            }
            final int svTag = ClprProofExtraction.readFirstVarintTag(valueBytes);
            try {
                if (svTag == ClprProofExtraction.SV_CHANNEL_TAG) {
                    final var inner = ClprProofExtraction.unwrapStateValueField(valueBytes);
                    if (inner != null) {
                        channel = ClprChannel.PROTOBUF.parse(inner.toReadableSequentialData());
                    }
                } else if (svTag == ClprProofExtraction.SV_MESSAGE_TAG) {
                    final var inner = ClprProofExtraction.unwrapStateValueField(valueBytes);
                    if (inner != null) {
                        final var msgValue = ClprMessageValue.PROTOBUF.parse(inner.toReadableSequentialData());
                        // Preserve the slot for redacted messages (payload cleared by ClprRedactMessage):
                        // the receiver expects to iterate messages by index so it can emit a REDACTED
                        // reply for that slot and advance ackedMessageId. Dropping the slot would
                        // misalign receivedMessageId and stall delivery.
                        messages.add(msgValue.hasPayload() ? msgValue.payload() : ClprMessagePayload.DEFAULT);
                        lastRunningHash = msgValue.runningHashAfterProcessing();
                    }
                } else if (svTag == ClprProofExtraction.SV_ENDPOINT_MANIFEST_TAG) {
                    // Optional endpoint manifest advancement (spec §4.9 / bundle Progress
                    // Criterion 5). Absent by default — the CLPR Service applies the update
                    // only when the returned manifest version is greater than the Channel's
                    // cached version (spec §4.2 Step 1b, impl in #333).
                    final var inner = ClprProofExtraction.unwrapStateValueField(valueBytes);
                    if (inner != null) {
                        newEndpointManifest = ClprEndpointManifest.PROTOBUF.parse(inner.toReadableSequentialData());
                    }
                }
            } catch (final Exception e) {
                log.info("verifyBundle: failed to parse inner state value (tag={})", svTag, e);
                return fail();
            }
        }

        if (channel == null) {
            // Manifest-only recovery bundle (spec §8.1.4 manual recovery): a state-proven endpoint
            // manifest with no channel leaf. Accepted only on the V3 (endpoint-manifest-enabled)
            // path, so the CLPR Service can apply the manifest update out-of-band — no gRPC to any
            // (stale) endpoint. With the feature off this stays a hard rejection.
            //
            // messages.isEmpty() is required: unlike Ethereum/Besu (where the distinct 7-item / 4-item
            // wire shape structurally rules out content), a Hiero proof can carry message leaves alongside
            // a manifest leaf. Without this guard such a bundle would return SUCCESS while its messages are
            // silently dropped (no penalty, queue never advances). A message-bearing bundle with no
            // channel leaf is malformed — reject it.
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            if (manifestEnabled && messages.isEmpty() && newEndpointManifest != null) {
                return manifestOnlySuccess(newEndpointManifest);
            }
            return fail();
        }

        // Propagate the peer's local trust_anchor_id so the receiver can identify which
        // signing authority the remote ledger currently has installed for verifying us.
        // For pure-ACK bundles (no message leaves), the chain isn't being extended in
        // this bundle, so we propagate the proven channel's cumulative sent_running_hash
        // unchanged — never EMPTY, since the channel's hash starts at 32 zero-bytes
        // and accumulates from there. Using EMPTY would break the receiver-side step 6
        // (running-hash) invariant.
        final var metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(channel.ackedMessageId() + 1 + messages.size())
                .sentRunningHash(messages.isEmpty() ? channel.sentRunningHash() : lastRunningHash)
                .receivedMessageId(channel.receivedMessageId())
                .receivedRunningHash(channel.receivedRunningHash())
                .status(channel.status())
                .trustAnchorId(channel.trustAnchorId())
                // Spec §4.5: propagate the sender's Channel.endpoint_manifest_version so the
                // receiver can detect whether its cached peer manifest is stale (compared against
                // the receiver's local ClprEndpointManifest.version). Zero here means the sender
                // Channel is PENDING. Consumption is scoped to the sync orchestrator (#335).
                .endpointManifestVersion(channel.endpointManifestVersion())
                .build();

        // Hiero TSS does not (yet) emit ledger-ID successions, so the in-band rotation channel
        // is unused. When succession support lands, set both fields from the proof's commitment
        // — the CLPR Service enforces the "non-empty iff non-empty" invariant on consumption.
        // new_endpoint_manifest is optional (spec §4.9): populated only when the bundle
        // included a state-proven manifest update; the CLPR Service applies the update only
        // when its version advances beyond Channel.endpoint_manifest_version (spec §4.2
        // Step 1b, impl in #333).
        final var bundleContentBuilder = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(messages)
                .newTrustAnchor(Bytes.EMPTY)
                .newTrustAnchorId(Bytes.EMPTY);
        if (newEndpointManifest != null) {
            bundleContentBuilder.newEndpointManifest(newEndpointManifest);
        }
        final var bundleContent = bundleContentBuilder.build();
        final var serialized = ClprBundleContent.PROTOBUF.toBytes(bundleContent);

        if (log.isDebugEnabled()) {
            log.debug(
                    "verifyBundle: trustAnchor={} OK ({} messages, {} bytes)",
                    trustAnchorBytes,
                    messages.size(),
                    serialized.length());
        }
        if (channelContext != null) {
            final boolean manifestEnabled =
                    configOf(frame).getConfigData(ClprConfig.class).endpointManifestEnabled();
            return v2Success(bundleContent, manifestEnabled);
        }
        return gasOnly(
                successResult(
                        VerifyBundleTranslator.VERIFY_BUNDLE
                                .getOutputs()
                                .encode(Tuple.singleton(serialized.toByteArray())),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    @NonNull
    private PricedResult v2Success(@NonNull final ClprBundleContent outContent, final boolean manifestEnabled) {
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
                    true);
        }
        return gasOnly(
                successResult(
                        VerifyBundleTranslator.VERIFY_BUNDLE_V2
                                .getOutputs()
                                .encode(Tuple.of(metaTuple, messageBytes, newTrustAnchor, newTrustAnchorId)),
                        GAS_REQUIREMENT),
                SUCCESS,
                true);
    }

    /**
     * V3 success return for a manifest-only recovery bundle (spec §8.1.4): the endpoint manifest with
     * an empty message set and no trust-anchor rotation. The metadata is signalled absent via a zero
     * {@code nextMessageId} sentinel — a normal bundle's {@code nextMessageId} is always {@code >= 1}
     * ({@code ackedMessageId + 1 + messages.size()}) — so {@link com.hedera.node.app.service.clpr.impl.verifier.EvmClprVerifier}
     * decodes it to a {@code null} metadata and {@code ClprSubmitBundleHandler} takes its
     * state-update-only path (applying the already-extracted manifest).
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
                true);
    }

    @NonNull
    private PricedResult fail() {
        return gasOnly(
                ordinalRevertResult(CLPR_BUNDLE_VERIFICATION_FAILED, GAS_REQUIREMENT),
                CLPR_BUNDLE_VERIFICATION_FAILED,
                true);
    }
}
