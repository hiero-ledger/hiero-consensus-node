// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op implementation of a double gauge.
 */
public class NoOpDoubleGauge extends AbstractNoOpMetric implements DoubleGauge {

    public NoOpDoubleGauge(final @NonNull MetricConfig<?, ?> config) {
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
    public void set(final double newValue) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(final double change) {}
}
