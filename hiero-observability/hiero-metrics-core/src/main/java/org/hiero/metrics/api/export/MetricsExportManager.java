// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.core.MetricRegistry;

/**
 * Manager for exporting metrics data points from all managed registries to external systems using set of
 * {@link PushingMetricsExporter} or {@link PullingMetricsExporter}, or both.
 * <p>
 * Can be created using {@link org.hiero.metrics.api.core.MetricsFacade}.
 * Requires one or more {@link MetricRegistry} to manage.
 * <p>
 * All operations are thread-safe.
 */
public interface MetricsExportManager {

    /**
     * @return the name of the export manager, never {@code null}
     */
    @NonNull
    String name();

    /**
     * Add metrics registry to be exported.
     *
     * @param metricRegistry metrics registry to be exported, mut not be {@code null}
     * @return {@code true} if the registry is snapshotable and was added to the manager, {@code false} otherwise
     */
    boolean manageMetricRegistry(@NonNull MetricRegistry metricRegistry);

    /**
     * Reset all metrics from all managed metric registries.
     */
    void resetAll();

    /**
     * @return {@code true} if this manager has additional running thread to export metrics.
     */
    boolean hasRunningExportThread();

    /**
     * Stop exporting and shutdown the manager.
     * This method is idempotent and called to release resources when the manager is no longer needed.
     */
    void shutdown();
}
