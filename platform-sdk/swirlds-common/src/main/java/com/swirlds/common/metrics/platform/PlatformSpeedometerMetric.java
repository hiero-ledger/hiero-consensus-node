// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.common.metrics.statistics.StatsSpeedometer;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Platform-implementation of {@link SpeedometerMetric}
 */
public class PlatformSpeedometerMetric extends AbstractDistributionMetric implements SpeedometerMetric {

    @SuppressWarnings("removal")
    private final StatsSpeedometer speedometer;

    /**
     * Constructs a new PlatformSpeedometerMetric with the given configuration.
     * @param config the configuration for this speedometer
     */
    public PlatformSpeedometerMetric(@NonNull final SpeedometerMetric.Config config) {
        this(config, Time.getCurrent());
    }

    /**
     * This constructor should only be used for testing.
     */
    @SuppressWarnings("removal")
    public PlatformSpeedometerMetric(final SpeedometerMetric.Config config, final Time time) {
        super(config, config.getHalfLife());
        this.speedometer = new StatsSpeedometer(config.getHalfLife(), time);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @SuppressWarnings("removal")
    @Override
    public StatsBuffered getStatsBuffered() {
        return speedometer;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("removal")
    @Override
    public void update(final double value) {
        speedometer.update(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cycle() {
        update(1);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("removal")
    @Override
    public double get() {
        return speedometer.getCyclesPerSecond();
    }
}
