// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;
import org.hiero.metrics.api.export.extension.writer.UnsynchronizedByteArrayOutputStream;
import org.hiero.metrics.test.fixtures.MetricsSnapshotProvider;

public class TestWriterContext {

    private final MetricsSnapshotsWriter snapshotsWriter;
    private final MetricsSnapshotProvider snapshotProvider;

    public TestWriterContext(MetricsSnapshotsWriter snapshotsWriter, Label... globalLabels) {
        this.snapshotsWriter = snapshotsWriter;
        snapshotProvider = new MetricsSnapshotProvider(globalLabels);
    }

    public MetricRegistry getRegistry() {
        return snapshotProvider.getRegistry();
    }

    public void exportAndVerify(String expected) throws IOException {
        UnsynchronizedByteArrayOutputStream outputStream = new UnsynchronizedByteArrayOutputStream();
        snapshotsWriter.write(snapshotProvider.get(), outputStream);
        assertThat(outputStream.toString()).isEqualTo(expected);
    }
}
