// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultGenericMultiValueDataPointSnapshot;

public final class DefaultStatsGaugeAdapter<I, D>
        extends AbstractStatefulMetric<I, D, DefaultGenericMultiValueDataPointSnapshot>
        implements StatsGaugeAdapter<I, D> {

    private final String statLabelName;
    private final String[] statLabelValues;
    private final ToDoubleFunction<D>[] statExportGetters;
    private final Consumer<D> reset;

    @SuppressWarnings("unchecked")
    public DefaultStatsGaugeAdapter(StatsGaugeAdapter.Builder<I, D> builder) {
        super(builder);

        statLabelName = builder.getStatLabel();
        statLabelValues = builder.getStatNames().toArray(new String[0]);

        reset = builder.getReset() != null ? builder.getReset() : container -> {}; // no-op reset if no specified
        statExportGetters = builder.getStatExportGetters().toArray(new ToDoubleFunction[0]);
    }

    @Override
    protected void reset(D dataPoint) {
        reset.accept(dataPoint);
    }

    @Override
    protected DefaultGenericMultiValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultGenericMultiValueDataPointSnapshot(dynamicLabelValues, statLabelName, statLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<D, DefaultGenericMultiValueDataPointSnapshot> dataPointHolder) {
        for (int i = 0; i < statExportGetters.length; i++) {
            double value = statExportGetters[i].applyAsDouble(dataPointHolder.dataPoint());
            dataPointHolder.snapshot().updateValueAt(i, value);
        }
    }
}
