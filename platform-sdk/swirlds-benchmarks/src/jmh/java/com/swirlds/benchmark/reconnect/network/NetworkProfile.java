// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Selects how {@code ReconnectBench} configures its in-memory simulated network channels.
 *
 * <p>Both profiles use {@link SimulatedNetworkChannel}; the profile controls only whether latency, bandwidth, and
 * in-flight backpressure are applied. It does not select a socket transport.
 */
public enum NetworkProfile {
    /** Applies the latency, bandwidth, and in-flight byte limit supplied to the benchmark. */
    REALISTIC,

    /** Disables network shaping while retaining the same in-memory channel and stream behavior. */
    LOOPBACK
}
