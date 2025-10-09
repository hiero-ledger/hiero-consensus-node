// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.internal.export.SnapshotableMetricsRegistry;

public final class DefaultMetricsSnapshot implements MetricsSnapshot {

    private Instant createdAt = Instant.now();
    private List<UpdatableMetricRegistrySnapshot> registrySnapshots = List.of();

    public DefaultMetricsSnapshot update() {
        createdAt = Instant.now();
        for (UpdatableMetricRegistrySnapshot snapshot : registrySnapshots) {
            snapshot.updateSnapshot();
        }
        return this;
    }

    public void addRegistry(@NonNull SnapshotableMetricsRegistry registry) {
        // in production, we expect one single metric registry to exist, so we optimize for that case
        if (registrySnapshots.isEmpty()) {
            registrySnapshots = List.of(registry.snapshot());
        } else {
            if (registrySnapshots.size() == 1) {
                List<UpdatableMetricRegistrySnapshot> newSnapshots = new ArrayList<>(4);
                newSnapshots.add(registrySnapshots.getFirst());
                registrySnapshots = newSnapshots;
            }
            registrySnapshots.add(registry.snapshot());
        }
    }

    @NonNull
    @Override
    public Instant createAt() {
        return createdAt;
    }

    @NonNull
    @Override
    public Iterator<MetricSnapshot> iterator() {
        if (registrySnapshots.isEmpty()) {
            return Collections.emptyIterator();
        } else if (registrySnapshots.size() == 1) {
            return registrySnapshots.getFirst().iterator();
        }

        return new Iterator<>() {

            int registryIdx = 0;
            int metricIdx = 0;

            @Override
            public boolean hasNext() {
                while (registryIdx < registrySnapshots.size()) {
                    if (metricIdx < registrySnapshots.get(registryIdx).size()) {
                        return true;
                    } else {
                        registryIdx++;
                        metricIdx = 0;
                    }
                }
                return false;
            }

            @Override
            public MetricSnapshot next() {
                return registrySnapshots.get(registryIdx).get(metricIdx++);
            }
        };
    }
}
