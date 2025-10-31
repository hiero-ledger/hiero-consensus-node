// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.core.ToLongOrDoubleFunction;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.MultiValueDataPointSnapshotImpl;

public final class StatsGaugeAdapterImpl<I, D> extends AbstractStatefulMetric<I, D, MultiValueDataPointSnapshotImpl>
        implements StatsGaugeAdapter<I, D> {

    private final String statLabelName;
    private final String[] statLabelValues;
    private final ToLongOrDoubleFunction<D>[] statExportGetters;
    private final boolean[] isFloatingPointAt;
    private final Consumer<D> reset;

    @SuppressWarnings("unchecked")
    public StatsGaugeAdapterImpl(StatsGaugeAdapter.Builder<I, D> builder) {
        super(builder);

        statLabelName = builder.getStatLabel();
        statLabelValues = builder.getStatNames().toArray(new String[0]);

        reset = builder.getReset() != null ? builder.getReset() : container -> {}; // no-op reset if no specified
        statExportGetters = builder.getStatExportGetters().toArray(new ToLongOrDoubleFunction[0]);

        isFloatingPointAt = new boolean[statExportGetters.length];
        for (int i = 0; i < statExportGetters.length; i++) {
            isFloatingPointAt[i] = statExportGetters[i].isToDoubleFunction();
        }
    }

    @Override
    protected void reset(D dataPoint) {
        reset.accept(dataPoint);
    }

    @Override
    protected MultiValueDataPointSnapshotImpl createDataPointSnapshot(D datapoint, LabelValues dynamicLabelValues) {
        return new MultiValueDataPointSnapshotImpl(
                dynamicLabelValues, statLabelName, statLabelValues, isFloatingPointAt);
    }

    @Override
    protected void updateDatapointSnapshot(DataPointHolder<D, MultiValueDataPointSnapshotImpl> dataPointHolder) {
        ToLongOrDoubleFunction<D> getter;
        for (int i = 0; i < statExportGetters.length; i++) {
            getter = statExportGetters[i];
            if (getter.isToDoubleFunction()) {
                double value = getter.getDoubleValueConverter().applyAsDouble(dataPointHolder.dataPoint());
                dataPointHolder.snapshot().setValueAt(i, value);
            } else {
                long value = getter.getLongValueConverter().applyAsLong(dataPointHolder.dataPoint());
                dataPointHolder.snapshot().setValueAt(i, value);
            }
        }
    }
}
