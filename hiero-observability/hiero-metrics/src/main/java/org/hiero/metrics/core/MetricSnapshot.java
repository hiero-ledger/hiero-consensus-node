// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;

/**
 * A snapshot of a {@link Metric} at a point in time, allowing iteration over {@link MeasurementSnapshot}s
 * sorted by their dynamic label values.
 * <p>
 * Adding or removing measurements only marks the {@link Measurements} of the metric as changed. The list of
 * measurement snapshots is rebuilt from them by the next {@link #update()}, so measurements added or removed
 * after an update are iterated only after the next update. Every iteration reads a fixed list of measurement
 * snapshots, which later updates do not change, but later updates do overwrite the values of the snapshots in it.
 */
public final class MetricSnapshot implements MetricInfo, Iterable<MeasurementSnapshot> {

    private final Metric<?> metric;
    private final Measurements<?> measurements;

    // replaced by a rebuilt list and never changed in place, so a started iteration is not affected
    private volatile List<MeasurementSnapshot> measurementSnapshots = List.of();

    MetricSnapshot(Metric<?> metric, Measurements<?> measurements) {
        this.metric = metric;
        this.measurements = measurements;
    }

    /**
     * Rebuilds the list of measurement snapshots if measurements were added or removed since the last update,
     * and updates all measurement snapshots with the latest measurement values.
     * A rebuilt list replaces the iterated one only after its values are updated.
     * <p>
     * This method is package private, because it is called by the {@link MetricRegistry} internally each time collecting metrics snapshots.
     */
    void update() {
        final List<MeasurementSnapshot> changed = measurements.snapshotsIfChanged();
        final List<MeasurementSnapshot> current = changed == null ? measurementSnapshots : changed;
        try {
            for (MeasurementSnapshot measurementSnapshot : current) {
                measurementSnapshot.update();
            }
        } finally {
            // published after the values are updated, so an iteration never reads a new snapshot without its value;
            // published also if an update throws, because the measurements are not marked as changed anymore
            if (changed != null) {
                measurementSnapshots = current;
            }
        }
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
