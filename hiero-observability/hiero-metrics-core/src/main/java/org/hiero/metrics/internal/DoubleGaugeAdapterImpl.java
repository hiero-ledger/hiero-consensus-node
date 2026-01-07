// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;

public final class DoubleGaugeAdapterImpl<M> extends AbstractSettableMetric<Supplier<M>, M, M>
        implements GaugeAdapter<M> {

    private final ToDoubleFunction<M> exportGetter;
    private final Consumer<M> reset;

    public DoubleGaugeAdapterImpl(GaugeAdapter.Builder<M> builder) {
        super(builder, Supplier::get);

        exportGetter = builder.getExportGetter().getToDoubleFunction();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected DoubleValueMeasurementSnapshotImpl createMeasurementSnapshot(
            M measurement, LabelValues dynamicLabelValues) {
        return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(M measurement, MeasurementSnapshot snapshot) {
        ((DoubleValueMeasurementSnapshotImpl) snapshot).set(exportGetter.applyAsDouble(measurement));
    }

    @Override
    protected void reset(M measurement) {
        reset.accept(measurement);
    }
}
