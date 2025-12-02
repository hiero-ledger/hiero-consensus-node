// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;

/**
 * Base class for {@link MetricsExportManager} implementations holding na.
 * <p>
 * Metrics and datapoints snapshots taken from the managed registry are reusable objects, so
 * {@link #takeSnapshot()} is synchronized to ensure thread-safety and just updates the snapshots
 * from associated metrics and their datapoints.
 */
public abstract class AbstractMetricsExportManager implements MetricsExportManager {

    protected static final Logger logger = LogManager.getLogger(MetricsExportManager.class);

    private final SnapshotableMetricsRegistry registry;
    private final UpdatableMetricRegistrySnapshot snapshot;

    protected AbstractMetricsExportManager(@NonNull SnapshotableMetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        snapshot = registry.snapshot();
    }

    @NonNull
    @Override
    public final MetricRegistry registry() {
        return registry;
    }

    @NonNull
    protected final synchronized Optional<MetricsCollectionSnapshot> takeSnapshot() {
        return Optional.of(snapshot.update());
    }

    @Override
    public void shutdown() {
        // nothing to do by default
    }
}
