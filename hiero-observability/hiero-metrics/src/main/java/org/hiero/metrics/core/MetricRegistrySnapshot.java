// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * A snapshot of all metrics in a {@link MetricRegistry} at a specific point in time,
 * allowing iteration over {@link MetricSnapshot}s sorted by metric name.
 * <p>
 * Registering or removing metrics only marks this snapshot as changed. The list of metric snapshots
 * is rebuilt from the registered metrics by the next {@link #update()}, so metrics registered or removed
 * after an update are iterated only after the next update. Every iteration reads a fixed list of metric snapshots,
 * which later updates do not change, but later updates do overwrite the values of the snapshots in it.
 */
public final class MetricRegistrySnapshot implements Iterable<MetricSnapshot> {

    private static final Comparator<MetricSnapshot> BY_NAME = Comparator.comparing(MetricSnapshot::name);

    private final Collection<Metric<?>> metrics;

    // marked by any thread after metrics are registered or removed, rebuilt and updated by exports
    private final SnapshotList<MetricSnapshot> snapshots;

    private volatile long timestamp = System.currentTimeMillis();

    /**
     * Creates an empty registry snapshot.
     */
    public MetricRegistrySnapshot() {
        this(List.of());
    }

    /**
     * Creates a registry snapshot of the given metrics, which is rebuilt by the first {@link #update()}.
     * Package private, because it is used by the {@link MetricRegistry} with a live view of its metrics.
     */
    MetricRegistrySnapshot(@NonNull Collection<Metric<?>> metrics) {
        this.metrics = metrics;
        this.snapshots = new SnapshotList<>(BY_NAME, this::collectSnapshots, MetricSnapshot::update);
    }

    @NonNull
    @Override
    public Iterator<MetricSnapshot> iterator() {
        return snapshots.iterator();
    }

    /**
     * The point in time, in milliseconds since the Unix epoch, at which this snapshot was last
     * {@link #update() updated}. Exporters can use this as a single, consistent timestamp for all
     * measurements collected in the same snapshot.
     *
     * @return the snapshot timestamp in epoch milliseconds
     */
    public long timestamp() {
        return timestamp;
    }

    /**
     * Rebuilds the list of metric snapshots if metrics were registered or removed since the last update,
     * and updates all metric snapshots with the latest measurement values.
     * A rebuilt list replaces the iterated one only after its values are updated.
     * Package private method, because it is called by the {@link MetricRegistry} each time exporter collects metrics snapshots.
     *
     * @return this registry snapshot
     */
    @NonNull
    synchronized MetricRegistrySnapshot update() {
        timestamp = System.currentTimeMillis();
        snapshots.update();
        return this;
    }

    /**
     * Collects the snapshots of all registered metrics. Called only by the {@link SnapshotList} rebuild
     * during {@link #update()}.
     *
     * @return the snapshots in iteration order of the metrics
     */
    private List<MetricSnapshot> collectSnapshots() {
        final List<MetricSnapshot> collected = new ArrayList<>(metrics.size());
        for (Metric<?> metric : metrics) {
            collected.add(metric.snapshot());
        }
        return collected;
    }

    /**
     * Marks metrics as changed, so the next {@link #update()} rebuilds the list of metric snapshots.
     * Must be called after the change is visible in the metrics collection.
     * Package private method, because it is called by the {@link MetricRegistry} internally after registering
     * or removing a metric.
     */
    void markChanged() {
        snapshots.markChanged();
    }
}
