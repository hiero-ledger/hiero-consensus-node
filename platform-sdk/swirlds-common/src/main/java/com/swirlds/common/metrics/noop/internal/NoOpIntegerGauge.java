// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.MetricConfig;

/**
 * A no-op implementation of an integer gauge.
 */
public class NoOpIntegerGauge extends AbstractNoOpMetric implements IntegerGauge {

    public NoOpIntegerGauge(final MetricConfig<?, ?> config) {
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
    public void set(final int newValue) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final int change) {}
}
