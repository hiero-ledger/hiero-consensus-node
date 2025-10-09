// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.AppendArray;
import org.hiero.metrics.internal.datapoint.DataPointHolder;

public final class UpdatableMetricSnapshot<D, S extends DataPointSnapshot> implements MetricSnapshot {

    private final Metric metric;
    private final Consumer<DataPointHolder<D, S>> snapshotUpdater;
    private final AppendArray<DataPointHolder<D, S>> dataPointHolders;

    public UpdatableMetricSnapshot(Metric metric, Consumer<DataPointHolder<D, S>> snapshotUpdater) {
        this.metric = metric;
        this.snapshotUpdater = snapshotUpdater;
        this.dataPointHolders = new AppendArray<>(metric.dynamicLabelNames().isEmpty() ? 1 : 8);
    }

    public void addDataPointHolder(DataPointHolder<D, S> dataPoint) {
        dataPointHolders.add(dataPoint);
    }

    @NonNull
    @Override
    public MetricMetadata metadata() {
        return metric.metadata();
    }

    @NonNull
    @Override
    public List<Label> constantLabels() {
        return metric.constantLabels();
    }

    @NonNull
    @Override
    public List<String> dynamicLabelNames() {
        return metric.dynamicLabelNames();
    }

    @Override
    public int size() {
        return dataPointHolders.size();
    }

    @NonNull
    @Override
    public DataPointSnapshot get(int index) {
        return dataPointHolders.get(index).snapshot();
    }

    public void updateSnapshot() {
        dataPointHolders.readyToRead(snapshotUpdater);
    }
}
