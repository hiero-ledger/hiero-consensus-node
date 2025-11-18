// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.PullingMetricsExporter;
import org.hiero.metrics.api.export.extension.PullingMetricsExporterAdapter;
import org.junit.jupiter.api.Test;

public class SinglePullingExporterMetricsExportManagerTest {

    @Test
    void testNullRegistryThrows() {
        assertThatThrownBy(() -> new SinglePullingExporterMetricsExportManager(null, createExporter()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullExporterThrows() {
        assertThatThrownBy(() -> new SinglePullingExporterMetricsExportManager(createRegistry(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("exporter must not be null");
    }

    @Test
    void testBasicProperties() {
        SnapshotableMetricsRegistry registry = createRegistry();
        MetricsExportManager manager = createManager(registry);

        assertThat(manager.hasRunningExportThread()).isFalse();
        assertThat(manager.registry()).isSameAs(registry);
    }

    @Test
    void testSnapshotAvailableToExporter() {
        PullingMetricsExporterAdapter exporter = createExporter();
        createManager(createRegistry(), exporter);
        assertThat(exporter.getSnapshot()).isPresent();
    }

    @Test
    void testExporterShutdown() throws IOException {
        PullingMetricsExporter exporter = mock(PullingMetricsExporter.class);
        MetricsExportManager manager =
                createManager(MetricRegistry.builder("registry").build(), exporter);
        verify(exporter).setSnapshotProvider(any());

        manager.shutdown();
        verify(exporter).close();
    }

    @Test
    void testClosingExporterFails() throws IOException {
        PullingMetricsExporter exporter = mock(PullingMetricsExporter.class);
        MetricsExportManager manager = createManager(createRegistry(), exporter);

        IOException ioException = new IOException("close failed");
        doThrow(ioException).when(exporter).close();

        assertThatThrownBy(manager::shutdown)
                .isInstanceOf(RuntimeException.class)
                .hasCause(ioException);
    }

    private PullingMetricsExporterAdapter createExporter() {
        return new PullingMetricsExporterAdapter("test-exporter");
    }

    private MetricsExportManager createManager(MetricRegistry registry, PullingMetricsExporter exporter) {
        return new SinglePullingExporterMetricsExportManager((SnapshotableMetricsRegistry) registry, exporter);
    }

    private MetricsExportManager createManager(MetricRegistry registry) {
        return createManager(registry, createExporter());
    }

    private SnapshotableMetricsRegistry createRegistry() {
        return (SnapshotableMetricsRegistry) MetricRegistry.builder("registry").build();
    }
}
