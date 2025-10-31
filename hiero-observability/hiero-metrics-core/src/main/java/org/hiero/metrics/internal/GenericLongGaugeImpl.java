// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.LongValueDataPointSnapshotImpl;

public final class GenericLongGaugeImpl<T>
        extends AbstractStatefulMetric<Supplier<T>, GaugeDataPoint<T>, LongValueDataPointSnapshotImpl>
        implements GenericGauge<T> {

    private final ToLongFunction<T> valueConverter;

    public GenericLongGaugeImpl(GenericGauge.Builder<T> builder) {
        super(builder);
        this.valueConverter = builder.getValueConverter().getLongValueConverter();
    }

    @Override
    protected void reset(GaugeDataPoint<T> dataPoint) {
        dataPoint.reset();
    }

    @Override
    protected LongValueDataPointSnapshotImpl createDataPointSnapshot(
            GaugeDataPoint<T> datapoint, LabelValues dynamicLabelValues) {
        return new LongValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<GaugeDataPoint<T>, LongValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder
                .snapshot()
                .set(valueConverter.applyAsLong(dataPointHolder.dataPoint().get()));
    }
}
