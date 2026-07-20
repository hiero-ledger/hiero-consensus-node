// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static java.lang.System.Logger.Level.ERROR;
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
 * {@link #close()} is called. Each export cycle writes a snapshot in which every sample line carries
 * the snapshot's millisecond timestamp; snapshots are simply concatenated (there is no {@code # EOF}
 * terminator). The file grows unboundedly; external rotation (e.g. logrotate) is the caller's
 * responsibility.
 * <p>
 * When gzip is enabled, the output is a single continuous gzip stream spanning all snapshots,
 * achieving better compression than per-snapshot gzip members because metric names and labels
 * repeat across snapshots and are shared by the compressor's dictionary. The stream is created with
 * sync-flush enabled, so each export cycle is flushed to disk and earlier snapshots survive an
 * abrupt process termination. The gzip footer (and final CRC) is only written on {@link #close()},
 * so a file from a process that did not close cleanly decompresses up to its last flushed snapshot.
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
        this.config = Objects.requireNonNull(config, "Metrics file export config must not be null");
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
                    .name("metrics-file-exporter")
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
            // syncFlush=true so each export cycle's flush() reaches disk; without it the deflater
            // buffers all data until close() and an abrupt termination would lose the whole history.
            os = new GZIPOutputStream(os, true);
        }
        outputStream = os;
    }

    private void exportLoop() {
        logger.log(INFO, "Metrics file exporter started. directory={0}", config.directory());
        int consecutiveFailures = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                writer.write(snapshotSupplier.get(), outputStream);
                consecutiveFailures = 0;
            } catch (IOException e) {
                // Stream errors are unlikely to recover; stop the loop
                logger.log(ERROR, "Metrics file export failed with I/O error, stopping exporter.", e);
                break;
            } catch (Exception e) {
                consecutiveFailures++;
                if (consecutiveFailures == 1 || (consecutiveFailures & (consecutiveFailures - 1)) == 0) {
                    // Log on first failure, then on powers of 2 (1, 2, 4, 8...) to cap noise
                    logger.log(
                            WARNING,
                            "Failed to export metrics to file ({0} consecutive failure(s)).",
                            consecutiveFailures,
                            e);
                }
            }
            try {
                Thread.sleep(Duration.ofSeconds(config.snapshotIntervalSeconds()));
            } catch (InterruptedException e) {
                break;
            }
        }

        try {
            // thread owns the stream, thread closes it
            outputStream.close();
        } catch (IOException e) {
            logger.log(WARNING, "Failed to close metrics output stream", e);
        }

        logger.log(INFO, "Metrics file exporter stopped.");
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

            if (thread.isAlive()) {
                throw new IOException("Metrics file exporter thread did not finish within 5 seconds");
            }
        }
        logger.log(INFO, "Metrics file exporter closed.");
    }
}
