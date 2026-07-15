// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.util.Objects;

/**
 * Resolved latency and bandwidth settings for the reconnect benchmark's loopback socket transport.
 *
 * @param profile the selected network profile
 * @param latencyNanos configured one-way latency in nanoseconds
 * @param bandwidthBytesPerSecond configured bandwidth in bytes per second
 */
public record SocketNetworkConfig(NetworkProfile profile, long latencyNanos, long bandwidthBytesPerSecond) {

    public SocketNetworkConfig {
        Objects.requireNonNull(profile, "profile must not be null");
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
    }

    public static SocketNetworkConfig resolve(
            final NetworkProfile profile, final long latencyMicroseconds, final long bandwidthMegabitsPerSecond) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (profile == NetworkProfile.LOOPBACK) {
            return new SocketNetworkConfig(profile, 0, Long.MAX_VALUE);
        }
        if (latencyMicroseconds < 0) {
            throw new IllegalArgumentException("latencyMicroseconds must be non-negative");
        }
        if (bandwidthMegabitsPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthMegabitsPerSecond must be positive");
        }

        final long latencyNanos = Math.multiplyExact(latencyMicroseconds, 1_000L);
        final long bandwidthBytesPerSecond = Math.multiplyExact(bandwidthMegabitsPerSecond, 1_000_000L) / 8L;
        return new SocketNetworkConfig(profile, latencyNanos, bandwidthBytesPerSecond);
    }
}
