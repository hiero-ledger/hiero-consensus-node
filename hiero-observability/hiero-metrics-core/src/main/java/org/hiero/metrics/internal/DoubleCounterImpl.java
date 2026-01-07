// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.DoubleCounterMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.DoubleAdderCounterMeasurement;

public final class DoubleCounterImpl
        extends AbstractSettableMetric<DoubleSupplier, DoubleCounterMeasurement, DoubleAdderCounterMeasurement>
        implements DoubleCounter {

    public DoubleCounterImpl(DoubleCounter.Builder builder) {
        super(builder, DoubleAdderCounterMeasurement::new);
    }

    @Override
    protected void reset(DoubleAdderCounterMeasurement measurement) {
        measurement.reset();
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            DoubleAdderCounterMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(DoubleAdderCounterMeasurement measurement, MeasurementSnapshot snapshot) {
        ((DoubleValueMeasurementSnapshotImpl) snapshot).set(measurement.get());
    }
}
