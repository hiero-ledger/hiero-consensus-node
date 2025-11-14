// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DoubleValueDataPointSnapshotImpl;

public final class DoubleGaugeAdapterImpl<I, D> extends AbstractStatefulMetric<I, D, DoubleValueDataPointSnapshotImpl>
        implements GaugeAdapter<I, D> {

    private final ToDoubleFunction<D> exportGetter;
    private final Consumer<D> reset;

    public DoubleGaugeAdapterImpl(GaugeAdapter.Builder<I, D> builder) {
        super(builder);

        exportGetter = builder.getValueConverter().getToDoubleFunction();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected DoubleValueDataPointSnapshotImpl createDataPointSnapshot(D datapoint, LabelValues dynamicLabelValues) {
        return new DoubleValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(DataPointHolder<D, DoubleValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(exportGetter.applyAsDouble(dataPointHolder.dataPoint()));
    }

    @Override
    protected void reset(D dataPoint) {
        reset.accept(dataPoint);
    }
}
