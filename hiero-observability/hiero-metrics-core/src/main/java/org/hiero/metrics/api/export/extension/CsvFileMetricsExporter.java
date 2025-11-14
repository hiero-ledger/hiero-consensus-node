// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import static java.nio.file.StandardOpenOption.APPEND;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.api.export.extension.writer.CsvMetricsSnapshotsWriter;

/**
 * A {@link PushingMetricsExporter} that writes metrics snapshots to a CSV file using {@link CsvMetricsSnapshotsWriter}.
 */
public class CsvFileMetricsExporter extends PushingMetricsExporterWriterAdapter {

    private final Path filePath;

    /**
     * Creates a new {@link CsvFileMetricsExporter} with the given name and file path using default CSV writer -
     * {@link CsvMetricsSnapshotsWriter#DEFAULT}.
     * If the file does not exist, it will be created along with any necessary parent directories.
     * The CSV headers will be written to the file upon creation.
     *
     * @param name     the name of the exporter
     * @param filePath the path to the CSV file where metrics snapshots will be written
     * @throws IOException if an I/O error occurs while creating the file or directories
     */
    public CsvFileMetricsExporter(@NonNull String name, @NonNull Path filePath) throws IOException {
        this(name, filePath, CsvMetricsSnapshotsWriter.DEFAULT);
    }

    /**
     * Creates a new {@link CsvFileMetricsExporter} with the given name, file path, and writer.
     * If the file does not exist, it will be created along with any necessary parent directories.
     * The CSV headers will be written to the file upon creation.
     *
     * @param name     the name of the exporter
     * @param filePath the path to the CSV file where metrics snapshots will be written
     * @param writer   the CSV metrics snapshots writer to use
     * @throws IOException if an I/O error occurs while creating the file or directories
     */
    public CsvFileMetricsExporter(
            @NonNull String name, @NonNull Path filePath, @NonNull CsvMetricsSnapshotsWriter writer)
            throws IOException {
        super(name, writer);
        this.filePath = Objects.requireNonNull(filePath, "file path must not be null");

        if (!Files.exists(filePath)) {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.createFile(filePath);
            try (OutputStream outputStream = Files.newOutputStream(filePath, APPEND)) {
                writer.writeHeaders(outputStream);
            }
        }
    }

    @Override
    protected OutputStream openStream() throws IOException {
        return Files.newOutputStream(filePath, APPEND);
    }
}
