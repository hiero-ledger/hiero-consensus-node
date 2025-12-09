// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.measurement.DoubleCounterMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class DoubleCounterImpl
        extends AbstractSettableMetric<DoubleSupplier, DoubleCounterMeasurement, DoubleValueMeasurementSnapshotImpl>
        implements DoubleCounter {

    public DoubleCounterImpl(DoubleCounter.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(DoubleCounterMeasurement measurement) {
        measurement.reset();
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            DoubleCounterMeasurement measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<DoubleCounterMeasurement, DoubleValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(measurementHolder.measurement().getAsDouble());
    }
}
