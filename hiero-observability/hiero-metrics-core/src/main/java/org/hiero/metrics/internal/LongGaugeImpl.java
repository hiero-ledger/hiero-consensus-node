// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class LongGaugeImpl
        extends AbstractSettableMetric<LongSupplier, LongGaugeMeasurement, LongValueMeasurementSnapshotImpl>
        implements LongGauge {

    private final ToLongFunction<LongGaugeMeasurement> exportValueSupplier;

    public LongGaugeImpl(LongGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? LongGaugeMeasurement::getAndReset : LongGaugeMeasurement::getAsLong;
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            LongGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<LongGaugeMeasurement, LongValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(exportValueSupplier.applyAsLong(measurementHolder.measurement()));
    }

    @Override
    protected void reset(LongGaugeMeasurement measurement) {
        measurement.reset();
    }
}
