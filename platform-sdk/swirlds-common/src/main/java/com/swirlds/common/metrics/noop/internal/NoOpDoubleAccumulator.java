// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op double accumulator.
 */
public class NoOpDoubleAccumulator extends AbstractNoOpMetric implements DoubleAccumulator {

    public NoOpDoubleAccumulator(final @NonNull MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double get() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getInitialValue() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final double other) {}
}
