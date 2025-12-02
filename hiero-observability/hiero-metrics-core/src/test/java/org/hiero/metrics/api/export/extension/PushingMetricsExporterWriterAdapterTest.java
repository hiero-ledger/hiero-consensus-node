// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import org.hiero.metrics.api.export.MetricsExportException;
import org.hiero.metrics.api.export.extension.writer.MetricsSnapshotsWriter;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PushingMetricsExporterWriterAdapterTest {

    @Test
    void testNullNameThrows() {
        assertThatThrownBy(() -> new TestAdapter(null, mock(MetricsSnapshotsWriter.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void testBlankNameThrows(String name) {
        assertThatThrownBy(() -> new TestAdapter(name, mock(MetricsSnapshotsWriter.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void testNullWriterThrows() {
        assertThatThrownBy(() -> new TestAdapter("test", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("writer must not be null");
    }

    @Test
    void testWriterThrowsIOException() throws IOException {
        IOException ioException = new IOException("test IO exception");
        MetricsCollectionSnapshot snapshot = mock(MetricsCollectionSnapshot.class);
        MetricsSnapshotsWriter writer = mock(MetricsSnapshotsWriter.class);
        TestAdapter adapter = new TestAdapter("test", writer);

        doThrow(ioException).when(writer).write(snapshot, adapter.out);

        assertThatThrownBy(() -> adapter.export(snapshot))
                .isInstanceOf(MetricsExportException.class)
                .hasCause(ioException)
                .hasMessage("Error exporting metrics by test");
    }

    @Test
    void testWriter() throws MetricsExportException, IOException {
        MetricsCollectionSnapshot snapshot = mock(MetricsCollectionSnapshot.class);
        MetricsSnapshotsWriter writer = mock(MetricsSnapshotsWriter.class);
        TestAdapter adapter = new TestAdapter("test", writer);

        adapter.export(snapshot);

        verify(writer).write(eq(snapshot), eq(adapter.out));
    }

    private static class TestAdapter extends PushingMetricsExporterWriterAdapter {

        private final OutputStream out = mock(OutputStream.class);

        public TestAdapter(@NonNull String name, @NonNull MetricsSnapshotsWriter writer) {
            super(name, writer);
        }

        @Override
        protected OutputStream openStream() {
            reset(out);
            return out;
        }
    }
}
