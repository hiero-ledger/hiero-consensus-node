// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

public final class UpdatableMetricSnapshot<M> implements MetricSnapshot {

    private record MeasurementAndSnapshot<M>(M measurement, MeasurementSnapshot snapshot) {}

    private final Metric metric;
    private final BiFunction<M, LabelValues, MeasurementSnapshot> snapshotCreator;
    private final BiConsumer<M, MeasurementSnapshot> snapshotUpdater;
    private final ConcurrentLinkedQueue<MeasurementAndSnapshot<M>> measurementAndSnapshots;

    public UpdatableMetricSnapshot(
            Metric metric,
            BiFunction<M, LabelValues, MeasurementSnapshot> snapshotCreator,
            BiConsumer<M, MeasurementSnapshot> snapshotUpdater) {
        this.metric = metric;
        this.snapshotCreator = snapshotCreator;
        this.snapshotUpdater = snapshotUpdater;
        this.measurementAndSnapshots = new ConcurrentLinkedQueue<>();
    }

    public void addMeasurement(M measurement, LabelValues labelValues) {
        MeasurementSnapshot snapshot = snapshotCreator.apply(measurement, labelValues);
        measurementAndSnapshots.add(new MeasurementAndSnapshot<>(measurement, snapshot));
    }

    public void update() {
        measurementAndSnapshots.forEach(this::updateMeasurementSnapshot);
    }

    private void updateMeasurementSnapshot(MeasurementAndSnapshot<M> measurementAndSnapshot) {
        snapshotUpdater.accept(measurementAndSnapshot.measurement(), measurementAndSnapshot.snapshot());
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
}
