// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.metrics.api.export.PullingMetricsExporter;

/**
 * A {@link org.hiero.metrics.api.export.MetricsExportManager} implementation that works with
 * a single {@link PullingMetricsExporter}.
 * <p>
 * The exporter is initialized when the first {@link SnapshotableMetricsRegistry} is managed by this
 * manager. The exporter is provided with a snapshot supplier that takes snapshots of all managed
 * registries on demand.
 */
public class SinglePullingExporterMetricsExportManager extends AbstractMetricsExportManager {

    private final PullingMetricsExporter exporter;

    public SinglePullingExporterMetricsExportManager(@NonNull String name, @NonNull PullingMetricsExporter exporter) {
        super(name);
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    @Override
    protected void init() {
        exporter.init(this::takeSnapshot);
    }

    @Override
    public boolean hasRunningExportThread() {
        return false;
    }
}
