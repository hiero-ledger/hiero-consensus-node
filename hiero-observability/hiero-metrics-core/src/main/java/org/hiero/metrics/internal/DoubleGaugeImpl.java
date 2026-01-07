// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.AtomicDoubleGaugeMeasurement;

public final class DoubleGaugeImpl extends AbstractSettableMetric<DoubleSupplier, DoubleGaugeMeasurement>
        implements DoubleGauge {

    public DoubleGaugeImpl(DoubleGauge.Builder builder) {
        super(builder, AtomicDoubleGaugeMeasurement::new);
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            DoubleGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(DoubleGaugeMeasurement measurement, MeasurementSnapshot snapshot) {
        ((DoubleValueMeasurementSnapshotImpl) snapshot).set(cast(measurement).get());
    }

    @Override
    protected void reset(DoubleGaugeMeasurement measurement) {
        cast(measurement).reset();
    }

    private AtomicDoubleGaugeMeasurement cast(DoubleGaugeMeasurement measurement) {
        return (AtomicDoubleGaugeMeasurement) measurement;
    }
}
