// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.MetricConfig;

/**
 * A no-op implementation of a long accumulator.
 */
public class NoOpLongAccumulator extends AbstractNoOpMetric implements LongAccumulator {

    public NoOpLongAccumulator(final MetricConfig<?, ?> config) {
        super(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long get() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getInitialValue() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final long other) {}
}
