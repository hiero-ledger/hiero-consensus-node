// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.AppendArray;

public final class UpdatableMetricRegistrySnapshot implements MetricsCollectionSnapshot {

    private Instant createdAt = Instant.now();

    private final AppendArray<UpdatableMetricSnapshot<?, ?>> snapshots = new AppendArray<>(64);

    @NonNull
    public synchronized UpdatableMetricRegistrySnapshot update() {
        createdAt = Instant.now();

        int size = snapshots.readyToRead();
        for (int i = 0; i < size; i++) {
            snapshots.get(i).updateSnapshot();
        }
        return this;
    }

    public void add(AbstractMetric<?, ?> snapshotableMetric) {
        snapshots.add(snapshotableMetric.snapshot());
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

    @NonNull
    @Override
    public Instant createAt() {
        return createdAt;
    }
}
