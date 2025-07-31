// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.data.Percentage.withPercentage;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;

/**
 * Represents a range of latency values with jitter for network connections. Used to define realistic latency
 * characteristics for different geographic connection types.
 */
@SuppressWarnings("unused")
public record LatencyRange(@NonNull Duration min, @NonNull Duration max, @NonNull Percentage jitterPercent) {

    public static final LatencyRange SAME_REGION_DEFAULT =
            LatencyRange.of(Duration.ofMillis(5), Duration.ofMillis(30), withPercentage(7.5));
    public static final LatencyRange SAME_CONTINENT_DEFAULT =
            LatencyRange.of(Duration.ofMillis(30), Duration.ofMillis(80), withPercentage(10));
    public static final LatencyRange INTERCONTINENTAL_DEFAULT =
            LatencyRange.of(Duration.ofMillis(80), Duration.ofMillis(300), withPercentage(12.5));

    /**
     * Creates a latency range with specified minimum and maximum durations and jitter percentage.
     *
     * @param min the minimum latency duration
     * @param max the maximum latency duration
     * @param jitterPercent the percentage of jitter to apply to the latency
     * @return a new {@code LatencyRange}
     * @throws NullPointerException if {@code min}, {@code max}, or {@code jitterPercent} is {@code null}
     * @throws IllegalArgumentException if {@code min} is negative, or if {@code min} is greater than {@code max}
     */
    @NonNull
    public static LatencyRange of(
            @NonNull final Duration min, @NonNull final Duration max, @NonNull final Percentage jitterPercent) {
        return new LatencyRange(min, max, jitterPercent);
    }

    /**
     * Creates a latency range with specified minimum and maximum durations and jitter percentage.
     *
     * @param min the minimum latency duration
     * @param max the maximum latency duration
     * @param jitterPercent the percentage of jitter to apply to the latency
     * @throws NullPointerException if {@code min}, {@code max}, or {@code jitterPercent} is {@code null}
     * @throws IllegalArgumentException if {@code min} is negative, or if {@code min} is greater than {@code max}
     */
    public LatencyRange {
        if (min.isNegative()) {
            throw new IllegalArgumentException("Latency durations must be non-negative");
        }
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Maximum latency must be greater than or equal to minimum latency");
        }
        if (jitterPercent.value < 0) {
            throw new IllegalArgumentException("Jitter percentage must be non-negative");
        }
        requireNonNull(jitterPercent);
    }
}
