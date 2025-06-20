// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_3_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.config.BasicCommonConfig_;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.config.MetricsConfig_;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Counter.Config;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.api.snapshot.SnapshotableMetric;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyCsvWriterTest {

    private static final NodeId NODE_ID = NodeId.of(42L);
    private Metrics metrics;
    private MetricsConfig metricsConfig;
    private Configuration configuration;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setStandardSettings() {
        configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.CSV_OUTPUT_FOLDER, tempDir.toString())
                .withValue(MetricsConfig_.CSV_APPEND, "false")
                .withValue(BasicCommonConfig_.SHOW_INTERNAL_STATS, "false")
                .withValue(BasicCommonConfig_.VERBOSE_STATISTICS, "false")
                .getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);

        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        metrics = new DefaultPlatformMetrics(
                NODE_ID,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    @Test
    void testToString() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);

        // then
        assertThat(writer.toString()).matches("^LegacyCsvWriter\\[csvFilePath=" + tempDir + ".*]$");
    }

    @Test
    void testParentFolderCreation() throws IOException {
        // given
        final Path grandParentPath = Files.createTempDirectory(tempDir, null);
        final Path parentPath = Files.createTempDirectory(grandParentPath, null);
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, parentPath, configuration);
        final Path csvFilePath = writer.getCsvFilePath();

        Files.deleteIfExists(csvFilePath);
        Files.delete(parentPath);
        Files.delete(grandParentPath);
        final List<Metric> metrics = createShortList();
        final List<Snapshot> snapshots = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification = new SnapshotEvent(NODE_ID, snapshots);

        // when
        writer.handleSnapshots(notification);

        // then
        assertThat(grandParentPath).exists();
        assertThat(parentPath).exists();
        assertThat(csvFilePath).exists();
    }

    @Test
    void testWriteDefault() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createCompleteList();
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);

        // when
        writer.handleSnapshots(notification1);

        // update metrics
        ((Counter) metrics.get(0)).increment();
        ((DoubleGauge) metrics.get(1)).set(Math.PI);
        ((IntegerAccumulator) metrics.get(3)).update(42);
        ((IntegerGauge) metrics.get(4)).set(42);
        ((IntegerPairAccumulator<Double>) metrics.get(5)).update(4711, 42);
        ((LongAccumulator) metrics.get(6)).update(42L);
        ((LongGauge) metrics.get(7)).set(4711L);
        ((RunningAverageMetric) metrics.get(8)).update(1000.0);
        ((SpeedometerMetric) metrics.get(9)).update(10);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);

        // when
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Counter:,Counter,
                                DoubleGauge:,DoubleGauge,
                                FunctionGauge:,FunctionGauge,
                                IntegerAccumulator:,IntegerAccumulator,
                                IntegerGauge:,IntegerGauge,
                                IntegerPairAccumulator:,IntegerPairAccumulator,
                                LongAccumulator:,LongAccumulator,
                                LongGauge:,LongGauge,
                                RunningAverageMetric:,RunningAverageMetric,
                                SpeedometerMetric:,SpeedometerMetric,
                                StatEntry:,StatEntry,

                                ,,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,
                                ,,Counter,DoubleGauge,FunctionGauge,IntegerAccumulator,IntegerGauge,IntegerPairAccumulator,LongAccumulator,LongGauge,RunningAverageMetric,SpeedometerMetric,StatEntry,
                                ,,0,0\\.0,Hello FunctionGauge,0,0,0.0,0,0,0\\.0,0\\.0,Hello StatEntry,
                                ,,1,3\\.1,Hello FunctionGauge,42,42,112\\.2,42,4711,1000\\.0,\\d*\\.\\d,Hello StatEntry,
                                """);
    }

    @Test
    void testWritingOfSpecialValues() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createShortList();
        final DoubleGauge gauge = (DoubleGauge) metrics.get(1);

        // when
        gauge.set(Double.NaN);
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);
        writer.handleSnapshots(notification1);
        gauge.set(Double.POSITIVE_INFINITY);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);
        writer.handleSnapshots(notification2);
        gauge.set(Double.NEGATIVE_INFINITY);
        final List<Snapshot> snapshots3 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification3 = new SnapshotEvent(NODE_ID, snapshots3);
        writer.handleSnapshots(notification3);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                (.*\\n){5}.*
                                ,,0,0.0,
                                ,,0,0.0,
                                ,,0,0.0,
                                """);
    }

    @Test
    void testWriteWithExistingFile() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        Files.writeString(csvFilePath, "Hello World");
        final List<Metric> metrics = createShortList();
        final List<Snapshot> snapshots = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification = new SnapshotEvent(NODE_ID, snapshots);

        // when
        writer.handleSnapshots(notification);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Counter:,Counter,
                                DoubleGauge:,DoubleGauge,

                                ,,platform,platform,
                                ,,Counter,DoubleGauge,
                                ,,0,0.0,
                                """);
    }

    @Test
    void testWriteWithAppendedModeAndExistingFile() throws IOException {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.CSV_OUTPUT_FOLDER, tempDir.toString())
                .withValue(MetricsConfig_.CSV_APPEND, "true")
                .getOrCreateConfig();
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        Files.writeString(
                csvFilePath,
                """
                        filename:,/tmp/tempfile.tmp,
                        Counter:,Counter,
                        DoubleGauge:,DoubleGauge,

                        ,,platform,platform,
                        ,,Counter,DoubleGauge,
                        ,,1,2.0,
                        ,,11,12.0,
                        """);
        final List<Metric> metrics = createShortList();
        final List<Snapshot> snapshots = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification = new SnapshotEvent(NODE_ID, snapshots);

        // when
        writer.handleSnapshots(notification);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Counter:,Counter,
                                DoubleGauge:,DoubleGauge,

                                ,,platform,platform,
                                ,,Counter,DoubleGauge,
                                ,,1,2.0,
                                ,,11,12.0,
                                				
                                				
                                ,,0,0.0,
                                """);
    }

    @Test
    void testWriteWithAppendedModeAndNonExistingFile() throws IOException {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.CSV_OUTPUT_FOLDER, tempDir.toString())
                .withValue(MetricsConfig_.CSV_APPEND, "true")
                .getOrCreateConfig();
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        Files.deleteIfExists(csvFilePath);
        final List<Metric> metrics = createShortList();
        final List<Snapshot> snapshots = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification = new SnapshotEvent(NODE_ID, snapshots);

        // when
        writer.handleSnapshots(notification);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Counter:,Counter,
                                DoubleGauge:,DoubleGauge,

                                ,,platform,platform,
                                ,,Counter,DoubleGauge,
                                ,,0,0.0,
                                """);
    }

    @Test
    void testWriteWithInternalIgnored() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createListWithInternals();
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);

        // when
        writer.handleSnapshots(notification1);

        // update metrics
        ((Counter) metrics.get(0)).add(2);
        ((Counter) metrics.get(1)).add(3);
        ((DoubleGauge) metrics.get(2)).set(Math.PI);
        ((DoubleGauge) metrics.get(3)).set(Math.E);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);

        // when
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Public Counter:,Public Counter,
                                Public DoubleGauge:,Public DoubleGauge,

                                ,,platform,platform,
                                ,,Public Counter,Public DoubleGauge,
                                ,,0,0.0,
                                ,,3,2.7,
                                """);
    }

    @Test
    void testWriteWithInternalNotIgnored() throws IOException {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(BasicCommonConfig_.SHOW_INTERNAL_STATS, "true")
                .getOrCreateConfig();
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createListWithInternals();
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);

        // when
        writer.handleSnapshots(notification1);

        // update metrics
        ((Counter) metrics.get(0)).add(2);
        ((Counter) metrics.get(1)).add(3);
        ((DoubleGauge) metrics.get(2)).set(Math.PI);
        ((DoubleGauge) metrics.get(3)).set(Math.E);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);

        // when
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Internal Counter:,Internal Counter,
                                Public Counter:,Public Counter,
                                Internal DoubleGauge:,Internal DoubleGauge,
                                Public DoubleGauge:,Public DoubleGauge,

                                ,,internal,platform,internal,platform,
                                ,,Internal Counter,Public Counter,Internal DoubleGauge,Public DoubleGauge,
                                ,,0,0,0.0,0.0,
                                ,,2,3,3.1,2.7,
                                """);
    }

    @Test
    void testWriteWithSecondaryValuesNotIncluded() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createListWithSecondaryValues();
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);

        // when
        writer.handleSnapshots(notification1);

        // update metrics
        ((RunningAverageMetric) metrics.get(0)).update(1000.0);
        ((SpeedometerMetric) metrics.get(1)).update(2000.0);
        ((RunningAverageMetric) metrics.get(2)).update(3000.0);
        ((SpeedometerMetric) metrics.get(3)).update(4000.0);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);

        // when
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                RunningAverageMetric:,RunningAverageMetric,
                                SpeedometerMetric:,SpeedometerMetric,
                                RunningAverageMetric Info:,RunningAverageMetric Info,
                                SpeedometerMetric Info:,SpeedometerMetric Info,

                                ,,platform,platform,platform:info,platform:info,
                                ,,RunningAverageMetric,SpeedometerMetric,RunningAverageMetric Info,SpeedometerMetric Info,
                                ,,0\\.0,0\\.0,0\\.0,0\\.0,
                                ,,1000\\.0,\\d*\\.\\d,3000\\.0,\\d*\\.\\d,
                                """);
    }

    @Test
    void testWriteWithSecondaryValuesIncluded() throws IOException {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(BasicCommonConfig_.VERBOSE_STATISTICS, "true")
                .getOrCreateConfig();
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createListWithSecondaryValues();
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);

        // when
        writer.handleSnapshots(notification1);

        // update metrics
        ((RunningAverageMetric) metrics.get(0)).update(1000.0);
        ((SpeedometerMetric) metrics.get(1)).update(2000.0);
        ((RunningAverageMetric) metrics.get(2)).update(3000.0);
        ((SpeedometerMetric) metrics.get(3)).update(4000.0);
        final List<Snapshot> snapshots2 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);

        // when
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                RunningAverageMetric:,RunningAverageMetric,
                                SpeedometerMetric:,SpeedometerMetric,
                                RunningAverageMetric Info:,RunningAverageMetric Info,
                                SpeedometerMetric Info:,SpeedometerMetric Info,

                                ,,platform,platform,platform,platform,platform,platform,platform,platform,platform:info,platform:info,
                                ,,RunningAverageMetric,RunningAverageMetricMax,RunningAverageMetricMin,RunningAverageMetricStd,SpeedometerMetric,SpeedometerMetricMax,SpeedometerMetricMin,SpeedometerMetricStd,RunningAverageMetric Info,SpeedometerMetric Info,
                                ,,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,0\\.0,
                                ,,1000\\.0,1000\\.0,1000\\.0,0\\.0,\\d*\\.\\d,\\d*\\.\\d,\\d*\\.\\d,0\\.0,3000\\.0,\\d*\\.\\d,
                                """);
    }

    @Test
    void testBrokenFormatString() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final DoubleGauge gauge = metrics.getOrCreate(new DoubleGauge.Config(Metrics.PLATFORM_CATEGORY, "DoubleGauge")
                .withFormat("%d")
                .withInitialValue(Math.PI));
        final Snapshot snapshot = Snapshot.of((SnapshotableMetric) gauge);
        final SnapshotEvent notification = new SnapshotEvent(NODE_ID, List.of(snapshot));

        // when
        writer.handleSnapshots(notification);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content).matches("""
                (.*\\n){4}.*
                ,,,
                """);
    }

    @Test
    void testChangedEntriesWithSimpleMetrics() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createSimpleList();

        // when

        // first row
        ((Counter) metrics.get(1)).add(1L);
        ((Counter) metrics.get(2)).add(2L);
        ((Counter) metrics.get(3)).add(3L);
        ((Counter) metrics.get(4)).add(4L);
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);
        writer.handleSnapshots(notification1);

        // second row
        ((Counter) metrics.get(1)).add(10L);
        ((Counter) metrics.get(3)).add(30L);
        final List<Snapshot> snapshots2 = List.of(
                Snapshot.of((SnapshotableMetric) metrics.get(3)), Snapshot.of((SnapshotableMetric) metrics.get(1)));
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                Counter 1:,Counter 1,
                                Counter 2:,Counter 2,
                                Counter 3:,Counter 3,
                                Counter 4:,Counter 4,
                                Counter 5:,Counter 5,

                                ,,platform,platform,platform,platform,platform,
                                ,,Counter 1,Counter 2,Counter 3,Counter 4,Counter 5,
                                ,,0,1,2,3,4,
                                ,,,11,,33,,
                                """);
    }

    @Test
    void testChangedEntriesWithComplexMetricsAndNoSecondaryValues() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createComplexList();

        // when

        // first row
        ((RunningAverageMetric) metrics.get(1)).update(1000.0);
        ((RunningAverageMetric) metrics.get(2)).update(2000.0);
        ((RunningAverageMetric) metrics.get(3)).update(3000.0);
        ((RunningAverageMetric) metrics.get(4)).update(4000.0);
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);
        writer.handleSnapshots(notification1);

        // second row
        ((RunningAverageMetric) metrics.get(1)).update(10000.0);
        ((RunningAverageMetric) metrics.get(3)).update(30000.0);
        final List<Snapshot> snapshots2 = List.of(
                Snapshot.of((SnapshotableMetric) metrics.get(3)), Snapshot.of((SnapshotableMetric) metrics.get(1)));
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                RunningAverageMetric 1:,RunningAverageMetric 1,
                                RunningAverageMetric 2:,RunningAverageMetric 2,
                                RunningAverageMetric 3:,RunningAverageMetric 3,
                                RunningAverageMetric 4:,RunningAverageMetric 4,
                                RunningAverageMetric 5:,RunningAverageMetric 5,

                                ,,platform,platform,platform,platform,platform,
                                ,,RunningAverageMetric 1,RunningAverageMetric 2,RunningAverageMetric 3,RunningAverageMetric 4,RunningAverageMetric 5,
                                ,,0\\.0,1000\\.0,2000\\.0,3000\\.0,4000\\.0,
                                ,,,5\\d*\\.\\d,,16\\d*\\.\\d,,
                                """);
    }

    @Test
    void testChangedEntriesWithComplexMetricsAndSecondaryValues() throws IOException {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(BasicCommonConfig_.VERBOSE_STATISTICS, "true")
                .getOrCreateConfig();
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createComplexList();

        // when

        // first row
        ((RunningAverageMetric) metrics.get(1)).update(1000.0);
        ((RunningAverageMetric) metrics.get(2)).update(2000.0);
        ((RunningAverageMetric) metrics.get(3)).update(3000.0);
        ((RunningAverageMetric) metrics.get(4)).update(4000.0);
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        final SnapshotEvent notification1 = new SnapshotEvent(NODE_ID, snapshots1);
        writer.handleSnapshots(notification1);

        // second row
        ((RunningAverageMetric) metrics.get(1)).update(10000.0);
        ((RunningAverageMetric) metrics.get(3)).update(30000.0);
        final List<Snapshot> snapshots2 = List.of(
                Snapshot.of((SnapshotableMetric) metrics.get(3)), Snapshot.of((SnapshotableMetric) metrics.get(1)));
        final SnapshotEvent notification2 = new SnapshotEvent(NODE_ID, snapshots2);
        writer.handleSnapshots(notification2);

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                                filename:,.*,
                                RunningAverageMetric 1:,RunningAverageMetric 1,
                                RunningAverageMetric 2:,RunningAverageMetric 2,
                                RunningAverageMetric 3:,RunningAverageMetric 3,
                                RunningAverageMetric 4:,RunningAverageMetric 4,
                                RunningAverageMetric 5:,RunningAverageMetric 5,

                                ,,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,platform,
                                ,,RunningAverageMetric 1,RunningAverageMetric 1Max,RunningAverageMetric 1Min,RunningAverageMetric 1Std,RunningAverageMetric 2,RunningAverageMetric 2Max,RunningAverageMetric 2Min,RunningAverageMetric 2Std,RunningAverageMetric 3,RunningAverageMetric 3Max,RunningAverageMetric 3Min,RunningAverageMetric 3Std,RunningAverageMetric 4,RunningAverageMetric 4Max,RunningAverageMetric 4Min,RunningAverageMetric 4Std,RunningAverageMetric 5,RunningAverageMetric 5Max,RunningAverageMetric 5Min,RunningAverageMetric 5Std,
                                ,,0\\.0,0\\.0,0\\.0,0\\.0,1000\\.0,1000\\.0,1000\\.0,0\\.0,2000\\.0,2000\\.0,2000\\.0,0\\.0,3000\\.0,3000\\.0,3000\\.0,0\\.0,4000\\.0,4000\\.0,4000\\.0,0\\.0,
                                ,,,,,,5\\d*\\.\\d,5\\d*\\.\\d,1000.0,\\d*\\.\\d,,,,,16\\d*\\.\\d,16\\d*\\.\\d,3000.0,\\d*\\.\\d,,,,,
                                """);
    }

    @Test
    void testAddedMetricsAfterFirstSnapshotAreIgnored() throws IOException {
        // given
        final LegacyCsvWriter writer = new LegacyCsvWriter(NODE_ID, tempDir, configuration);
        final Path csvFilePath = writer.getCsvFilePath();
        final List<Metric> metrics = createSimpleList();

        // when
        final List<Snapshot> snapshots1 = metrics.stream()
                .map(SnapshotableMetric.class::cast)
                .map(Snapshot::of)
                .toList();
        writer.handleSnapshots(new SnapshotEvent(NODE_ID, snapshots1));

        final List<Snapshot> snapshots = List.of(
                Snapshot.of((SnapshotableMetric) this.metrics.getOrCreate(new Config("NewCategory", "NewCounter"))));
        writer.handleSnapshots(new SnapshotEvent(NODE_ID, snapshots));

        // then
        final String content = Files.readString(csvFilePath);
        assertThat(content)
                .matches(
                        """
                        filename:,.*,
                        Counter 1:,Counter 1,
                        Counter 2:,Counter 2,
                        Counter 3:,Counter 3,
                        Counter 4:,Counter 4,
                        Counter 5:,Counter 5,

                        ,,platform,platform,platform,platform,platform,
                        ,,Counter 1,Counter 2,Counter 3,Counter 4,Counter 5,
                        ,,0,0,0,0,0,
                        ,,,,,,,
                        """);
    }

    private List<Metric> createCompleteList() {
        return List.of(
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter")),
                metrics.getOrCreate(
                        new DoubleGauge.Config(Metrics.PLATFORM_CATEGORY, "DoubleGauge").withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new FunctionGauge.Config<>(
                                Metrics.PLATFORM_CATEGORY, "FunctionGauge", String.class, () -> "Hello FunctionGauge")
                        .withFormat("%s")),
                metrics.getOrCreate(new IntegerAccumulator.Config(Metrics.PLATFORM_CATEGORY, "IntegerAccumulator")),
                metrics.getOrCreate(new IntegerGauge.Config(Metrics.PLATFORM_CATEGORY, "IntegerGauge")),
                metrics.getOrCreate(new IntegerPairAccumulator.Config<>(
                                Metrics.PLATFORM_CATEGORY,
                                "IntegerPairAccumulator",
                                Double.class,
                                IntegerPairAccumulator.AVERAGE)
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new LongAccumulator.Config(Metrics.PLATFORM_CATEGORY, "LongAccumulator")),
                metrics.getOrCreate(new LongGauge.Config(Metrics.PLATFORM_CATEGORY, "LongGauge")),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new SpeedometerMetric.Config(Metrics.PLATFORM_CATEGORY, "SpeedometerMetric")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new StatEntry.Config(
                                Metrics.PLATFORM_CATEGORY, "StatEntry", String.class, () -> "Hello StatEntry")
                        .withFormat("%s")));
    }

    private List<Metric> createShortList() {
        return List.of(
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter")),
                metrics.getOrCreate(
                        new DoubleGauge.Config(Metrics.PLATFORM_CATEGORY, "DoubleGauge").withFormat(FORMAT_3_1)));
    }

    private List<Metric> createListWithInternals() {
        return List.of(
                metrics.getOrCreate(new Counter.Config(Metrics.INTERNAL_CATEGORY, "Internal Counter")),
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Public Counter")),
                metrics.getOrCreate(new DoubleGauge.Config(Metrics.INTERNAL_CATEGORY, "Internal DoubleGauge")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new DoubleGauge.Config(Metrics.PLATFORM_CATEGORY, "Public DoubleGauge")
                        .withFormat(FORMAT_3_1)));
    }

    private List<Metric> createListWithSecondaryValues() {
        return List.of(
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new SpeedometerMetric.Config(Metrics.PLATFORM_CATEGORY, "SpeedometerMetric")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.INFO_CATEGORY, "RunningAverageMetric Info")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new SpeedometerMetric.Config(Metrics.INFO_CATEGORY, "SpeedometerMetric Info")
                        .withFormat(FORMAT_3_1)));
    }

    private List<Metric> createSimpleList() {
        return List.of(
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter 1")),
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter 2")),
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter 3")),
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter 4")),
                metrics.getOrCreate(new Counter.Config(Metrics.PLATFORM_CATEGORY, "Counter 5")));
    }

    private List<Metric> createComplexList() {
        return List.of(
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric 1")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric 2")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric 3")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric 4")
                        .withFormat(FORMAT_3_1)),
                metrics.getOrCreate(new RunningAverageMetric.Config(Metrics.PLATFORM_CATEGORY, "RunningAverageMetric 5")
                        .withFormat(FORMAT_3_1)));
    }
}
