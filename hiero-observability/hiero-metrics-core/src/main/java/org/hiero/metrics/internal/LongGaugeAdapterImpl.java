// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class LongGaugeAdapterImpl<D>
        extends AbstractSettableMetric<Supplier<D>, D, LongValueMeasurementSnapshotImpl> implements GaugeAdapter<D> {

    private final ToLongFunction<D> exportGetter;
    private final Consumer<D> reset;

    public LongGaugeAdapterImpl(GaugeAdapter.Builder<D> builder) {
        super(builder);

        exportGetter = builder.getExportGetter().getToLongFunction();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            D measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(MeasurementHolder<D, LongValueMeasurementSnapshotImpl> measurementHolder) {
        measurementHolder.snapshot().set(exportGetter.applyAsLong(measurementHolder.measurement()));
    }

    @Override
    protected void reset(D measurement) {
        reset.accept(measurement);
    }
}
