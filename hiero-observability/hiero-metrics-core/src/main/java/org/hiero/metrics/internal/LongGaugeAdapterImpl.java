// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.OneValueDataPointSnapshotImpl;

public final class LongGaugeAdapterImpl<I, D> extends AbstractStatefulMetric<I, D, OneValueDataPointSnapshotImpl>
        implements GaugeAdapter<I, D> {

    private final ToLongFunction<D> exportGetter;
    private final Consumer<D> reset;

    public LongGaugeAdapterImpl(GaugeAdapter.Builder<I, D> builder) {
        super(builder);

        exportGetter = builder.getValueConverter().getLongValueConverter();
        reset = builder.getReset() != null ? builder.getReset() : container -> {};
    }

    @Override
    protected OneValueDataPointSnapshotImpl createDataPointSnapshot(D datapoint, LabelValues dynamicLabelValues) {
        return new OneValueDataPointSnapshotImpl(dynamicLabelValues, false);
    }

    @Override
    protected void updateDatapointSnapshot(DataPointHolder<D, OneValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(exportGetter.applyAsLong(dataPointHolder.dataPoint()));
    }

    @Override
    protected void reset(D dataPoint) {
        reset.accept(dataPoint);
    }
}
