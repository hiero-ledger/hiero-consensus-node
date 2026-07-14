// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Observed traffic and blocking diagnostics for one direction of a simulated reconnect link.
 *
 * <p>Wait durations measure wall-clock time spent inside channel waits. They include thread-scheduling overhead, may
 * overlap with work on other reconnect threads, and must not be added together as modeled network delay or reconnect
 * elapsed time.
 *
 * @param bytesWritten bytes accepted from the sender
 * @param bytesRead bytes delivered to the receiver
 * @param maxInflightBytes largest observed accepted-but-unread byte count
 * @param capacityWaitCount waits because the in-flight byte limit was full
 * @param capacityWaitNanos observed wall-clock time blocked for in-flight capacity
 * @param emptyReadWaitNanos observed wall-clock time waiting for the peer to produce bytes
 * @param arrivalWaitNanos observed wall-clock time waiting for scheduled arrival, including scheduler overhead
 */
public record SimulatedNetworkStats(
        long bytesWritten,
        long bytesRead,
        long maxInflightBytes,
        long capacityWaitCount,
        long capacityWaitNanos,
        long emptyReadWaitNanos,
        long arrivalWaitNanos) {}
