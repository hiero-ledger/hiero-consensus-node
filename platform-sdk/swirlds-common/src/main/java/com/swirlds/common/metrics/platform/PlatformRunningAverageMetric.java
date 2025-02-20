// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.base.time.Time;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.common.metrics.statistics.StatsRunningAverage;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Platform-implementation of {@link RunningAverageMetric}
 */
@SuppressWarnings("unused")
public class PlatformRunningAverageMetric extends AbstractDistributionMetric implements RunningAverageMetric {

    @SuppressWarnings("removal")
    private final @NonNull StatsRunningAverage runningAverage;

    /**
     * Constructs a new PlatformRunningAverageMetric with the given configuration.
     * @param config the configuration for this running average
     */
    public PlatformRunningAverageMetric(@NonNull final RunningAverageMetric.Config config) {
        this(config, Time.getCurrent());
    }

    /**
     * This constructor should only be used for testing.
     */
    @SuppressWarnings("removal")
    public PlatformRunningAverageMetric(final RunningAverageMetric.Config config, final Time time) {
        super(config, config.getHalfLife());
        this.runningAverage = new StatsRunningAverage(halfLife, time);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @SuppressWarnings("removal")
    @Override
    public StatsBuffered getStatsBuffered() {
        return runningAverage;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("removal")
    @Override
    public void update(final double value) {
        runningAverage.recordValue(value);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("removal")
    @Override
    public double get() {
        return runningAverage.getWeightedMean();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("halfLife", halfLife)
                .append("value", get())
                .toString();
    }
}
