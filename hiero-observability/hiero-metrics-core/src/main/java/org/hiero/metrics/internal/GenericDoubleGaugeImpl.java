// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.measurement.GaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class GenericDoubleGaugeImpl<T>
        extends AbstractSettableMetric<Supplier<T>, GaugeMeasurement<T>, DoubleValueMeasurementSnapshotImpl>
        implements GenericGauge<T> {

    private final ToDoubleFunction<T> valueConverter;

    public GenericDoubleGaugeImpl(GenericGauge.Builder<T> builder) {
        super(builder);
        this.valueConverter = builder.getValueConverter().getToDoubleFunction();
    }

    @Override
    protected void reset(GaugeMeasurement<T> measurement) {
        measurement.reset();
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            GaugeMeasurement<T> measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<GaugeMeasurement<T>, DoubleValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder
                .snapshot()
                .set(valueConverter.applyAsDouble(
                        measurementHolder.measurement().get()));
    }
}
