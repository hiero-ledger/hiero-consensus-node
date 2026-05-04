// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

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
