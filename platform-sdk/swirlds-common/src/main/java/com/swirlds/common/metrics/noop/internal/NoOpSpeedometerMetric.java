// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op implementation of a speedometer metric.
 */
public class NoOpSpeedometerMetric extends AbstractNoOpMetric implements SpeedometerMetric {

    public NoOpSpeedometerMetric(final @NonNull MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Double get(final @NonNull ValueType valueType) {
        return 0.0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getHalfLife() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final double value) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void cycle() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public double get() {
        return 0;
    }
}
