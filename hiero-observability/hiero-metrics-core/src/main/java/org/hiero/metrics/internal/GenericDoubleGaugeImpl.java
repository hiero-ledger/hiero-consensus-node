// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.OneValueDataPointSnapshotImpl;

public final class GenericDoubleGaugeImpl<T>
        extends AbstractStatefulMetric<Supplier<T>, GaugeDataPoint<T>, OneValueDataPointSnapshotImpl>
        implements GenericGauge<T> {

    private final ToDoubleFunction<T> valueConverter;

    public GenericDoubleGaugeImpl(GenericGauge.Builder<T> builder) {
        super(builder);
        this.valueConverter = builder.getValueConverter().getDoubleValueConverter();
    }

    @Override
    protected void reset(GaugeDataPoint<T> dataPoint) {
        dataPoint.reset();
    }

    @Override
    protected OneValueDataPointSnapshotImpl createDataPointSnapshot(
            GaugeDataPoint<T> datapoint, LabelValues dynamicLabelValues) {
        return new OneValueDataPointSnapshotImpl(dynamicLabelValues, true);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<GaugeDataPoint<T>, OneValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder
                .snapshot()
                .set(valueConverter.applyAsDouble(dataPointHolder.dataPoint().get()));
    }
}
