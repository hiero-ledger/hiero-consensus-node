// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.export.file.config.MetricsFileExportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsFileExporterTest {

    private static final MetricKey<DoubleGauge> GAUGE = DoubleGauge.key("test_gauge");
    private static final MetricKey<LongCounter> COUNTER = LongCounter.key("test_counter");

    @TempDir
    Path tempDir;

    @Test
    void testExportCreatesFile() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt");
        MetricsFileExporter exporter = this.exporterWith(false);

        try (MetricRegistry ignored =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            awaitFile(filePath);
            assertThat(filePath).exists();
        }
    }

    @Test
    void testExportPlainTextContent() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt");
        MetricsFileExporter exporter = this.exporterWith(false);

        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            registry.register(DoubleGauge.builder(GAUGE).setDescription("A test gauge"));
            registry.getMetric(GAUGE).getOrCreateNotLabeled().set(42.5);

            // Wait for background thread to write at least once
            awaitFile(filePath);
            // Wait a bit more for a second write to ensure metric is included
            Thread.sleep(1200);

            String content = Files.readString(filePath);
            assertThat(content).contains("# TYPE test_gauge gauge");
            assertThat(content).contains("# HELP test_gauge A test gauge");
        }
    }

    @Test
    void testExportGzipContent() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt.gz");
        MetricsFileExporter exporter = this.exporterWith(true);

        // With a persistent GZIPOutputStream the gzip footer is only written on close(),
        // so we must close the registry before reading the file.
        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            registry.register(LongCounter.builder(COUNTER).setDescription("A test counter"));
            registry.getMetric(COUNTER).getOrCreateNotLabeled().increment(7L);

            awaitFile(filePath);
            Thread.sleep(1200);
        } // closes registry → exporter → GZIPOutputStream (writes gzip footer)

        String content;
        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(filePath))) {
            content = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(content).contains("# TYPE test_counter counter");
    }

    @Test
    void testCloseStopsExporter() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt");
        // Long interval so only the initial write happens while running
        MetricsFileExporter exporter = exporterWith(false, 9999);

        MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build();

        awaitFile(filePath);

        long fileModifiedTime = Files.getLastModifiedTime(filePath).toMillis();
        long closeStart = System.currentTimeMillis();
        registry.close(); // closes both registry and exporter
        long closeElapsed = System.currentTimeMillis() - closeStart;

        assertThat(closeElapsed)
                .as("close() should complete quickly by interrupting the sleeping thread")
                .isLessThan(2000);

        Thread.sleep(200);
        assertThat(Files.getLastModifiedTime(filePath).toMillis())
                .as("File should not be modified after close")
                .isEqualTo(fileModifiedTime);
    }

    @Test
    void testNoFileCreatedWhenNoSnapshotSupplier() throws Exception {
        Path filePath = tempDir.resolve("metrics.prom");
        MetricsFileExporter exporter = exporterWith(false, 1);
        // Never call setSnapshotSupplier — no thread should start, no file should appear
        Thread.sleep(200);
        assertThat(filePath).doesNotExist();
        exporter.close();
    }

    private MetricsFileExporter exporterWith(boolean useGzip) throws IOException {
        return exporterWith(useGzip, 1);
    }

    private MetricsFileExporter exporterWith(boolean useGzip, int intervalSeconds) {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.file.directory", tempDir.toString())
                .withValue("metrics.exporter.file.useGzip", String.valueOf(useGzip))
                .withValue("metrics.exporter.file.snapshotIntervalSeconds", String.valueOf(intervalSeconds))
                .build();
        return new MetricsFileExporter(config.getConfigData(MetricsFileExportConfig.class));
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!Files.exists(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(path).as("Expected file to be created: " + path).exists();
    }
}
