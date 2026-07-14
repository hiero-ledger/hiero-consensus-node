// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Observed traffic and blocking diagnostics for one direction of a simulated reconnect link.
 *
 * @param bytesWritten bytes accepted from the sender
 * @param bytesRead bytes delivered to the receiver
 * @param maxInflightBytes largest observed accepted-but-unread byte count
 * @param writeCalls top-level sender write calls
 * @param writeRanges byte ranges scheduled after splitting large writes
 * @param readCalls top-level receiver read calls
 * @param capacityWaitCount waits because the in-flight byte limit was full
 * @param capacityWaitNanos observed wall-clock time blocked for in-flight capacity
 * @param emptyReadWaitCount waits because the peer had not produced any queued bytes
 * @param emptyReadWaitNanos observed wall-clock time waiting for the peer to produce bytes
 * @param arrivalWaitCount waits for queued bytes to reach their simulated arrival time
 * @param arrivalWaitNanos observed wall-clock time waiting for scheduled arrival, including scheduler overhead
 */
public record SimulatedNetworkStats(
        long bytesWritten,
        long bytesRead,
        long maxInflightBytes,
        long writeCalls,
        long writeRanges,
        long readCalls,
        long capacityWaitCount,
        long capacityWaitNanos,
        long emptyReadWaitCount,
        long emptyReadWaitNanos,
        long arrivalWaitCount,
        long arrivalWaitNanos) {

    public SimulatedNetworkStats(final long bytesWritten, final long bytesRead, final long maxInflightBytes) {
        this(bytesWritten, bytesRead, maxInflightBytes, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
