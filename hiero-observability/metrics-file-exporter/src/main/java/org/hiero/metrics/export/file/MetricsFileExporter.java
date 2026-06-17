// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.export.file.config.MetricsFileExportConfig;

/**
 * A file-based exporter that periodically appends metrics snapshots in Prometheus text format to a file,
 * building a historical record suitable for batch ingestion into backends such as VictoriaMetrics.
 * <p>
 * A daemon thread is started on the first call to {@link #setSnapshotSupplier} and runs until
 * {@link #close()} is called. Each export cycle writes a timestamped snapshot (terminated by
 * {@code # EOF}) to the persistent output stream. The file grows unboundedly; external rotation
 * (e.g. logrotate) is the caller's responsibility.
 * <p>
 * When gzip is enabled, the output is a single continuous gzip stream spanning all snapshots,
 * achieving better compression than per-snapshot gzip members because metric names and labels
 * repeat across snapshots and are shared by the compressor's dictionary. The gzip footer is
 * written only on {@link #close()}, so the file is not a valid gzip archive until then.
 * <p>
 * Supports optional gzip compression and buffered I/O, configured via {@link MetricsFileExportConfig}.
 */
public class MetricsFileExporter implements MetricsExporter {

    private static final System.Logger logger = System.getLogger(MetricsFileExporter.class.getName());

    private final MetricsFileWriter writer;
    private final MetricsFileExportConfig config;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile Supplier<MetricRegistrySnapshot> snapshotSupplier;
    private volatile Thread exportThread;
    private OutputStream outputStream;

    public MetricsFileExporter(@NonNull MetricsFileExportConfig config) {
        Objects.requireNonNull(config, "OpenMetrics File Export config must not be null");
        this.config = config;
        this.writer = new MetricsFileWriter(config.decimalFormat());
    }

    @Override
    public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;

        if (started.compareAndSet(false, true)) {
            try {
                openOutputStream();
            } catch (IOException e) {
                throw new RuntimeException("Failed to open metrics output in directory: " + config.directory(), e);
            }
            exportThread = Thread.ofPlatform()
                    .daemon(true)
                    .name("openmetrics-file-exporter")
                    .start(this::exportLoop);
        }
    }

    private void openOutputStream() throws IOException {
        Objects.requireNonNull(config.directory(), "directory must not be null");
        Files.createDirectories(config.directory());

        Path filePath = config.useGzip()
                ? config.directory().resolve("metrics.txt.gz")
                : config.directory().resolve("metrics.txt");

        OutputStream os = Files.newOutputStream(filePath, CREATE, APPEND);
        if (config.bufferSize() > 0) {
            os = new BufferedOutputStream(os, config.bufferSize());
        }
        if (config.useGzip()) {
            os = new GZIPOutputStream(os);
        }
        outputStream = os;
    }

    private void exportLoop() {
        logger.log(INFO, "OpenMetrics file exporter started. directory={0}", config.directory());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                writer.write(snapshotSupplier.get(), outputStream);
            } catch (IOException e) {
                logger.log(WARNING, "Failed to export metrics to file", e);
            }
            try {
                Thread.sleep(Duration.ofSeconds(config.snapshotIntervalSeconds()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.log(INFO, "OpenMetrics file exporter stopped.");
    }

    @Override
    public void close() throws IOException {
        final Thread thread = exportThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (outputStream != null) {
            outputStream.close();
        }
        logger.log(INFO, "OpenMetrics file exporter closed.");
    }
}
