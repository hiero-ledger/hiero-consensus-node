// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import org.hiero.metrics.api.export.AbstractMetricsExporter;
import org.hiero.metrics.api.export.MetricsExportException;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

/**
 * An abstract class for adapting {@link MetricsSnapshotsWriter} as a {@link PushingMetricsExporter}.
 * Subclasses must implement the {@link #openStream()} method to provide an {@link OutputStream}
 * where the metrics snapshots will be written.
 */
public abstract class PushingMetricsExporterWriterAdapter extends AbstractMetricsExporter
        implements PushingMetricsExporter {

    private final MetricsSnapshotsWriter writer;

    /**
     * Creates a new {@link PushingMetricsExporterWriterAdapter} with the given name and writer.
     *
     * @param name   the name of the exporter
     * @param writer the writer to use for writing metrics snapshots
     * @throws NullPointerException if any of the parameters is null
     */
    public PushingMetricsExporterWriterAdapter(@NonNull String name, @NonNull MetricsSnapshotsWriter writer) {
        super(name);
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    @Override
    public void export(@NonNull MetricsSnapshot snapshot) throws MetricsExportException {
        try (OutputStream stream = openStream()) {
            writer.write(snapshot, stream);
        } catch (IOException e) {
            throw new MetricsExportException("Error exporting metrics by " + name(), e);
        }
    }

    /**
     * Opens an {@link OutputStream} where the metrics snapshots will be written.
     *
     * @return an {@link OutputStream} for writing metrics snapshots
     * @throws IOException if an I/O error occurs while opening the stream
     */
    protected abstract OutputStream openStream() throws IOException;

    @Override
    public void close() throws IOException {
        // No resources to close
    }
}
