// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.util.Objects;

/**
 * Resolved network settings for one direction of the simulated reconnect link.
 *
 * <p>The benchmark resolves its human-facing microsecond and megabit parameters once, before synchronization, so the
 * channel operates directly in nanoseconds, bytes per second, and bytes. The in-flight limit counts bytes accepted from
 * the sender but not yet read by the receiver.
 *
 * @param profile profile from which these settings were resolved
 * @param latencyNanos one-way delay before transmission bytes begin arriving at the receiver, in nanoseconds
 * @param bandwidthBytesPerSecond transmission rate in bytes per second
 * @param inflightBytesLimit maximum accepted-but-unread bytes in this direction
 */
public record NetworkSimulationConfig(
        NetworkProfile profile, long latencyNanos, long bandwidthBytesPerSecond, int inflightBytesLimit) {

    /**
     * Creates and validates resolved network settings.
     *
     * @param profile profile from which these settings were resolved
     * @param latencyNanos one-way latency in nanoseconds
     * @param bandwidthBytesPerSecond transmission rate in bytes per second
     * @param inflightBytesLimit maximum accepted-but-unread bytes
     * @throws NullPointerException if {@code profile} is {@code null}
     * @throws IllegalArgumentException if latency is negative or either limit is not positive
     */
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

    /**
     * Resolves benchmark parameters into the units used by {@link SimulatedNetworkChannel}.
     *
     * <p>{@link NetworkProfile#REALISTIC} converts microseconds to nanoseconds and decimal megabits per second to bytes
     * per second, then retains the supplied in-flight limit. {@link NetworkProfile#LOOPBACK} deliberately ignores the
     * three shaping values and resolves to zero latency, unlimited bandwidth, and an unlimited in-flight limit.
     *
     * @param profile network profile to resolve
     * @param latencyMicroseconds configured one-way latency in microseconds
     * @param bandwidthMegabitsPerSecond configured bandwidth in decimal megabits per second
     * @param inflightBytesLimit configured maximum accepted-but-unread bytes
     * @return validated settings in the units used by the simulated channel
     * @throws NullPointerException if {@code profile} is {@code null}
     * @throws IllegalArgumentException if {@code profile} is {@code REALISTIC} and latency is negative or either limit
     *     is not positive
     * @throws ArithmeticException if a unit conversion overflows a {@code long}
     */
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
