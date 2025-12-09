// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class DoubleGaugeImpl
        extends AbstractSettableMetric<DoubleSupplier, DoubleGaugeMeasurement, DoubleValueMeasurementSnapshotImpl>
        implements DoubleGauge {

    private final ToDoubleFunction<DoubleGaugeMeasurement> exportValueSupplier;

    public DoubleGaugeImpl(DoubleGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? DoubleGaugeMeasurement::getAndReset : DoubleGaugeMeasurement::getAsDouble;
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            DoubleGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<DoubleGaugeMeasurement, DoubleValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(exportValueSupplier.applyAsDouble(measurementHolder.measurement()));
    }

    @Override
    protected void reset(DoubleGaugeMeasurement measurement) {
        measurement.reset();
    }
}
