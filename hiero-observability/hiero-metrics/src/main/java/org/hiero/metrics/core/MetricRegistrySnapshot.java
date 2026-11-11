// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // set by any thread after metrics are registered or removed, cleared by the update rebuilding the list
    private final AtomicBoolean changed = new AtomicBoolean(true);
    // replaced by a rebuilt list and never changed in place, so a started iteration is not affected
    private volatile List<MetricSnapshot> snapshots = List.of();

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
     * Package private method, because it is called by the {@link MetricRegistry} each time exporter collects metrics snapshots.
     *
     * @return this registry snapshot
     */
    @NonNull
    synchronized MetricRegistrySnapshot update() {
        timestamp = System.currentTimeMillis();
        // cleared before reading the metrics: a change made after clearing marks it again for the next update;
        // read first, so an update without changes does not write the flag
        if (changed.get() && changed.getAndSet(false)) {
            final List<MetricSnapshot> rebuilt = new ArrayList<>(metrics.size());
            for (Metric<?> metric : metrics) {
                rebuilt.add(metric.snapshot());
            }
            snapshots = SnapshotLists.sortKeepingLastOfEqual(rebuilt, BY_NAME);
        }
        for (MetricSnapshot snapshot : snapshots) {
            snapshot.update();
        }
        return this;
    }

    /**
     * Marks metrics as changed, so the next {@link #update()} rebuilds the list of metric snapshots.
     * Must be called after the change is visible in the metrics collection.
     * Package private method, because it is called by the {@link MetricRegistry} internally after registering
     * or removing a metric.
     */
    void markChanged() {
        changed.set(true);
    }
}
