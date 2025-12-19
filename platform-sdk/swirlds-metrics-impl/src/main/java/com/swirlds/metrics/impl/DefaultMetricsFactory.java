// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.FunctionGauge;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.MetricsFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link MetricsFactory}
 */
public class DefaultMetricsFactory implements MetricsFactory {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Counter createCounter(@NonNull final Counter.Config config) {
        return new DefaultCounter(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public DoubleAccumulator createDoubleAccumulator(@NonNull final DoubleAccumulator.Config config) {
        return new DefaultDoubleAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public DoubleGauge createDoubleGauge(@NonNull final DoubleGauge.Config config) {
        return new DefaultDoubleGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public IntegerAccumulator createIntegerAccumulator(@NonNull final IntegerAccumulator.Config config) {
        return new DefaultIntegerAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public IntegerGauge createIntegerGauge(@NonNull final IntegerGauge.Config config) {
        return new DefaultIntegerGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public LongAccumulator createLongAccumulator(@NonNull final LongAccumulator.Config config) {
        return new DefaultLongAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public LongGauge createLongGauge(@NonNull final LongGauge.Config config) {
        return new DefaultLongGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public <T> FunctionGauge<T> createFunctionGauge(@NonNull final FunctionGauge.Config<T> config) {
        return new DefaultFunctionGauge<>(config);
    }
}
