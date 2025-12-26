// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;

public final class LongGaugeImpl extends AbstractSettableMetric<LongSupplier, LongGaugeMeasurement>
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
    protected void updateMeasurementSnapshot(LongGaugeMeasurement measurement, MeasurementSnapshot snapshot) {
        ((LongValueMeasurementSnapshotImpl) snapshot).set(exportValueSupplier.applyAsLong(measurement));
    }

    @Override
    protected void reset(LongGaugeMeasurement measurement) {
        measurement.reset();
    }
}
