// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.internal.export.snapshot.MetricsSnapshotImpl;

/**
 * Base class for {@link MetricsExportManager} implementations.
 * <p>
 * Manages multiple metric registries and provides a combined snapshot of their metrics.<br>
 * With first managed {@link SnapshotableMetricsRegistry} instance, it calls {@link #init()} method
 * to allow subclasses to perform any necessary initialization.
 * Before that, this manger allows subclasses to register export metrics by calling
 * {@link #registerExportMetrics(String, MetricRegistry)} from {@link #initExportMetrics()}.
 * <p>
 * Metrics and datapoints snapshots are taken from all managed registries and are reusable objects, so
 * {@link #takeSnapshot()} is synchronized to ensure thread-safety and just updates the snapshots
 * from associated metrics and their datapoints.
 */
public abstract class AbstractMetricsExportManager implements MetricsExportManager {

    protected static final Logger logger = LogManager.getLogger(MetricsExportManager.class);

    private final String name;
    private final Set<Set<Label>> registriesGlobalLabels = new HashSet<>();
    private final List<SnapshotableMetricsRegistry> metricRegistries = new ArrayList<>();
    private final MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();

    protected AbstractMetricsExportManager(@NonNull String name) {
        this.name = ArgumentUtils.throwArgBlank(name, "name");
    }

    @NonNull
    @Override
    public final String name() {
        return name;
    }

    private void initExportMetrics() {
        SnapshotableMetricsRegistry exportMetricsRegistry =
                (SnapshotableMetricsRegistry) MetricsFacade.createRegistry(new Label("export_manager", name));
        registerExportMetrics("export", exportMetricsRegistry);
        snapshots.addRegistry(exportMetricsRegistry);
    }

    /**
     * Initializes the manager.
     * Called only once when first snapshotable metrics registry is managed by this manager
     * when {@link #manageMetricRegistry(MetricRegistry)} is called.
     */
    protected abstract void init();

    /**
     * Register export metrics.
     *
     * @param category category for export metrics to be used
     * @param exportMetricsRegistry registry to register export metrics with
     */
    protected void registerExportMetrics(@NonNull String category, @NonNull MetricRegistry exportMetricsRegistry) {
        // nothing by default
    }

    @Override
    public final boolean manageMetricRegistry(@NonNull MetricRegistry registry) {
        if (registry instanceof SnapshotableMetricsRegistry snapshotableRegistry) {
            boolean firstSnapshotableRegistry = false;

            synchronized (this) {
                if (!registry.globalLabels().isEmpty()
                        && !registriesGlobalLabels.add(new HashSet<>(registry.globalLabels()))) {
                    throw new IllegalArgumentException(
                            "Metric registry has duplicate global labels with another registry: "
                                    + registry.globalLabels());
                }

                if (metricRegistries.isEmpty()) {
                    firstSnapshotableRegistry = true;
                    initExportMetrics();
                } else {
                    // check same registry instance is not added twice
                    if (metricRegistries.contains(snapshotableRegistry)) {
                        throw new IllegalArgumentException("Metric registry instance is already managed: " + registry);
                    }
                }

                metricRegistries.add(snapshotableRegistry);
                snapshots.addRegistry(snapshotableRegistry);
            }

            logger.info("Added metrics registry with global labels: {}", registry.globalLabels());

            if (firstSnapshotableRegistry) {
                init();
            }

            return true;
        }
        return false;
    }

    @NonNull
    protected final synchronized Optional<MetricsSnapshot> takeSnapshot() {
        if (metricRegistries.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(snapshots.update());
    }

    @Override
    public final void resetAll() {
        metricRegistries.forEach(MetricRegistry::reset);
    }

    @Override
    public void shutdown() {
        // nothing to do by default
    }
}
