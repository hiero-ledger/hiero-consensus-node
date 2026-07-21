// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.util.Objects;

/**
 * Resolved refined-A1 settings for the reconnect benchmark's loopback socket transport.
 *
 * <p>The configured values always preserve the supplied comparison target. The modeled values say what the
 * visibility scheduler actually applies. This distinction allows {@link NetworkProfile#INSTRUMENTED_LOOPBACK} to
 * use exactly the same range splitting as {@link NetworkProfile#REALISTIC} while applying neither latency nor a
 * bandwidth limit.
 *
 * @param profile the selected network profile
 * @param configuredLatencyNanos supplied target one-way latency in nanoseconds
 * @param configuredBandwidthBytesPerSecond supplied target bandwidth in bytes per second
 * @param modeledLatencyNanos effective one-way latency applied by the visibility scheduler
 * @param modeledBandwidthBytesPerSecond effective bandwidth applied by the visibility scheduler; {@link
 *     Long#MAX_VALUE} represents unlimited bandwidth
 * @param releaseQuantumNanos target-derived maximum algorithmic release quantum
 * @param maxObservedRangeBytes maximum number of bytes published as one pre-write observed range
 */
public record SocketNetworkConfig(
        NetworkProfile profile,
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        long modeledLatencyNanos,
        long modeledBandwidthBytesPerSecond,
        long releaseQuantumNanos,
        int maxObservedRangeBytes) {

    private static final long NANOS_PER_MICROSECOND = 1_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long BYTES_PER_SECOND_PER_MEGABIT = 125_000L;
    private static final long MAX_RELEASE_QUANTUM_NANOS = 50_000L;
    private static final int MAX_OBSERVED_RANGE_BYTES = 8 * 1_024;

    public SocketNetworkConfig {
        Objects.requireNonNull(profile, "profile must not be null");
        if (configuredLatencyNanos < 0) {
            throw new IllegalArgumentException("configuredLatencyNanos must be non-negative");
        }
        if (configuredBandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("configuredBandwidthBytesPerSecond must be positive");
        }
        if (modeledLatencyNanos < 0) {
            throw new IllegalArgumentException("modeledLatencyNanos must be non-negative");
        }
        if (modeledBandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("modeledBandwidthBytesPerSecond must be positive");
        }

        if (profile == NetworkProfile.LOOPBACK) {
            if (modeledLatencyNanos != 0
                    || modeledBandwidthBytesPerSecond != Long.MAX_VALUE
                    || releaseQuantumNanos != 0
                    || maxObservedRangeBytes != 0) {
                throw new IllegalArgumentException("LOOPBACK must not configure visibility-scheduling components");
            }
        } else {
            final long expectedReleaseQuantumNanos = releaseQuantum(configuredLatencyNanos);
            final int expectedMaxObservedRangeBytes =
                    observedRangeBytes(configuredBandwidthBytesPerSecond, expectedReleaseQuantumNanos);
            if (releaseQuantumNanos != expectedReleaseQuantumNanos) {
                throw new IllegalArgumentException("releaseQuantumNanos must match the configured target latency");
            }
            if (maxObservedRangeBytes != expectedMaxObservedRangeBytes) {
                throw new IllegalArgumentException(
                        "maxObservedRangeBytes must match the configured target bandwidth and release quantum");
            }

            if (profile == NetworkProfile.REALISTIC
                    && (modeledLatencyNanos != configuredLatencyNanos
                            || modeledBandwidthBytesPerSecond != configuredBandwidthBytesPerSecond)) {
                throw new IllegalArgumentException("REALISTIC must model the configured latency and bandwidth");
            }
            if (profile == NetworkProfile.INSTRUMENTED_LOOPBACK
                    && (modeledLatencyNanos != 0 || modeledBandwidthBytesPerSecond != Long.MAX_VALUE)) {
                throw new IllegalArgumentException("INSTRUMENTED_LOOPBACK must not apply latency or bandwidth");
            }
        }
    }

    /**
     * Resolves user-facing microsecond and megabit values without losing the supplied target under control profiles.
     *
     * @param profile the network profile
     * @param latencyMicroseconds target one-way latency in microseconds
     * @param bandwidthMegabitsPerSecond target bandwidth in megabits per second
     * @return a validated resolved configuration
     * @throws NullPointerException if {@code profile} is {@code null}
     * @throws IllegalArgumentException if latency is negative or bandwidth is not positive
     * @throws ArithmeticException if either unit conversion overflows
     */
    public static SocketNetworkConfig resolve(
            final NetworkProfile profile, final long latencyMicroseconds, final long bandwidthMegabitsPerSecond) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (latencyMicroseconds < 0) {
            throw new IllegalArgumentException("latencyMicroseconds must be non-negative");
        }
        if (bandwidthMegabitsPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthMegabitsPerSecond must be positive");
        }

        final long configuredLatencyNanos = Math.multiplyExact(latencyMicroseconds, NANOS_PER_MICROSECOND);
        final long configuredBandwidthBytesPerSecond =
                Math.multiplyExact(bandwidthMegabitsPerSecond, BYTES_PER_SECOND_PER_MEGABIT);

        if (profile == NetworkProfile.LOOPBACK) {
            return new SocketNetworkConfig(
                    profile, configuredLatencyNanos, configuredBandwidthBytesPerSecond, 0, Long.MAX_VALUE, 0, 0);
        }

        final long releaseQuantumNanos = releaseQuantum(configuredLatencyNanos);
        final int maxObservedRangeBytes = observedRangeBytes(configuredBandwidthBytesPerSecond, releaseQuantumNanos);
        final boolean shapingEnabled = profile == NetworkProfile.REALISTIC;
        return new SocketNetworkConfig(
                profile,
                configuredLatencyNanos,
                configuredBandwidthBytesPerSecond,
                shapingEnabled ? configuredLatencyNanos : 0,
                shapingEnabled ? configuredBandwidthBytesPerSecond : Long.MAX_VALUE,
                releaseQuantumNanos,
                maxObservedRangeBytes);
    }

    /** Returns whether the refined-A1 observer and receiver gate are installed. */
    public boolean visibilitySchedulingActive() {
        return profile != NetworkProfile.LOOPBACK;
    }

    /** Returns whether the effective profile applies a positive visibility latency. */
    public boolean latencyShapingActive() {
        return visibilitySchedulingActive() && modeledLatencyNanos > 0;
    }

    /** Returns whether the effective profile applies a finite bandwidth. */
    public boolean bandwidthShapingActive() {
        return visibilitySchedulingActive() && modeledBandwidthBytesPerSecond != Long.MAX_VALUE;
    }

    private static long releaseQuantum(final long configuredLatencyNanos) {
        if (configuredLatencyNanos == 0) {
            return MAX_RELEASE_QUANTUM_NANOS;
        }
        return Math.min(MAX_RELEASE_QUANTUM_NANOS, Math.max(1L, configuredLatencyNanos / 10L));
    }

    /** Computes {@code floor(bytesPerSecond * quantumNanos / 1 second)} without overflowing the product. */
    private static int observedRangeBytes(final long bytesPerSecond, final long quantumNanos) {
        final long wholeSecondsContribution = (bytesPerSecond / NANOS_PER_SECOND) * quantumNanos;
        final long fractionalSecondContribution =
                ((bytesPerSecond % NANOS_PER_SECOND) * quantumNanos) / NANOS_PER_SECOND;
        final long bytes = wholeSecondsContribution + fractionalSecondContribution;
        return (int) Math.min(MAX_OBSERVED_RANGE_BYTES, Math.max(1L, bytes));
    }
}
