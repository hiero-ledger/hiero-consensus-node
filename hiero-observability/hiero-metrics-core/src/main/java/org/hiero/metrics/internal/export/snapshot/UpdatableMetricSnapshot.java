// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;

public final class UpdatableMetricSnapshot<M> implements MetricSnapshot {

    private final Metric metric;
    private final Consumer<MeasurementAndSnapshot<M>> snapshotUpdater;
    private final ConcurrentLinkedQueue<MeasurementAndSnapshot<M>> measurementAndSnapshots;

    public UpdatableMetricSnapshot(Metric metric, Consumer<MeasurementAndSnapshot<M>> snapshotUpdater) {
        this.metric = metric;
        this.snapshotUpdater = snapshotUpdater;
        this.measurementAndSnapshots = new ConcurrentLinkedQueue<>();
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

    @NonNull
    @Override
    public Iterator<MeasurementSnapshot> iterator() {
        return measurementAndSnapshots.stream()
                .map(MeasurementAndSnapshot::snapshot)
                .iterator();
    }

    public void update() {
        measurementAndSnapshots.forEach(snapshotUpdater);
    }
}
