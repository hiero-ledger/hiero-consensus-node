// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.LongCounterMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.LongAdderCounterMeasurement;

public final class LongCounterImpl extends AbstractSettableMetric<LongSupplier, LongCounterMeasurement>
        implements LongCounter {

    public LongCounterImpl(LongCounter.Builder builder) {
        super(builder, LongAdderCounterMeasurement::new);
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createSnapshot(
            LongCounterMeasurement measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateSnapshot(LongCounterMeasurement measurement, MeasurementSnapshot snapshot) {
        ((LongValueMeasurementSnapshotImpl) snapshot).set(cast(measurement).get());
    }

    @Override
    protected void reset(LongCounterMeasurement measurement) {
        cast(measurement).reset();
    }

    private LongAdderCounterMeasurement cast(LongCounterMeasurement measurement) {
        return (LongAdderCounterMeasurement) measurement;
    }
}
