// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A snapshot of all metrics in a {@link MetricRegistry} at a specific point in time, allowing iteration over {@link MetricSnapshot}s.
 */
public final class MetricRegistrySnapshot implements Iterable<MetricSnapshot> {

    private final ConcurrentLinkedQueue<MetricSnapshot> snapshots = new ConcurrentLinkedQueue<>();

    @NonNull
    @Override
    public Iterator<MetricSnapshot> iterator() {
        return snapshots.stream().iterator();
    }

    /**
     * Update all metric snapshots in this registry snapshot with the latest measurement values.
     * Package private method, because it is called by the {@link MetricRegistry} each time exporter collects metrics snapshots.
     *
     * @return this registry snapshot
     */
    @NonNull
    synchronized MetricRegistrySnapshot update() {
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
}
