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
import org.hiero.metrics.internal.measurement.MeasurementAndSnapshot;

public final class UpdatableMetricSnapshot<M> implements MetricSnapshot {

    private final Metric metric;
    private final Consumer<MeasurementAndSnapshot<M>> snapshotUpdater;
    private final AppendArray<MeasurementAndSnapshot<M>> measurementAndSnapshots;

    public UpdatableMetricSnapshot(Metric metric, Consumer<MeasurementAndSnapshot<M>> snapshotUpdater) {
        this.metric = metric;
        this.snapshotUpdater = snapshotUpdater;
        this.measurementAndSnapshots =
                new AppendArray<>(metric.dynamicLabelNames().isEmpty() ? 1 : 8);
    }

    public void addMeasurementAndSnapshot(MeasurementAndSnapshot<M> measurementAndSnapshot) {
        measurementAndSnapshots.add(measurementAndSnapshot);
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
        return measurementAndSnapshots.size();
    }

    @NonNull
    @Override
    public MeasurementSnapshot get(int index) {
        return measurementAndSnapshots.get(index).snapshot();
    }

    public void update() {
        int size = measurementAndSnapshots.readyToRead();
        for (int i = 0; i < size; i++) {
            snapshotUpdater.accept(measurementAndSnapshots.get(i));
        }
    }
}
