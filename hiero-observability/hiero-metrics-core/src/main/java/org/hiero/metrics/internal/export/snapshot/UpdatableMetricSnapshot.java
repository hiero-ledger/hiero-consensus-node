// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.LabelValues;

public final class UpdatableMetricSnapshot<M> implements MetricSnapshot {

    private record MeasurementAndSnapshot<M>(M measurement, MeasurementSnapshot snapshot) {}

    private final AbstractMetric<M> metric;
    private final ConcurrentLinkedQueue<MeasurementAndSnapshot<M>> measurementAndSnapshots;

    public UpdatableMetricSnapshot(AbstractMetric<M> metric) {
        this.metric = metric;
        this.measurementAndSnapshots = new ConcurrentLinkedQueue<>();
    }

    public void addMeasurement(M measurement, LabelValues labelValues) {
        MeasurementSnapshot snapshot = metric.createSnapshot(measurement, labelValues);
        measurementAndSnapshots.add(new MeasurementAndSnapshot<>(measurement, snapshot));
    }

    public void update() {
        measurementAndSnapshots.forEach(this::updateMeasurementSnapshot);
    }

    private void updateMeasurementSnapshot(MeasurementAndSnapshot<M> measurementAndSnapshot) {
        metric.updateSnapshot(measurementAndSnapshot.measurement(), measurementAndSnapshot.snapshot());
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
