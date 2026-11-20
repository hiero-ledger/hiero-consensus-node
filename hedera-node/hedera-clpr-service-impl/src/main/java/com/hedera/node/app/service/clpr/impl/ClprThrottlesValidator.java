// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition.MAX_SYNC_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.MAX_STORED_MANIFEST_BYTES;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates CLPR throttles, both those set by a {@code ClprUpdateLedgerConfiguration} and those loaded from a
 * ledger configuration file by {@link ClprLedgerConfigurationFile} (whose synthetic dispatches skip pureChecks).
 */
public final class ClprThrottlesValidator {
    /** Complete empty error reply with a ten-byte uint64 message id, status, and protobuf framing. */
    public static final int MIN_MESSAGE_PAYLOAD_BYTES = 15;

    /** Maximum ledger limits supported by Hiero. Existing advertisements are grandfathered. */
    public static final int MAX_MESSAGE_PAYLOAD_BYTES = 64 * 1024;

    public static final int MAX_MESSAGES_PER_BUNDLE = 1000;

    /** A Hiero sender offers prefixes that fit the native verifier even for a peer advertising a larger sync. */
    public static final int VERIFIABLE_SYNC_BYTES = 128 * 1024;

    /** Covers the verifier selector, three dynamic arguments, trust anchor, channel context and word padding. */
    public static final int VERIFIER_CALL_OVERHEAD_BYTES = 2 * 1024;

    // In addition to the cached manifest, allow 14 KiB for the 13,712-byte native TSS signature
    // and 10 KiB for both Merkle paths, other channel fields and protobuf framing.
    private static final int SINGLE_MESSAGE_PROOF_OVERHEAD_BYTES = MAX_STORED_MANIFEST_BYTES + (14 + 10) * 1024;

    /** Conservative wire framing reserve above the serialized StateProof. */
    static final long CLPR_SYNC_ENVELOPE_OVERHEAD_BYTES = 114L;

    // The original 64 KiB reserve included 114 bytes for framing. Keep the proof allowance fixed
    // so a change to the wire framing reserve is reflected in the total.
    private static final long MIN_STATE_PROOF_OVERHEAD_BYTES = 65_422L;

    /**
     * Conservative floor on the headroom max_sync_bytes must reserve above max_message_payload_bytes
     * for the proof envelope (channel leaf + one message leaf + sibling chain to block root +
     * TSS signature + ClprSyncPayload framing). Spec §1.1 explicitly warns that if max_sync_bytes
     * cannot carry a single max-sized message plus its proof overhead, the Channel deadlocks —
     * the sender can never build a bundle that fits, the peer never acks, and the queue stalls
     * forever. Hiero senders may split a queue across proofs.
     * The channel leaf includes the cached peer manifest. The sender accounts for its storage cap
     * in its single-message budget and checks the actual serialized proof before transmission.
     */
    public static final long MIN_PROOF_OVERHEAD_BYTES =
            MIN_STATE_PROOF_OVERHEAD_BYTES + CLPR_SYNC_ENVELOPE_OVERHEAD_BYTES;

    private ClprThrottlesValidator() {}

    /** Proof bytes available after reserving the verifier's ABI arguments in the consensus call. */
    static int senderProofBudget(@NonNull final Configuration configuration) {
        final int calldata =
                configuration.getConfigData(JumboTransactionsConfig.class).ethereumMaxCallDataSize();
        return Math.clamp(calldata, VERIFIER_CALL_OVERHEAD_BYTES, VERIFIABLE_SYNC_BYTES) - VERIFIER_CALL_OVERHEAD_BYTES;
    }

    /** Serialized DATA or reply bytes available after reserving the proof that carries one message. */
    public static long senderPayloadBudget(@NonNull final Configuration configuration) {
        return Math.clamp(
                senderProofBudget(configuration) - SINGLE_MESSAGE_PROOF_OVERHEAD_BYTES, 0, MAX_MESSAGE_PAYLOAD_BYTES);
    }

    /** Applies the implementation ceilings before the caller restores any previously advertised floors. */
    public static ClprThrottles capDeliveryLimits(@NonNull final ClprThrottles requested, final int queueCeiling) {
        return requested
                .copyBuilder()
                .maxMessagePayloadBytes(Math.min(requested.maxMessagePayloadBytes(), MAX_MESSAGE_PAYLOAD_BYTES))
                .maxSyncBytes(Math.min(requested.maxSyncBytes(), MAX_SYNC_BYTES))
                .maxMessagesPerBundle(Math.min(requested.maxMessagesPerBundle(), MAX_MESSAGES_PER_BUNDLE))
                .maxQueueDepth(Math.min(requested.maxQueueDepth(), queueCeiling))
                .build();
    }

    /**
     * Preserves the local limits that peers may already have used to enqueue DATA or build a bundle.
     * Gas and endpoint limits follow the requested configuration.
     */
    public static ClprThrottles preserveAdvertisedLimits(
            @NonNull final ClprThrottles requested, @NonNull final ClprThrottles current) {
        return requested
                .copyBuilder()
                .maxMessagePayloadBytes(Math.max(requested.maxMessagePayloadBytes(), current.maxMessagePayloadBytes()))
                .maxSyncBytes(Math.max(requested.maxSyncBytes(), current.maxSyncBytes()))
                .maxMessagesPerBundle(Math.max(requested.maxMessagesPerBundle(), current.maxMessagesPerBundle()))
                .maxQueueDepth(Math.max(requested.maxQueueDepth(), current.maxQueueDepth()))
                .build();
    }

    /**
     * Returns whether the given throttles are valid: all fields positive, the payload limit can carry
     * any empty error reply, and max_sync_bytes leaves room for one max-sized message plus its proof overhead.
     *
     * @param throttles the throttles to check
     * @return whether the throttles are valid
     */
    public static boolean areValidThrottles(@NonNull final ClprThrottles throttles) {
        requireNonNull(throttles);
        return throttles.maxMessagesPerBundle() > 0
                && throttles.maxMessagePayloadBytes() >= MIN_MESSAGE_PAYLOAD_BYTES
                && throttles.maxQueueDepth() > 0
                && throttles.maxGasPerMessage() > 0
                && throttles.maxSyncBytes() > 0
                // Spec §1.1 deadlock guard: max_sync_bytes MUST leave room for one max-sized message
                // plus its proof+envelope overhead. Otherwise the first oversized message enqueued
                // stalls the Channel — sender can never assemble a bundle that fits, peer never
                // acks, queue never drains.
                && throttles.maxSyncBytes() >= (long) throttles.maxMessagePayloadBytes() + MIN_PROOF_OVERHEAD_BYTES;
    }
}
