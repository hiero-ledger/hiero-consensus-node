// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.core.ArrayAccessor;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.AppendArray;
import org.hiero.metrics.internal.export.SnapshotableMetric;

public final class UpdatableMetricRegistrySnapshot implements ArrayAccessor<MetricSnapshot> {

    private final AppendArray<UpdatableMetricSnapshot<?, ? extends DataPointSnapshot>> snapshots =
            new AppendArray<>(64);

    public void updateSnapshot() {
        snapshots.readyToRead(UpdatableMetricSnapshot::updateSnapshot);
    }

    public void add(SnapshotableMetric<? extends DataPointSnapshot> snapshotableMetric) {
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
}
