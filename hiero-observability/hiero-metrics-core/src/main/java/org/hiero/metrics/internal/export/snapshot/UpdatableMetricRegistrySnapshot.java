// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.hiero.metrics.internal.core.AppendArray;

public final class UpdatableMetricRegistrySnapshot implements MetricsCollectionSnapshot {

    private final AppendArray<UpdatableMetricSnapshot<?>> snapshots = new AppendArray<>(64);

    @NonNull
    public synchronized UpdatableMetricRegistrySnapshot update() {
        int size = snapshots.readyToRead();
        for (int i = 0; i < size; i++) {
            snapshots.get(i).update();
        }
        return this;
    }

    public void addMetricSnapshot(UpdatableMetricSnapshot<?> metricSnapshot) {
        snapshots.add(metricSnapshot);
    }

    @Override
    public int size() {
        return snapshots.size();
    }

    @NonNull
    @Override
    public MetricSnapshot get(int index) {
        return snapshots.get(index);
    }
}
