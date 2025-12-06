// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.datapoint.DoubleGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DoubleValueDataPointSnapshotImpl;

public final class DoubleGaugeImpl
        extends AbstractStatefulMetric<DoubleSupplier, DoubleGaugeDataPoint, DoubleValueDataPointSnapshotImpl>
        implements DoubleGauge {

    private final ToDoubleFunction<DoubleGaugeDataPoint> exportValueSupplier;

    public DoubleGaugeImpl(DoubleGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? DoubleGaugeDataPoint::getAndReset : DoubleGaugeDataPoint::getAsDouble;
    }

    @Override
    protected DoubleValueDataPointSnapshotImpl createDataPointSnapshot(
            DoubleGaugeDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new DoubleValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<DoubleGaugeDataPoint, DoubleValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(exportValueSupplier.applyAsDouble(dataPointHolder.dataPoint()));
    }

    @Override
    protected void reset(DoubleGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
