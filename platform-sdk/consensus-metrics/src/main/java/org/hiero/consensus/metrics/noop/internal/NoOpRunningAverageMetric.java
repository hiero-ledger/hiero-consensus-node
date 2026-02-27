// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.noop.internal;

import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.metrics.RunningAverageMetric;

/**
 * A no-op implementation of a running average metric.
 */
public class NoOpRunningAverageMetric extends AbstractNoOpMetric implements RunningAverageMetric {

    public NoOpRunningAverageMetric(final @NonNull MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Double get(@NonNull final ValueType valueType) {
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
    public double get() {
        return 0;
    }
}
