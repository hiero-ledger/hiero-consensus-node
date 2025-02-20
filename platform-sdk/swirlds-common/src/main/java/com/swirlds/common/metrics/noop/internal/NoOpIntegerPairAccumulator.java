// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op implementation of an integer pair accumulator.
 */
public class NoOpIntegerPairAccumulator<T> extends AbstractNoOpMetric implements IntegerPairAccumulator<T> {

    private final @NonNull T value;

    public NoOpIntegerPairAccumulator(final @NonNull MetricConfig<?, ?> config, final @NonNull T value) {
        super(config);
        this.value = value;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public T get() {
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLeft() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRight() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void update(final int leftValue, final int rightValue) {}

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public DataType getDataType() {
        return DataType.INT;
    }
}
