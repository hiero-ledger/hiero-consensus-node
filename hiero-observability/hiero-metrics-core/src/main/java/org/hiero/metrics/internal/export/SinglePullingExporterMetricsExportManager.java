// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import org.hiero.metrics.api.export.PullingMetricsExporter;

/**
 * A {@link org.hiero.metrics.api.export.MetricsExportManager} implementation that works with
 * a single {@link PullingMetricsExporter}.
 * <p>
 * The exporter is provided with a snapshot supplier that takes snapshots of all managed
 * registries on demand.
 */
public final class SinglePullingExporterMetricsExportManager extends AbstractMetricsExportManager {

    private final PullingMetricsExporter exporter;

    public SinglePullingExporterMetricsExportManager(
            @NonNull SnapshotableMetricsRegistry registry, @NonNull PullingMetricsExporter exporter) {
        super(registry);
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.exporter.setSnapshotProvider(this::takeSnapshot);
    }

    @Override
    public boolean hasRunningExportThread() {
        return false;
    }

    @Override
    public void shutdown() {
        try {
            exporter.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
