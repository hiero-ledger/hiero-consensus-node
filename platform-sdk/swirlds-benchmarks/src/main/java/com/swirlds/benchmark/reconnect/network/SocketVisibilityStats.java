// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Immutable diagnostics for one refined-A1 socket direction.
 *
 * <p>Times describe the application-visible eligibility model and its local implementation overhead. They are not
 * measurements of TCP packet transit, ACK timing, or a physical link.
 *
 * @param configuredLatencyNanos comparison-target sender-observation-to-visibility latency
 * @param configuredBandwidthBytesPerSecond comparison-target payload bandwidth
 * @param modeledLatencyNanos effective sender-observation-to-visibility latency
 * @param modeledBandwidthBytesPerSecond effective payload eligibility bandwidth
 * @param releaseQuantumNanos maximum target coalescing interval
 * @param maxObservedRangeBytes maximum raw write range
 * @param observedBytes compressed payload bytes observed before raw writes
 * @param scheduledBytes compressed payload bytes assigned an eligibility schedule
 * @param returnedBytes compressed payload bytes returned by the receiver gate
 * @param rangeCount number of observed ranges
 * @param rangeSizeP50Bytes approximate median observed range size
 * @param rangeSizeP99Bytes approximate p99 observed range size
 * @param maxRangeSizeBytes largest observed range
 * @param pendingRanges ranges not completely consumed when the snapshot was taken
 * @param pendingBytes bytes not completely consumed when the snapshot was taken
 * @param maxPendingRanges largest pending range count
 * @param maxPendingBytes largest pending byte count
 * @param maxSerializationBacklogBytes largest estimated byte prefix still scheduled on the future serialization
 *     timeline, excluding already-serialized application-undrained data
 * @param maxSerializationBacklogNanos largest scheduled serialization backlog
 * @param timingWakeCount timed eligibility waits
 * @param metadataWaitNanos time waiting for sender metadata
 * @param latencyWaitNanos time waiting in the latency portion of eligibility
 * @param bandwidthWaitNanos time waiting in the serialization portion of eligibility
 * @param releaseLatenessSamples ranges sampled after a timed eligibility wake
 * @param releaseLatenessP50Nanos approximate median timed-wake lateness
 * @param releaseLatenessP99Nanos approximate p99 timed-wake lateness
 * @param maxReleaseLatenessNanos maximum scheduler lateness
 * @param releasesOverQuarterLatency ranges released more than one quarter-latency late
 * @param observerToFirstReturnSamples ranges whose first raw byte was returned by the gate
 * @param observerToFirstReturnP50Nanos approximate median observation-to-first-return duration
 * @param observerToFirstReturnP99Nanos approximate p99 observation-to-first-return duration
 * @param maxObserverToFirstReturnNanos maximum observation-to-first-return duration
 * @param rawReadCount successful raw reads
 * @param rawReadSizeP50Bytes approximate median raw read size
 * @param rawReadSizeP99Bytes approximate p99 raw read size
 * @param maxRawReadSizeBytes largest raw read
 * @param rawReadWaitNanos total raw read duration after eligibility
 * @param maxRawReadWaitNanos maximum raw read duration after eligibility
 * @param failedRawReads failed raw reads
 * @param rawWriteCount raw write attempts
 * @param rawWriteDurationNanos total raw write duration
 * @param rawWriteDurationP99Nanos approximate p99 raw write duration
 * @param maxRawWriteDurationNanos maximum raw write duration
 * @param rawWriteBytesOverQuarterLatency bytes in writes lasting more than one quarter of target latency
 * @param rawWriteBytesOverSerializationDuration bytes in writes lasting longer than target serialization duration
 * @param rawWriteBytesOverEitherTarget bytes in writes lasting longer than either the quarter-latency or target
 *     serialization-duration limit; a range which violates both limits is counted once
 * @param failedRawWrites failed or ambiguously partial raw writes
 * @param state controller lifecycle state
 */
public record SocketVisibilityStats(
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        long modeledLatencyNanos,
        long modeledBandwidthBytesPerSecond,
        long releaseQuantumNanos,
        int maxObservedRangeBytes,
        long observedBytes,
        long scheduledBytes,
        long returnedBytes,
        long rangeCount,
        long rangeSizeP50Bytes,
        long rangeSizeP99Bytes,
        long maxRangeSizeBytes,
        int pendingRanges,
        long pendingBytes,
        int maxPendingRanges,
        long maxPendingBytes,
        long maxSerializationBacklogBytes,
        long maxSerializationBacklogNanos,
        long timingWakeCount,
        long metadataWaitNanos,
        long latencyWaitNanos,
        long bandwidthWaitNanos,
        long releaseLatenessSamples,
        long releaseLatenessP50Nanos,
        long releaseLatenessP99Nanos,
        long maxReleaseLatenessNanos,
        long releasesOverQuarterLatency,
        long observerToFirstReturnSamples,
        long observerToFirstReturnP50Nanos,
        long observerToFirstReturnP99Nanos,
        long maxObserverToFirstReturnNanos,
        long rawReadCount,
        long rawReadSizeP50Bytes,
        long rawReadSizeP99Bytes,
        long maxRawReadSizeBytes,
        long rawReadWaitNanos,
        long maxRawReadWaitNanos,
        long failedRawReads,
        long rawWriteCount,
        long rawWriteDurationNanos,
        long rawWriteDurationP99Nanos,
        long maxRawWriteDurationNanos,
        long rawWriteBytesOverQuarterLatency,
        long rawWriteBytesOverSerializationDuration,
        long rawWriteBytesOverEitherTarget,
        long failedRawWrites,
        String state) {}
