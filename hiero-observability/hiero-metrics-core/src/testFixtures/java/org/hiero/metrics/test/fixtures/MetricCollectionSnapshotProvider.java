// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures;

import java.util.function.Supplier;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.extension.PullingMetricsExporterAdapter;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Hodls a {@link MetricRegistry} and allows to access snapshots for testing purposes.
 */
public final class MetricCollectionSnapshotProvider implements Supplier<MetricsCollectionSnapshot> {

    private final MetricRegistry registry;
    private final PullingMetricsExporterAdapter exporter = new PullingMetricsExporterAdapter("test");

    /**
     * Creates a new instance with an empty registry and no global label set.
     */
    public MetricCollectionSnapshotProvider() {
        this(MetricRegistry.builder("registry").build());
    }

    /**
     * Creates a new instance with an empty registry and the given global labels.
     *
     * @param globalLabels the global labels to set
     */
    public MetricCollectionSnapshotProvider(Label... globalLabels) {
        this(MetricRegistry.builder("registry").withGlobalLabels(globalLabels).build());
    }

    /**
     * Creates a new instance with the given registry.
     *
     * @param registry the metric registry
     */
    public MetricCollectionSnapshotProvider(MetricRegistry registry) {
        this.registry = registry;
        MetricsExportManager.builder().withExporter(exporter).build(registry);
    }

    /**
     * Gets the underlying metric registry.
     *
     * @return the metric registry
     */
    public MetricRegistry getRegistry() {
        return registry;
    }

    /**
     * Gets the current metrics snapshot from the registry.
     *
     * @return the current metrics snapshot
     */
    @Override
    public MetricsCollectionSnapshot get() {
        return exporter.getSnapshot().get();
    }
}
