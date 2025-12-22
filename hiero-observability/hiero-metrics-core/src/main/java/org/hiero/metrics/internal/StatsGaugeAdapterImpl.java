// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.MultiValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class StatsGaugeAdapterImpl<D>
        extends AbstractSettableMetric<Supplier<D>, D, MultiValueMeasurementSnapshotImpl>
        implements StatsGaugeAdapter<D> {

    private final String statLabelName;
    private final String[] statLabelValues;
    private final ToNumberFunction<D>[] statExportGetters;
    private final boolean[] isFloatingPointAt;
    private final Consumer<D> reset;

    @SuppressWarnings("unchecked")
    public StatsGaugeAdapterImpl(StatsGaugeAdapter.Builder<D> builder) {
        super(builder);

        statLabelName = builder.getStatLabel();
        statLabelValues = builder.getStatNames().toArray(new String[0]);

        reset = builder.getReset() != null ? builder.getReset() : container -> {}; // no-op reset if no specified
        statExportGetters = builder.getStatExportGetters().toArray(new ToNumberFunction[0]);

        isFloatingPointAt = new boolean[statExportGetters.length];
        for (int i = 0; i < statExportGetters.length; i++) {
            isFloatingPointAt[i] = statExportGetters[i].isFloatingPointFunction();
        }
    }

    @Override
    protected void reset(D measurement) {
        reset.accept(measurement);
    }

    @Override
    protected MultiValueMeasurementSnapshotImpl createMeasurementSnapshot(
            D measurement, LabelValues dynamicLabelValues) {
        return new MultiValueMeasurementSnapshotImpl(
                dynamicLabelValues, statLabelName, statLabelValues, isFloatingPointAt);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<D, MultiValueMeasurementSnapshotImpl> measurementHolder) {
        ToNumberFunction<D> getter;
        for (int i = 0; i < statExportGetters.length; i++) {
            getter = statExportGetters[i];
            if (getter.isFloatingPointFunction()) {
                double value = getter.getToDoubleFunction().applyAsDouble(measurementHolder.measurement());
                measurementHolder.snapshot().setValueAt(i, value);
            } else {
                long value = getter.getToLongFunction().applyAsLong(measurementHolder.measurement());
                measurementHolder.snapshot().setValueAt(i, value);
            }
        }
    }
}
