// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Interface for writing {@link MetricsCollectionSnapshot} to an output stream.
 */
public interface MetricsSnapshotsWriter {

    /**
     * Writes {@link MetricsCollectionSnapshot} to the provided output stream.
     *
     * @param output the output to write to
     * @throws IOException if an error occurs during export
     */
    void write(@NonNull MetricsCollectionSnapshot snapshots, @NonNull OutputStream output) throws IOException;
}
