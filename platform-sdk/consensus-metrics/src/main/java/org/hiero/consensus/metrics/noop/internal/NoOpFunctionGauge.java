// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.noop.internal;

import com.swirlds.metrics.api.MetricConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.metrics.FunctionGauge;

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
