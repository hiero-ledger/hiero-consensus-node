// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.AtomicLongGaugeMeasurement;

public final class LongGaugeImpl
        extends AbstractSettableMetric<LongSupplier, LongGaugeMeasurement, AtomicLongGaugeMeasurement>
        implements LongGauge {

    public LongGaugeImpl(LongGauge.Builder builder) {
        super(builder, AtomicLongGaugeMeasurement::new);
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            AtomicLongGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(AtomicLongGaugeMeasurement measurement, MeasurementSnapshot snapshot) {
        ((LongValueMeasurementSnapshotImpl) snapshot).set(measurement.get());
    }

    @Override
    protected void reset(AtomicLongGaugeMeasurement measurement) {
        measurement.reset();
    }
}
