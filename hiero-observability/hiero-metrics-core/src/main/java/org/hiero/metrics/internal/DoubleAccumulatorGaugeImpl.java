// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.DoubleAccumulatorGauge;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.DoubleAccumulatorGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.DoubleAccumulatorGaugeMeasurementImpl;

public class DoubleAccumulatorGaugeImpl
        extends AbstractSettableMetric<DoubleSupplier, DoubleAccumulatorGaugeMeasurement>
        implements DoubleAccumulatorGauge {

    private final ToDoubleFunction<DoubleAccumulatorGaugeMeasurementImpl> exportValueSupplier;

    public DoubleAccumulatorGaugeImpl(DoubleAccumulatorGauge.Builder builder) {
        super(builder, init -> new DoubleAccumulatorGaugeMeasurementImpl(builder.getOperator(), init));

        exportValueSupplier = builder.isResetOnExport()
                ? DoubleAccumulatorGaugeMeasurementImpl::getAndReset
                : DoubleAccumulatorGaugeMeasurementImpl::get;
    }

    @Override
    protected MeasurementSnapshot createSnapshot(
            DoubleAccumulatorGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateSnapshot(DoubleAccumulatorGaugeMeasurement measurement, MeasurementSnapshot snapshot) {
        ((DoubleValueMeasurementSnapshotImpl) snapshot).set(exportValueSupplier.applyAsDouble(cast(measurement)));
    }

    @Override
    protected void reset(DoubleAccumulatorGaugeMeasurement measurement) {
        cast(measurement).reset();
    }

    private DoubleAccumulatorGaugeMeasurementImpl cast(DoubleAccumulatorGaugeMeasurement measurement) {
        return (DoubleAccumulatorGaugeMeasurementImpl) measurement;
    }
}
