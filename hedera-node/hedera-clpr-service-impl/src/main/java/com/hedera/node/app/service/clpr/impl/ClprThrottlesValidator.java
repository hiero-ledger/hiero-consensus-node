// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprThrottles;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates CLPR throttles, both those set by a {@code ClprUpdateLedgerConfiguration} and those loaded from a
 * ledger configuration file by {@link ClprLedgerConfigurationFile} (whose synthetic dispatches skip pureChecks).
 */
public final class ClprThrottlesValidator {
    /**
     * Conservative floor on the headroom max_sync_bytes must reserve above max_message_payload_bytes
     * for the proof envelope (channel leaf + one message leaf + sibling chain to block root +
     * TSS signature + ClprSyncPayload framing). Spec §1.1 explicitly warns that if max_sync_bytes
     * cannot carry a single max-sized message plus its proof overhead, the Channel deadlocks —
     * the sender can never build a bundle that fits, the peer never acks, and the queue stalls
     * forever. This is a floor, not a sizing recommendation; operators should size generously per
     * the spec's rule of thumb {@code max_message_payload_bytes * max_messages_per_bundle +
     * worst_case_proof_overhead}.
     */
    public static final long MIN_PROOF_OVERHEAD_BYTES = 65_536L;

    private ClprThrottlesValidator() {}

    /**
     * Returns whether the given throttles are valid: all fields positive, and max_sync_bytes leaves room
     * for one max-sized message plus its proof overhead.
     *
     * @param throttles the throttles to check
     * @return whether the throttles are valid
     */
    public static boolean areValidThrottles(@NonNull final ClprThrottles throttles) {
        requireNonNull(throttles);
        return throttles.maxMessagesPerBundle() > 0
                && throttles.maxMessagePayloadBytes() > 0
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
