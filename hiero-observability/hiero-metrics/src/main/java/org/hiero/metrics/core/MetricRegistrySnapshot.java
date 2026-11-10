// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A snapshot of all metrics in a {@link MetricRegistry} at a specific point in time,
 * allowing iteration over {@link MetricSnapshot}s with consistent repetitive order (new metrics are appended).
 * <p>
 * Every iteration reads a fixed list: metrics added or removed after the iteration started
 * are not added to or removed from it.
 */
public final class MetricRegistrySnapshot implements Iterable<MetricSnapshot> {

    private final CopyOnWriteArrayList<MetricSnapshot> snapshots;

    private volatile long timestamp = System.currentTimeMillis();

    /**
     * Creates an empty registry snapshot.
     */
    public MetricRegistrySnapshot() {
        this(List.of());
    }

    /**
     * Creates a registry snapshot with the given initial metric snapshots, copying them into the backing list at once.
     * Package private, because it is used by the {@link MetricRegistry} to register metrics in one pass.
     */
    MetricRegistrySnapshot(@NonNull List<MetricSnapshot> initial) {
        snapshots = new CopyOnWriteArrayList<>(initial);
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
     * Update all metric snapshots in this registry snapshot with the latest measurement values.
     * Package private method, because it is called by the {@link MetricRegistry} each time exporter collects metrics snapshots.
     *
     * @return this registry snapshot
     */
    @NonNull
    synchronized MetricRegistrySnapshot update() {
        timestamp = System.currentTimeMillis();
        snapshots.forEach(MetricSnapshot::update);
        return this;
    }

    /**
     * Internal method to add a metric snapshot to this registry snapshot.
     * Package private method, because it is called by the {@link MetricRegistry} internally during metric registration.
     */
    void addMetricSnapshot(MetricSnapshot metricSnapshot) {
        snapshots.add(metricSnapshot);
    }

    /**
     * Internal method to remove the given metric snapshot from this registry snapshot.
     * {@link MetricSnapshot} does not override {@code equals}, so it is removed by identity.
     * Package private method, because it is called by the {@link MetricRegistry} internally during metric removal.
     */
    void removeMetricSnapshot(MetricSnapshot metricSnapshot) {
        snapshots.remove(metricSnapshot);
    }
}
