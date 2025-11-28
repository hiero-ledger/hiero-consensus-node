// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.datapoint.LongGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.LongValueDataPointSnapshotImpl;

public final class LongGaugeImpl
        extends AbstractStatefulMetric<LongSupplier, LongGaugeDataPoint, LongValueDataPointSnapshotImpl>
        implements LongGauge {

    private final ToLongFunction<LongGaugeDataPoint> exportValueSupplier;

    public LongGaugeImpl(LongGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? LongGaugeDataPoint::getAndReset : LongGaugeDataPoint::getAsLong;
    }

    @Override
    protected LongValueDataPointSnapshotImpl createDataPointSnapshot(
            LongGaugeDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new LongValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<LongGaugeDataPoint, LongValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(exportValueSupplier.applyAsLong(dataPointHolder.dataPoint()));
    }

    @Override
    protected void reset(LongGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
