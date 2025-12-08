// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.internal.helpers.Utils;

/**
 * ChaosBotConfiguration for the chaos bot.
 *
 * @param minInterval the minimum interval between experiments
 * @param maxInterval the maximum interval between experiments
 * @param seed the seed for randomness (optional, if {@code null} a random seed will be used)
 * @param experiments the set of experiments to run
 */
public record ChaosBotConfiguration(
        @NonNull Duration minInterval,
        @NonNull Duration maxInterval,
        @Nullable Long seed,
        @NonNull List<Experiment> experiments) {

    /** Default configuration for the chaos bot. */
    public static final ChaosBotConfiguration DEFAULT = new ChaosBotConfiguration(
            Duration.ofSeconds(30L),
            Duration.ofSeconds(90L),
            null,
            List.of(
                    new HighLatencyNodeExperiment(),
                    new LowBandwidthNodeExperiment(),
                    new NetworkPartitionExperiment(),
                    new NodeFailureExperiment()));

    /**
     * Create a new configuration.
     *
     * @param minInterval the minimum interval between experiments
     * @param maxInterval the maximum interval between experiments
     * @param seed the seed for randomness
     * @param experiments the set of experiments to run
     */
    public ChaosBotConfiguration {
        requireNonNull(minInterval);
        requireNonNull(maxInterval);
        requireNonNull(experiments);
    }

    /**
     * Create a new configuration with the specified minimum Interval.
     *
     * <p>If the new minimum interval is greater than the current maximum interval, the maximum interval is adjusted to be equal to the new minimum interval.
     *
     * @param newMinInterval the new minimum interval
     * @return the new configuration
     */
    public ChaosBotConfiguration withMinInterval(@NonNull final Duration newMinInterval) {
        final Duration newMaxInterval = newMinInterval.compareTo(maxInterval) > 0 ? newMinInterval : maxInterval;
        return new ChaosBotConfiguration(newMinInterval, newMaxInterval, this.seed, this.experiments);
    }

    /**
     * Create a new configuration with the specified maximum Interval.
     *
     * <p>If the new maximum interval is less than the current minimum interval, the minimum interval is adjusted to be equal to the new maximum interval.
     *
     * @param newMaxInterval the new maximum interval
     * @return the new configuration
     */
    public ChaosBotConfiguration withMaxInterval(@NonNull final Duration newMaxInterval) {
        final Duration newMinInterval = newMaxInterval.compareTo(minInterval) < 0 ? newMaxInterval : minInterval;
        return new ChaosBotConfiguration(newMinInterval, newMaxInterval, this.seed, this.experiments);
    }

    /**
     * Create a new configuration with the specified seed.
     *
     * @param newSeed the new seed
     * @return the new configuration
     */
    public ChaosBotConfiguration withSeed(final long newSeed) {
        return new ChaosBotConfiguration(this.minInterval, this.maxInterval, newSeed, this.experiments);
    }

    /**
     * Create a new configuration with the specified experiments.
     *
     * @param newExperiments the new experiments
     * @return the new configuration
     */
    public ChaosBotConfiguration withExperiments(@NonNull final List<Experiment> newExperiments) {
        return new ChaosBotConfiguration(this.minInterval, this.maxInterval, this.seed, newExperiments);
    }

    /**
     * Create a new configuration with the specified experiments.
     *
     * @param first the first experiment
     * @param others the other experiments
     * @return the new configuration
     */
    public ChaosBotConfiguration withExperiments(@NonNull final Experiment first, @NonNull final Experiment... others) {
        return new ChaosBotConfiguration(this.minInterval, this.maxInterval, this.seed, Utils.toList(first, others));
    }
}
