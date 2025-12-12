// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.measurement.BooleanGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class BooleanGaugeImpl
        extends AbstractSettableMetric<BooleanSupplier, BooleanGaugeMeasurement, LongValueMeasurementSnapshotImpl>
        implements BooleanGauge {

    public BooleanGaugeImpl(BooleanGauge.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(BooleanGaugeMeasurement measurement) {
        measurement.reset();
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            BooleanGaugeMeasurement measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<BooleanGaugeMeasurement, LongValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(measurementHolder.measurement().getAsLong());
    }
}
