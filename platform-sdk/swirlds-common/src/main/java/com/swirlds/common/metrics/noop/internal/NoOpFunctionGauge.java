// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op implementation of a function gauge.
 *
 * @param <T>
 * 		the type of the function gauge
 */
public class NoOpFunctionGauge<T> extends AbstractNoOpMetric implements FunctionGauge<T> {

    private final T value;

    public NoOpFunctionGauge(final @NonNull MetricConfig<?, ?> config, final @NonNull T value) {
        super(config);
        this.value = value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public T get() {
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public DataType getDataType() {
        return DataType.INT;
    }
}
