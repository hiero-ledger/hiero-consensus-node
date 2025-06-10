// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.MetricConfig;

/**
 * A no-op implementation of a long gauge.
 */
public class NoOpLongGauge extends AbstractNoOpMetric implements LongGauge {

    public NoOpLongGauge(final MetricConfig<?, ?> config) {
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
    public void set(final long newValue) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final long change) {}
}
