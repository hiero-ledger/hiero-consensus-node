// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AbstractCachingMetricsSnapshotsWriterTest {

    private TestAbstractCachingMetricsSnapshotsWriter writer;

    @BeforeEach
    void setUp() {
        writer = createWriter();
    }

    private void writeAndVerify(MetricsCollectionSnapshot snapshots, String expectedOutput) {
        try (var output = new UnsynchronizedByteArrayOutputStream()) {
            writer.write(snapshots, output);
            String result = output.toString();

            assertThat(result).isEqualTo(expectedOutput);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testMetricsAndDataPoints() {
        MetricsCollectionSnapshot snapshots = mockSnapshots(Instant.now());

        writeAndVerify(snapshots, "");
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedDatapointsAfterLastCall()).isEmpty();

        // add new metric1 with no data points
        MetricSnapshot metric1 = mockMetricSnapshot("metric1");
        when(metric1.size()).thenReturn(0);
        when(snapshots.iterator()).thenReturn(List.of(metric1).iterator());

        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                AFTER METRIC: metric1
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).containsExactlyInAnyOrder("metric1");
        assertThat(writer.getCreatedDatapointsAfterLastCall()).isEmpty();

        // add metric1 new data point and new metric2 without data points
        MetricSnapshot metric2 = mockMetricSnapshot("metric2");
        when(metric2.size()).thenReturn(0);
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());

        DataPointSnapshot dataPoint11 = mock(DataPointSnapshot.class);
        when(dataPoint11.toString()).thenReturn("dataPoint11");
        when(metric1.size()).thenReturn(1);
        when(metric1.get(0)).thenReturn(dataPoint11);

        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                DATA POINT: dataPoint11
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).containsExactlyInAnyOrder("metric2");
        assertThat(writer.getCreatedDatapointsAfterLastCall()).containsExactlyInAnyOrder("dataPoint11");

        // add metric1 new data point and metric2 new data point
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());
        DataPointSnapshot dataPoint12 = mock(DataPointSnapshot.class);
        when(dataPoint12.toString()).thenReturn("dataPoint12");
        when(metric1.size()).thenReturn(2);
        when(metric1.get(1)).thenReturn(dataPoint12);

        DataPointSnapshot dataPoint21 = mock(DataPointSnapshot.class);
        when(dataPoint21.toString()).thenReturn("dataPoint21");
        when(metric2.size()).thenReturn(1);
        when(metric2.get(0)).thenReturn(dataPoint21);

        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                DATA POINT: dataPoint11
                DATA POINT: dataPoint12
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                DATA POINT: dataPoint21
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedDatapointsAfterLastCall()).containsExactlyInAnyOrder("dataPoint12", "dataPoint21");

        // export again everything should be cached
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());
        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                DATA POINT: dataPoint11
                DATA POINT: dataPoint12
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                DATA POINT: dataPoint21
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedDatapointsAfterLastCall()).isEmpty();
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

    private TestAbstractCachingMetricsSnapshotsWriter createWriter() {
        return new TestAbstractCachingMetricsSnapshotsWriter.TestBuilder().build();
    }

    static class TestAbstractCachingMetricsSnapshotsWriter
            extends AbstractCachingMetricsSnapshotsWriter<
                    TestAbstractCachingMetricsSnapshotsWriter.TestBaseMetricExportData> {

        private final List<String> createdMetrics = new ArrayList<>();
        private final List<String> createdDatapoints = new ArrayList<>();

        protected TestAbstractCachingMetricsSnapshotsWriter(TestBuilder builder) {
            super(builder);
        }

        public List<String> getCreatedMetricsAfterLastCall() {
            ArrayList<String> result = new ArrayList<>(createdMetrics);
            createdMetrics.clear();
            return result;
        }

        public List<String> getCreatedDatapointsAfterLastCall() {
            ArrayList<String> result = new ArrayList<>(createdDatapoints);
            createdDatapoints.clear();
            return result;
        }

        @Override
        protected void beforeMetricWrite(
                @NonNull TestBaseMetricExportData metricExportData, @NonNull OutputStream output) throws IOException {
            super.beforeMetricWrite(metricExportData, output);
            output.write(
                    ("BEFORE METRIC: " + metricExportData.metricSnapshot() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected void writeDataPoint(
                @NonNull Instant timestamp,
                @NonNull DataPointSnapshot dataPointSnapshot,
                @NonNull ByteArrayTemplate dataPointExportTemplate,
                @NonNull OutputStream output)
                throws IOException {
            output.write(dataPointExportTemplate.iterator().next());
        }

        @Override
        protected void afterMetricWrite(
                @NonNull TestBaseMetricExportData metricExportData, @NonNull OutputStream output) throws IOException {
            super.afterMetricWrite(metricExportData, output);
            output.write(
                    ("AFTER METRIC: " + metricExportData.metricSnapshot() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected TestBaseMetricExportData buildMetricExportData(MetricSnapshot metricSnapshot) {
            createdMetrics.add(metricSnapshot.toString());
            return new TestBaseMetricExportData(metricSnapshot);
        }

        class TestBaseMetricExportData extends BaseMetricExportData {

            protected TestBaseMetricExportData(MetricSnapshot metricSnapshot) {
                super(metricSnapshot);
            }

            @Override
            protected ByteArrayTemplate buildDataPointExportTemplate(DataPointSnapshot dataPointSnapshot) {
                createdDatapoints.add(dataPointSnapshot.toString());
                return ByteArrayTemplate.builder()
                        .appendUtf8("DATA POINT: " + dataPointSnapshot + "\n")
                        .build();
            }
        }

        static class TestBuilder
                extends AbstractMetricsSnapshotsWriter.Builder<TestBuilder, TestAbstractCachingMetricsSnapshotsWriter> {

            @NonNull
            @Override
            protected TestBuilder self() {
                return this;
            }

            @NonNull
            @Override
            public TestAbstractCachingMetricsSnapshotsWriter build() {
                return new TestAbstractCachingMetricsSnapshotsWriter(this);
            }
        }
    }
}
