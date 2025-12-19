// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.FunctionGauge;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Supplier;

/**
 * Platform-implementation of {@link FunctionGauge}
 *
 * @param <T> the type of the contained value
 */
public class DefaultFunctionGauge<T> extends AbstractMetric implements FunctionGauge<T> {

    private final DataType dataType;
    private final Supplier<T> supplier;

    /**
     * Constructs a new DefaultFunctionGauge with the given configuration.
     * @param config the configuration for this function gauge
     */
    public DefaultFunctionGauge(@NonNull final FunctionGauge.Config<T> config) {
        super(config);
        this.dataType = MetricConfig.mapDataType(config.getType());
        this.supplier = config.getSupplier();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public DataType getDataType() {
        return dataType;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<SnapshotEntry> takeSnapshot() {
        return List.of(new SnapshotEntry(VALUE, get()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T get() {
        return supplier.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("value", supplier.get())
                .toString();
    }
}
