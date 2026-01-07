// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;

public final class LongGaugeAdapterImpl<M> extends AbstractSettableMetric<Supplier<M>, M, M>
        implements GaugeAdapter<M> {

    private final ToLongFunction<M> exportGetter;
    private final Consumer<M> reset;

    public LongGaugeAdapterImpl(GaugeAdapter.Builder<M> builder) {
        super(builder, Supplier::get);

        exportGetter = builder.getExportGetter().getToLongFunction();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            M measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(M measurement, MeasurementSnapshot snapshot) {
        ((LongValueMeasurementSnapshotImpl) snapshot).set(exportGetter.applyAsLong(measurement));
    }

    @Override
    protected void reset(M measurement) {
        reset.accept(measurement);
    }
}
