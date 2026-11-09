// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A snapshot of a {@link Metric} at a point in time, allowing iteration over {@link MeasurementSnapshot}s
 * with consistent repetitive order (new measurements are appended).
 * <p>
 * Every iteration reads a fixed list: measurements added or removed after the iteration started
 * are not added to or removed from it.
 */
public final class MetricSnapshot implements MetricInfo, Iterable<MeasurementSnapshot> {

    private final Metric metric;
    private final CopyOnWriteArrayList<MeasurementSnapshot> measurementSnapshots;

    public MetricSnapshot(Metric metric) {
        this.metric = metric;
        this.measurementSnapshots = new CopyOnWriteArrayList<>();
    }

    /**
     * Internal method to add a measurement snapshot to this metric snapshot.
     * Package private method, because it is called by the {@link Metric} internally during measurement snapshot creation.
     */
    void addMeasurementSnapshot(MeasurementSnapshot measurementSnapshot) {
        measurementSnapshots.add(measurementSnapshot);
    }

    /**
     * Internal method to remove the measurement snapshot with the given label values from this metric snapshot.
     * Package private method, because it is called by the {@link Metric} internally during measurement removal.
     */
    void removeMeasurementSnapshot(LabelValues labelValues) {
        measurementSnapshots.removeIf(
                snapshot -> snapshot.getDynamicLabelValues().equals(labelValues));
    }

    /**
     * Update all measurement snapshots in this metric snapshot with the latest measurement values.
     * <p>
     * This method is package private, because it is called by the {@link MetricRegistry} internally each time collecting metrics snapshots.
     */
    void update() {
        measurementSnapshots.forEach(MeasurementSnapshot::update);
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
        return measurementSnapshots.iterator();
    }

    @Override
    public String toString() {
        return "MetricSnapshot{" + "name='"
                + name() + '\'' + ", type="
                + type() + ", unit='"
                + unit() + '\'' + ", description='"
                + description() + '\'' + ", staticLabels="
                + staticLabels() + ", dynamicLabelNames="
                + dynamicLabelNames() + ", measurementSnapshots="
                + measurementSnapshots + '}';
    }
}
