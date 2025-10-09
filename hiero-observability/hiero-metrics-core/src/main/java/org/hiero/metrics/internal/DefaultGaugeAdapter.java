// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultSingleValueDataPointSnapshot;

public final class DefaultGaugeAdapter<I, D> extends AbstractStatefulMetric<I, D, DefaultSingleValueDataPointSnapshot>
        implements GaugeAdapter<I, D> {

    private final Function<D, Number> exportGetter;
    private final Consumer<D> reset;

    public DefaultGaugeAdapter(GaugeAdapter.Builder<I, D> builder) {
        super(builder);

        exportGetter = builder.getExportGetter();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected DefaultSingleValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultSingleValueDataPointSnapshot(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(DataPointHolder<D, DefaultSingleValueDataPointSnapshot> dataPointHolder) {
        Number value = exportGetter.apply(dataPointHolder.dataPoint());
        double doubleValue = value != null ? value.doubleValue() : Double.NaN;
        dataPointHolder.snapshot().update(doubleValue);
    }

    @Override
    protected void reset(D dataPoint) {
        reset.accept(dataPoint);
    }
}
