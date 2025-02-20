// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.MetricConfig;

/**
 * A no-op implementation of an integer accumulator.
 */
public class NoOpIntegerAccumulator extends AbstractNoOpMetric implements IntegerAccumulator {

    public NoOpIntegerAccumulator(final MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int get() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getInitialValue() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final int other) {}
}
