// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import static java.nio.file.StandardOpenOption.APPEND;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.metrics.api.export.AbstractMetricsExporter;
import org.hiero.metrics.api.export.MetricsExportException;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.api.export.extension.writer.CsvMetricsSnapshotsWriter;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

/**
 * A {@link PushingMetricsExporter} that writes metrics snapshots to a CSV file.
 */
public class CsvFileMetricsExporter extends AbstractMetricsExporter implements PushingMetricsExporter {

    private final Path filePath;
    private final CsvMetricsSnapshotsWriter writer;

    public CsvFileMetricsExporter(@NonNull String name, @NonNull Path filePath) throws IOException {
        super(name);
        this.filePath = Objects.requireNonNull(filePath, "file path must not be null");
        writer = CsvMetricsSnapshotsWriter.DEFAULT;

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
    public void export(@NonNull MetricsSnapshot snapshot) throws MetricsExportException {
        try (OutputStream outputStream = Files.newOutputStream(filePath, APPEND)) {
            writer.write(snapshot, outputStream);
        } catch (IOException e) {
            throw new MetricsExportException("Error exporting metrics by " + name(), e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }
}
