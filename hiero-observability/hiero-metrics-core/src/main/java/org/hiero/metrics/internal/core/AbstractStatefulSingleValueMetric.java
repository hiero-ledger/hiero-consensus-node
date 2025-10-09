// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultSingleValueDataPointSnapshot;

/**
 * Base class for all stateful metrics with single double value data points
 * using {@link DefaultSingleValueDataPointSnapshot} as snapshot implementation.
 *
 * @param <I> the type of the initializer used to create new data points
 * @param <D> the type of the data point, must implement DoubleSupplier
 */
public abstract class AbstractStatefulSingleValueMetric<I, D extends DoubleSupplier>
        extends AbstractStatefulMetric<I, D, DefaultSingleValueDataPointSnapshot> {

    protected AbstractStatefulSingleValueMetric(StatefulMetric.Builder<I, D, ?, ?> builder) {
        super(builder);
    }

    @Override
    protected DefaultSingleValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultSingleValueDataPointSnapshot(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(DataPointHolder<D, DefaultSingleValueDataPointSnapshot> dataPointHolder) {
        dataPointHolder.snapshot().update(dataPointHolder.dataPoint().getAsDouble());
    }
}
