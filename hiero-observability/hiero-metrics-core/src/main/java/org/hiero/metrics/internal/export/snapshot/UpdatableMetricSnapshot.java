// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.AppendArray;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class UpdatableMetricSnapshot<D, S extends MeasurementSnapshot> implements MetricSnapshot {

    private final Metric metric;
    private final Consumer<MeasurementHolder<D, S>> snapshotUpdater;
    private final AppendArray<MeasurementHolder<D, S>> measurementHolders;

    public UpdatableMetricSnapshot(Metric metric, Consumer<MeasurementHolder<D, S>> snapshotUpdater) {
        this.metric = metric;
        this.snapshotUpdater = snapshotUpdater;
        this.measurementHolders = new AppendArray<>(metric.dynamicLabelNames().isEmpty() ? 1 : 8);
    }

    public void addMeasurementHolder(MeasurementHolder<D, S> holder) {
        measurementHolders.add(holder);
    }

    @NonNull
    @Override
    public MetricMetadata metadata() {
        return metric.metadata();
    }

    @NonNull
    @Override
    public List<Label> staticLabels() {
        return metric.staticLabels();
    }

    @NonNull
    @Override
    public List<String> dynamicLabelNames() {
        return metric.dynamicLabelNames();
    }

    @Override
    public int size() {
        return measurementHolders.size();
    }

    @NonNull
    @Override
    public MeasurementSnapshot get(int index) {
        return measurementHolders.get(index).snapshot();
    }

    public void updateSnapshot() {
        int size = measurementHolders.readyToRead();
        for (int i = 0; i < size; i++) {
            snapshotUpdater.accept(measurementHolders.get(i));
        }
    }
}
