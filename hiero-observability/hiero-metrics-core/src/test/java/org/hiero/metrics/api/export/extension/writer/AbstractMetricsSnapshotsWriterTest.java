// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.junit.jupiter.api.Test;

public class AbstractMetricsSnapshotsWriterTest {

    @Test
    void testNullFilterBuilderThrows() {
        TestAbstractMetricsSnapshotsWriter.TestBuilder builder = builder();

        assertThatThrownBy(() -> builder.withMetricFilter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("metric filter cannot be null");
    }

    @Test
    void testNullFormatBuilderThrows() {
        TestAbstractMetricsSnapshotsWriter.TestBuilder builder = builder();

        assertThatThrownBy(() -> builder.withDecimalFormat(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("format cannot be null");
    }

    @Test
    void testDefaultFormat() {
        TestAbstractMetricsSnapshotsWriter writer = builder().build();

        // longs
        assertThat(writer.format(0L)).isEqualTo("0");
        assertThat(writer.format(123456789L)).isEqualTo("123456789");
        assertThat(writer.format(-123456789L)).isEqualTo("-123456789");

        // doubles
        assertThat(writer.format(0.0)).isEqualTo("0");
        assertThat(writer.format(10.0)).isEqualTo("10");
        assertThat(writer.format(10.1)).isEqualTo("10.1");
        assertThat(writer.format(-10.0)).isEqualTo("-10");
        assertThat(writer.format(-10.1)).isEqualTo("-10.1");
        assertThat(writer.format(12345.6789)).isEqualTo("12345.679");
        assertThat(writer.format(12345.6739)).isEqualTo("12345.674");
        assertThat(writer.format(-12345.6789)).isEqualTo("-12345.679");
        assertThat(writer.format(-12345.6739)).isEqualTo("-12345.674");
        assertThat(writer.format(Double.NaN)).isEqualTo("NaN");
    }

    @Test
    void testCustomFormat() {
        TestAbstractMetricsSnapshotsWriter writer =
                builder().withDecimalFormat("#.#").build();

        // longs
        assertThat(writer.format(0L)).isEqualTo("0");
        assertThat(writer.format(123456789L)).isEqualTo("123456789");
        assertThat(writer.format(-123456789L)).isEqualTo("-123456789");

        // doubles
        assertThat(writer.format(0.0)).isEqualTo("0");
        assertThat(writer.format(10.0)).isEqualTo("10");
        assertThat(writer.format(10.1)).isEqualTo("10.1");
        assertThat(writer.format(-10.0)).isEqualTo("-10");
        assertThat(writer.format(-10.1)).isEqualTo("-10.1");
        assertThat(writer.format(12345.6789)).isEqualTo("12345.7");
        assertThat(writer.format(12345.6489)).isEqualTo("12345.6");
        assertThat(writer.format(-12345.6789)).isEqualTo("-12345.7");
        assertThat(writer.format(-12345.6489)).isEqualTo("-12345.6");
        assertThat(writer.format(Double.NaN)).isEqualTo("NaN");
    }

    @Test
    void testWriterNoSnapshots() {
        TestAbstractMetricsSnapshotsWriter writer = builder().build();
        MetricsCollectionSnapshot snapshots = mockSnapshots(Instant.now());

        try (var output = new UnsynchronizedByteArrayOutputStream()) {
            writer.write(snapshots, output);
            String result = output.toString();

            assertThat(result)
                    .isEqualTo(
                            """
                BEFORE SNAPSHOTS: snapshots
                AFTER SNAPSHOTS: snapshots""");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        verify(snapshots, never()).createAt();
        verify(snapshots).iterator();
    }

    @Test
    void testWriterSnapshotsNoFilter() {
        Instant timestamp = LocalDate.of(2025, 11, 14).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        MetricSnapshot metric1 = mockMetricSnapshot("metric1");
        mockMetadata(metric1);
        MetricSnapshot metric2 = mockMetricSnapshot("metric2");
        mockMetadata(metric2);
        MetricsCollectionSnapshot snapshots = mockSnapshots(timestamp, metric1, metric2);

        TestAbstractMetricsSnapshotsWriter writer = builder().build();

        try (var output = new UnsynchronizedByteArrayOutputStream()) {
            writer.write(snapshots, output);
            String result = output.toString();

            assertThat(result)
                    .isEqualTo(
                            """
                BEFORE SNAPSHOTS: snapshots
                METRIC SNAPSHOT: 2025-11-14T00:00:00Z-metric1
                METRIC SNAPSHOT: 2025-11-14T00:00:00Z-metric2
                AFTER SNAPSHOTS: snapshots""");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        verify(snapshots, times(2)).createAt();
        verify(snapshots).iterator();
        verify(metric1).metadata();
        verify(metric2).metadata();
    }

    @Test
    void testWriterSnapshotsWithFilter() {
        Instant timestamp = LocalDate.of(2025, 11, 14).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

        MetricSnapshot metric1 = mockMetricSnapshot("metric1");
        MetricMetadata metadata1 = mockMetadata(metric1);
        MetricSnapshot metric2 = mockMetricSnapshot("metric2");
        mockMetadata(metric2);
        MetricsCollectionSnapshot snapshots = mockSnapshots(timestamp, metric1, metric2);

        TestAbstractMetricsSnapshotsWriter writer =
                builder().withMetricFilter(metadata -> metadata != metadata1).build();

        try (var output = new UnsynchronizedByteArrayOutputStream()) {
            writer.write(snapshots, output);
            String result = output.toString();

            assertThat(result)
                    .isEqualTo(
                            """
                BEFORE SNAPSHOTS: snapshots
                METRIC SNAPSHOT: 2025-11-14T00:00:00Z-metric2
                AFTER SNAPSHOTS: snapshots""");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        verify(snapshots, times(1)).createAt();
        verify(snapshots).iterator();
        verify(metric1).metadata();
        verify(metric2).metadata();
    }

    private MetricsCollectionSnapshot mockSnapshots(Instant timestamp, MetricSnapshot... metrics) {
        MetricsCollectionSnapshot snapshots = mock(MetricsCollectionSnapshot.class);
        when(snapshots.toString()).thenReturn("snapshots");
        when(snapshots.createAt()).thenReturn(timestamp);
        when(snapshots.iterator()).thenReturn(java.util.List.of(metrics).iterator());
        return snapshots;
    }

    private MetricSnapshot mockMetricSnapshot(String name) {
        MetricSnapshot snapshot = mock(MetricSnapshot.class);
        when(snapshot.toString()).thenReturn(name);
        return snapshot;
    }

    private MetricMetadata mockMetadata(MetricSnapshot metricSnapshot) {
        MetricMetadata metadata = mock(MetricMetadata.class);
        when(metricSnapshot.metadata()).thenReturn(metadata);
        return metadata;
    }

    private TestAbstractMetricsSnapshotsWriter.TestBuilder builder() {
        return new TestAbstractMetricsSnapshotsWriter.TestBuilder();
    }

    static class TestAbstractMetricsSnapshotsWriter extends AbstractMetricsSnapshotsWriter {

        public TestAbstractMetricsSnapshotsWriter(TestBuilder builder) {
            super(builder);
        }

        @Override
        protected void beforeSnapshotsWrite(@NonNull MetricsCollectionSnapshot snapshots, @NonNull OutputStream output)
                throws IOException {
            super.beforeSnapshotsWrite(snapshots, output);
            output.write(("BEFORE SNAPSHOTS: " + snapshots + '\n').getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected void writeMetricSnapshot(Instant timestamp, MetricSnapshot metricSnapshot, OutputStream output)
                throws IOException {
            output.write(
                    ("METRIC SNAPSHOT: " + timestamp + '-' + metricSnapshot + '\n').getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected void afterSnapshotsWrite(@NonNull MetricsCollectionSnapshot snapshots, @NonNull OutputStream output)
                throws IOException {
            output.write(("AFTER SNAPSHOTS: " + snapshots).getBytes(StandardCharsets.UTF_8));
            super.afterSnapshotsWrite(snapshots, output);
        }

        static class TestBuilder
                extends AbstractMetricsSnapshotsWriter.Builder<TestBuilder, TestAbstractMetricsSnapshotsWriter> {

            @NonNull
            @Override
            protected TestBuilder self() {
                return this;
            }

            @NonNull
            @Override
            public TestAbstractMetricsSnapshotsWriter build() {
                return new TestAbstractMetricsSnapshotsWriter(this);
            }
        }
    }
}
