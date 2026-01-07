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

public final class DoubleCounterImpl extends AbstractSettableMetric<DoubleSupplier, DoubleCounterMeasurement>
        implements DoubleCounter {

    public DoubleCounterImpl(DoubleCounter.Builder builder) {
        super(builder, DoubleAdderCounterMeasurement::new);
    }

    @Override
    protected MeasurementSnapshot createSnapshot(DoubleCounterMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateSnapshot(DoubleCounterMeasurement measurement, MeasurementSnapshot snapshot) {
        ((DoubleValueMeasurementSnapshotImpl) snapshot).set(cast(measurement).get());
    }

    @Override
    protected void reset(DoubleCounterMeasurement measurement) {
        cast(measurement).reset();
    }

    private DoubleAdderCounterMeasurement cast(DoubleCounterMeasurement measurement) {
        return (DoubleAdderCounterMeasurement) measurement;
    }
}
