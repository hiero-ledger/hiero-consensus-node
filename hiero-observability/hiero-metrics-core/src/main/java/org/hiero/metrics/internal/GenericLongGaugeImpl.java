// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.measurement.GaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class GenericLongGaugeImpl<T>
        extends AbstractSettableMetric<Supplier<T>, GaugeMeasurement<T>, LongValueMeasurementSnapshotImpl>
        implements GenericGauge<T> {

    private final ToLongFunction<T> valueConverter;

    public GenericLongGaugeImpl(GenericGauge.Builder<T> builder) {
        super(builder);
        this.valueConverter = builder.getValueConverter().getToLongFunction();
    }

    @Override
    protected void reset(GaugeMeasurement<T> measurement) {
        measurement.reset();
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            GaugeMeasurement<T> measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<GaugeMeasurement<T>, LongValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder
                .snapshot()
                .set(valueConverter.applyAsLong(measurementHolder.measurement().get()));
    }
}
