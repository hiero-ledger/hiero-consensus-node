// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op counter.
 */
public class NoOpCounter extends AbstractNoOpMetric implements Counter {

    public NoOpCounter(final @NonNull MetricConfig<?, ?> config) {
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
    public void add(final long value) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void increment() {}
}
