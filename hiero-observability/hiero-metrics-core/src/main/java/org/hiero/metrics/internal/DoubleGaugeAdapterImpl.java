// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class DoubleGaugeAdapterImpl<D>
        extends AbstractSettableMetric<Supplier<D>, D, DoubleValueMeasurementSnapshotImpl> implements GaugeAdapter<D> {

    private final ToDoubleFunction<D> exportGetter;
    private final Consumer<D> reset;

    public DoubleGaugeAdapterImpl(GaugeAdapter.Builder<D> builder) {
        super(builder);

        exportGetter = builder.getExportGetter().getToDoubleFunction();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            D measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<D, DoubleValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(exportGetter.applyAsDouble(measurementHolder.measurement()));
    }

    @Override
    protected void reset(D measurement) {
        reset.accept(measurement);
    }
}
