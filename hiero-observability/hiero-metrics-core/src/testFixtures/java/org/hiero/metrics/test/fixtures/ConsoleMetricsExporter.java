// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.OutputStream;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.api.export.extension.PushingMetricsExporterWriterAdapter;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;

/**
 * A simple {@link PushingMetricsExporter} that writes metrics snapshots to the console (standard output).
 */
public class ConsoleMetricsExporter extends PushingMetricsExporterWriterAdapter {

    public ConsoleMetricsExporter(@NonNull MetricsSnapshotsWriter writer) {
        super("console", writer);
    }

    @Override
    protected OutputStream openStream() {
        return System.out;
    }
}
