// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.OutputStream;
import org.hiero.metrics.api.export.extension.PushingMetricsExporterWriterAdapter;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;

public class ConsoleMetricsExporter extends PushingMetricsExporterWriterAdapter {

    public ConsoleMetricsExporter(@NonNull MetricsSnapshotsWriter writer) {
        super("console", writer);
    }

    @Override
    protected OutputStream openStream() {
        return System.out;
    }
}
