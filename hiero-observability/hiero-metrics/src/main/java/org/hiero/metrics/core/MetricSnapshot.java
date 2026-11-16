// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A snapshot of a {@link Metric} at a point in time, allowing iteration over {@link MeasurementSnapshot}s
 * sorted by their dynamic label values.
 * <p>
 * Only an exported metric has a snapshot, which keeps the list of measurement snapshots, so a metric that is not
 * exported keeps nothing for export. Adding or removing measurements only marks this snapshot as changed.
 * The list of measurement snapshots is rebuilt from the {@link Measurements} of the metric by the next
 * {@link #update()}, so measurements added or removed after an update are iterated only after the next update.
 * Every iteration reads a fixed list of measurement snapshots, which later updates do not change, but later updates
 * do overwrite the values of the snapshots in it.
 */
public final class MetricSnapshot implements MetricInfo, Iterable<MeasurementSnapshot> {

    private static final Comparator<MeasurementSnapshot> BY_LABEL_VALUES =
            (first, second) -> LabelValues.compare(first.getDynamicLabelValues(), second.getDynamicLabelValues());

    private final Metric<?> metric;

    // marked by any thread after measurements are added or removed, rebuilt and updated by exports
    private final SnapshotList<MeasurementSnapshot> measurementSnapshots;

    /**
     * Package private, because it is created only by the {@link Metric} constructor of an exported metric.
     * Reads nothing from the metric, so it does not depend on the order of the constructor assignments:
     * the metadata and measurements of the metric are read only by exports.
     *
     * @param metric the metric of this snapshot
     */
    MetricSnapshot(@NonNull Metric<?> metric) {
        this.metric = Objects.requireNonNull(metric, "metric must not be null");
        this.measurementSnapshots =
                new SnapshotList<>(BY_LABEL_VALUES, metric::collectMeasurementSnapshots, MeasurementSnapshot::update);
    }

    /**
     * Rebuilds the list of measurement snapshots if measurements were added or removed since the last update,
     * and updates all measurement snapshots with the latest measurement values.
     * A rebuilt list replaces the iterated one only after its values are updated.
     * <p>
     * This method is package private, because it is called by the {@link MetricRegistry} internally each time collecting metrics snapshots.
     *
     * @return {@code true} if the list of measurement snapshots was rebuilt, {@code false} if nothing changed
     */
    boolean update() {
        return measurementSnapshots.update();
    }

    /**
     * Marks the list of measurement snapshots as changed, so the next {@link #update()} rebuilds it.
     * Package private, because it is called only by the {@link Metric} after its measurements are added or removed.
     */
    void markChanged() {
        measurementSnapshots.markChanged();
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
