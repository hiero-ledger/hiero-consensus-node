// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.IOException;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.extension.PullingMetricsExporterAdapter;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;
import org.hiero.metrics.api.export.extension.writer.UnsynchronizedByteArrayOutputStream;

public class TestExporterContext {

    private final MetricsSnapshotsWriter snapshotsWriter;
    private final MetricRegistry registry;
    private final PullingMetricsExporterAdapter exporter = new PullingMetricsExporterAdapter("test");

    public TestExporterContext(MetricsSnapshotsWriter snapshotsWriter, Label... globalLabels) {
        this.snapshotsWriter = snapshotsWriter;
        registry = MetricsFacade.createRegistry(globalLabels);
        MetricsExportManager snapshotManager = MetricsFacade.createExportManager(exporter);
        snapshotManager.manageMetricRegistry(registry);
    }

    public MetricRegistry getRegistry() {
        return registry;
    }

    public void exportAndVerify(String expected) throws IOException {
        UnsynchronizedByteArrayOutputStream outputStream = new UnsynchronizedByteArrayOutputStream();
        snapshotsWriter.write(exporter.getSnapshot().get(), outputStream);
        assertThat(outputStream.toString()).isEqualTo(expected);
    }
}
