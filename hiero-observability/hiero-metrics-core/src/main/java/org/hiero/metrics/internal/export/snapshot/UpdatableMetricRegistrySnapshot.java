// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

public final class UpdatableMetricRegistrySnapshot implements MetricsCollectionSnapshot {

    private final ConcurrentLinkedQueue<UpdatableMetricSnapshot<?>> snapshots = new ConcurrentLinkedQueue<>();

    @NonNull
    public synchronized MetricsCollectionSnapshot update() {
        snapshots.forEach(UpdatableMetricSnapshot::update);
        return this;
    }

    public void addMetricSnapshot(UpdatableMetricSnapshot<?> metricSnapshot) {
        snapshots.add(metricSnapshot);
    }

    @NonNull
    @Override
    public Iterator<MetricSnapshot> iterator() {
        return snapshots.stream().map(s -> (MetricSnapshot) s).iterator();
    }
}
