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
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
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
    void testMetricsAndMeasurements() {
        MetricsCollectionSnapshot snapshots = mockSnapshots(Instant.now());

        writeAndVerify(snapshots, "");
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedMeasurementsAfterLastCall()).isEmpty();

        // add new metric1 with no measurements
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
        assertThat(writer.getCreatedMeasurementsAfterLastCall()).isEmpty();

        // add metric1 new measurement and new metric2 without measurements
        MetricSnapshot metric2 = mockMetricSnapshot("metric2");
        when(metric2.size()).thenReturn(0);
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());

        MeasurementSnapshot measurement11 = mock(MeasurementSnapshot.class);
        when(measurement11.toString()).thenReturn("measurement11");
        when(metric1.size()).thenReturn(1);
        when(metric1.get(0)).thenReturn(measurement11);

        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                MEASUREMENT: measurement11
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).containsExactlyInAnyOrder("metric2");
        assertThat(writer.getCreatedMeasurementsAfterLastCall()).containsExactlyInAnyOrder("measurement11");

        // add metric1 new measurement and metric2 new measurement
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());
        MeasurementSnapshot measurement12 = mock(MeasurementSnapshot.class);
        when(measurement12.toString()).thenReturn("measurement12");
        when(metric1.size()).thenReturn(2);
        when(metric1.get(1)).thenReturn(measurement12);

        MeasurementSnapshot measurement21 = mock(MeasurementSnapshot.class);
        when(measurement21.toString()).thenReturn("measurement21");
        when(metric2.size()).thenReturn(1);
        when(metric2.get(0)).thenReturn(measurement21);

        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                MEASUREMENT: measurement11
                MEASUREMENT: measurement12
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                MEASUREMENT: measurement21
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedMeasurementsAfterLastCall())
                .containsExactlyInAnyOrder("measurement12", "measurement21");

        // export again everything should be cached
        when(snapshots.iterator()).thenReturn(List.of(metric1, metric2).iterator());
        writeAndVerify(
                snapshots,
                """
                BEFORE METRIC: metric1
                MEASUREMENT: measurement11
                MEASUREMENT: measurement12
                AFTER METRIC: metric1
                BEFORE METRIC: metric2
                MEASUREMENT: measurement21
                AFTER METRIC: metric2
                """);
        assertThat(writer.getCreatedMetricsAfterLastCall()).isEmpty();
        assertThat(writer.getCreatedMeasurementsAfterLastCall()).isEmpty();
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
        private final List<String> createdMeasurements = new ArrayList<>();

        protected TestAbstractCachingMetricsSnapshotsWriter(TestBuilder builder) {
            super(builder);
        }

        public List<String> getCreatedMetricsAfterLastCall() {
            ArrayList<String> result = new ArrayList<>(createdMetrics);
            createdMetrics.clear();
            return result;
        }

        public List<String> getCreatedMeasurementsAfterLastCall() {
            ArrayList<String> result = new ArrayList<>(createdMeasurements);
            createdMeasurements.clear();
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
        protected void writeMeasurement(
                @NonNull Instant timestamp,
                @NonNull MeasurementSnapshot measurementSnapshot,
                @NonNull ByteArrayTemplate measurementExportTemplate,
                @NonNull OutputStream output)
                throws IOException {
            output.write(measurementExportTemplate.iterator().next());
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
            protected ByteArrayTemplate buildMeasurementExportTemplate(MeasurementSnapshot measurementSnapshot) {
                createdMeasurements.add(measurementSnapshot.toString());
                return ByteArrayTemplate.builder()
                        .appendUtf8("MEASUREMENT: " + measurementSnapshot + "\n")
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
