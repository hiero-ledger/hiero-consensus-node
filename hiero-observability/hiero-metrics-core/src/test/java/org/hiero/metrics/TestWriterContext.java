// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;
import org.hiero.metrics.api.export.extension.writer.UnsynchronizedByteArrayOutputStream;
import org.hiero.metrics.test.fixtures.MetricCollectionSnapshotProvider;

public class TestWriterContext {

    private final MetricsSnapshotsWriter snapshotsWriter;
    private final MetricCollectionSnapshotProvider snapshotProvider;

    public TestWriterContext(MetricsSnapshotsWriter snapshotsWriter, Label... globalLabels) {
        this.snapshotsWriter = snapshotsWriter;
        snapshotProvider = new MetricCollectionSnapshotProvider(globalLabels);
    }

    public MetricRegistry getRegistry() {
        return snapshotProvider.getRegistry();
    }

    public void exportAndVerify(String expected) throws IOException {
        UnsynchronizedByteArrayOutputStream outputStream = new UnsynchronizedByteArrayOutputStream();
        snapshotsWriter.write(snapshotProvider.get(), outputStream);
        assertThat(new ByteArrayInputStream(outputStream.toByteArray())).hasContent(expected);
    }
}
