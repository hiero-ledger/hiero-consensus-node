// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.datapoint.LongGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.OneValueDataPointSnapshotImpl;

public final class LongGaugeImpl
        extends AbstractStatefulMetric<LongSupplier, LongGaugeDataPoint, OneValueDataPointSnapshotImpl>
        implements LongGauge {

    private final ToLongFunction<LongGaugeDataPoint> exportValueSupplier;

    public LongGaugeImpl(LongGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? LongGaugeDataPoint::getAndReset : LongGaugeDataPoint::getAsLong;
    }

    @Override
    protected OneValueDataPointSnapshotImpl createDataPointSnapshot(
            LongGaugeDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new OneValueDataPointSnapshotImpl(dynamicLabelValues, false);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<LongGaugeDataPoint, OneValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(exportValueSupplier.applyAsLong(dataPointHolder.dataPoint()));
    }

    @Override
    protected void reset(LongGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
