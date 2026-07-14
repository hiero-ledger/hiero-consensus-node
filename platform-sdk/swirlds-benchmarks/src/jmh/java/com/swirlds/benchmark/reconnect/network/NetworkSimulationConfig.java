// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.util.Objects;

public record NetworkSimulationConfig(
        NetworkProfile profile, long latencyNanos, long bandwidthBytesPerSecond, int inflightBytesLimit) {

    public NetworkSimulationConfig {
        Objects.requireNonNull(profile, "profile must not be null");
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        if (inflightBytesLimit <= 0) {
            throw new IllegalArgumentException("inflightBytesLimit must be positive");
        }
    }

    public static NetworkSimulationConfig resolve(
            final NetworkProfile profile,
            final long latencyMicroseconds,
            final long bandwidthMegabitsPerSecond,
            final int inflightBytesLimit) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (profile == NetworkProfile.LOOPBACK) {
            return new NetworkSimulationConfig(profile, 0, Long.MAX_VALUE, Integer.MAX_VALUE);
        }
        if (latencyMicroseconds < 0) {
            throw new IllegalArgumentException("latencyMicroseconds must be non-negative");
        }
        if (bandwidthMegabitsPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthMegabitsPerSecond must be positive");
        }
        if (inflightBytesLimit <= 0) {
            throw new IllegalArgumentException("inflightBytesLimit must be positive");
        }

        final long latencyNanos = Math.multiplyExact(latencyMicroseconds, 1_000L);
        final long bandwidthBytesPerSecond = Math.multiplyExact(bandwidthMegabitsPerSecond, 1_000_000L) / 8L;
        return new NetworkSimulationConfig(profile, latencyNanos, bandwidthBytesPerSecond, inflightBytesLimit);
    }
}
