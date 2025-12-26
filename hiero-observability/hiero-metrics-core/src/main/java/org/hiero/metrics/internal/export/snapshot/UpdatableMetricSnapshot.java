// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.AppendArray;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class UpdatableMetricSnapshot<M> implements MetricSnapshot {

    private final Metric metric;
    private final Consumer<MeasurementHolder<M>> snapshotUpdater;
    private final AppendArray<MeasurementHolder<M>> measurementHolders;

    public UpdatableMetricSnapshot(Metric metric, Consumer<MeasurementHolder<M>> snapshotUpdater) {
        this.metric = metric;
        this.snapshotUpdater = snapshotUpdater;
        this.measurementHolders = new AppendArray<>(metric.dynamicLabelNames().isEmpty() ? 1 : 8);
    }

    public void addMeasurementHolder(MeasurementHolder<M> holder) {
        measurementHolders.add(holder);
    }

    @Override
    @NonNull
    public MetricType type() {
        return metric.type();
    }

    @Override
    @NonNull
    public String name() {
        return metric.name();
    }

    @Override
    @Nullable
    public String unit() {
        return metric.unit();
    }

    @Override
    @Nullable
    public String description() {
        return metric.description();
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
