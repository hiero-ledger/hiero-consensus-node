// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricRegistrySnapshot;
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

            await(() -> readString(filePath).contains("test_gauge 42.5"), "Expected the gauge sample to be written");

            String content = readString(filePath);
            assertThat(content).contains("# TYPE test_gauge gauge");
            assertThat(content).contains("# HELP test_gauge A test gauge");
        }
    }

    @Test
    void testMetadataWrittenOnceAcrossSnapshots() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt");
        MetricsFileExporter exporter = this.exporterWith(false);

        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            registry.register(DoubleGauge.builder(GAUGE));
            registry.getMetric(GAUGE).getOrCreateNotLabeled().set(42.5);

            // Wait until at least two snapshots have written the sample line.
            await(
                    () -> countOccurrences(readString(filePath), "test_gauge 42.5") >= 2,
                    "Expected at least two snapshots to be written");

            String content = readString(filePath);
            assertThat(countOccurrences(content, "# TYPE test_gauge gauge"))
                    .as("TYPE metadata must be written only once across snapshots")
                    .isEqualTo(1);
        }
    }

    @Test
    void testExportGzipContent() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt.gz");
        MetricsFileExporter exporter = this.exporterWith(true);

        // With a persistent GZIPOutputStream the gzip footer is only written on close(),
        // so we close the registry before reading the file.
        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            registry.register(LongCounter.builder(COUNTER).setDescription("A test counter"));
            registry.getMetric(COUNTER).getOrCreateNotLabeled().increment(7L);

            // The gzip member footer is only written on close() and the data cannot be decompressed
            // while the stream is open, so we cannot poll content here. Wait just over one interval
            // to guarantee a snapshot taken after registration, then close to finish the member.
            awaitFile(filePath);
            Thread.sleep(1300);
        } // closes registry → exporter → GZIPOutputStream (writes gzip footer)

        assertThat(gunzip(filePath)).contains("# TYPE test_counter_total counter");
    }

    @Test
    void testGzipAppendsAcrossRestarts() throws Exception {
        Path filePath = tempDir.resolve("metrics.txt.gz");

        // First run writes a counter, then closes (finishing the first gzip member).
        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporterWith(true)).build()) {
            registry.register(LongCounter.builder(COUNTER));
            registry.getMetric(COUNTER).getOrCreateNotLabeled().increment(7L);
            awaitFile(filePath);
            Thread.sleep(1300);
        }

        // Second run appends a new gzip member to the same file.
        try (MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporterWith(true)).build()) {
            registry.register(DoubleGauge.builder(GAUGE));
            registry.getMetric(GAUGE).getOrCreateNotLabeled().set(1.0);
            Thread.sleep(1300);
        }

        // GZIPInputStream transparently reads both concatenated members.
        String content = gunzip(filePath);
        assertThat(content).contains("# TYPE test_counter_total counter").contains("# TYPE test_gauge gauge");
    }

    @Test
    void testDirectoryCreatedWhenMissing() throws Exception {
        Path missingDir = tempDir.resolve("nested/metrics/dir");
        Path filePath = missingDir.resolve("metrics.txt");
        MetricsFileExporter exporter = exporterWith(missingDir, false, 1);

        try (MetricRegistry ignored =
                MetricRegistry.builder().setMetricsExporter(exporter).build()) {
            awaitFile(filePath);
            assertThat(missingDir).isDirectory();
            assertThat(filePath).exists();
        }
    }

    @Test
    void testExportLoopSurvivesThrowingSupplier() throws Exception {
        MetricsFileExporter exporter = exporterWith(false);
        AtomicInteger calls = new AtomicInteger();

        // The first cycle throws; the loop must log it and keep running for later cycles.
        exporter.setSnapshotSupplier(() -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
            return new MetricRegistrySnapshot();
        });

        await(() -> calls.get() >= 3, "Export loop must continue after a throwing snapshot supplier");
        exporter.close();
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
        Path filePath = tempDir.resolve("metrics.txt");
        MetricsFileExporter exporter = exporterWith(false, 1);
        // Never call setSnapshotSupplier — no thread should start, no file should appear
        Thread.sleep(200);
        assertThat(filePath).doesNotExist();
        exporter.close();
    }

    private MetricsFileExporter exporterWith(boolean useGzip) {
        return exporterWith(tempDir, useGzip, 1);
    }

    private MetricsFileExporter exporterWith(boolean useGzip, int intervalSeconds) {
        return exporterWith(tempDir, useGzip, intervalSeconds);
    }

    private MetricsFileExporter exporterWith(Path directory, boolean useGzip, int intervalSeconds) {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.file.directory", directory.toString())
                .withValue("metrics.exporter.file.useGzip", String.valueOf(useGzip))
                .withValue("metrics.exporter.file.snapshotIntervalSeconds", String.valueOf(intervalSeconds))
                .build();
        return new MetricsFileExporter(config.getConfigData(MetricsFileExportConfig.class));
    }

    private static void awaitFile(Path path) throws InterruptedException {
        await(() -> Files.exists(path), "Expected file to be created: " + path);
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private static String readString(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String gunzip(Path path) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(path))) {
            return new String(gis.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
