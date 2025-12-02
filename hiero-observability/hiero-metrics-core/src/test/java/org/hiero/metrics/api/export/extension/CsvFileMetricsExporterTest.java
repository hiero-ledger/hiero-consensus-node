// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.export.MetricsExportException;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.hiero.metrics.test.fixtures.MetricCollectionSnapshotProvider;
import org.hiero.metrics.test.fixtures.SateSetEnum;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

public class CsvFileMetricsExporterTest {

    @TempDir
    private Path tempDir;

    @Test
    void testNullFilePathThrows() {
        assertThatThrownBy(() -> new CsvFileMetricsExporter("test", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("file path must not be null");
    }

    @Test
    void testHeadersWrittenOnlyOnNewFile() throws IOException {
        Path filePath = tempDir.resolve("non_existing_dir/non_existing_file.csv");
        assertThat(filePath).doesNotExist();

        new CsvFileMetricsExporter("test", filePath);

        assertThat(filePath).exists();
        assertThat(filePath).hasContent("timestamp,metric,unit,value,labels\n");

        // create new exporter with same file path
        new CsvFileMetricsExporter("test", filePath);
        assertThat(filePath).hasContent("timestamp,metric,unit,value,labels\n");
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RealMetrics {

        static final MetricCollectionSnapshotProvider snapshotProvider = new MetricCollectionSnapshotProvider();

        static Path filePath;
        static CsvFileMetricsExporter exporter;

        static LongCounter longCounter;
        static DoubleGauge doubleGauge;
        static StateSet<SateSetEnum> stateSet;
        static StatsGaugeAdapter<StatContainer> statsGauge;
        static Instant timestamp1;

        @BeforeAll
        static void setup(@TempDir Path tmpDir) throws IOException {
            filePath = tmpDir.resolve("test.csv");
            exporter = new CsvFileMetricsExporter("test", filePath);

            longCounter = LongCounter.builder("long_counter")
                    .withDescription("A test counter")
                    .withUnit("counter_unit")
                    .withConstantLabel(new Label("env", "test"))
                    .withDynamicLabelNames("l1", "l2")
                    .register(snapshotProvider.getRegistry());

            doubleGauge = DoubleGauge.builder("double_gauge").register(snapshotProvider.getRegistry());

            stateSet = StateSet.builder("state_set", SateSetEnum.class)
                    .withConstantLabel(new Label("env", "test"))
                    .register(snapshotProvider.getRegistry());

            statsGauge = StatContainer.metricBuilder("stats_gauge")
                    .withDynamicLabelNames("label")
                    .register(snapshotProvider.getRegistry());
        }

        @Test
        @Order(1)
        void testNoMetricsUpdates() throws MetricsExportException {
            MetricsCollectionSnapshot snapshot = snapshotProvider.get();
            exporter.export(snapshot);

            assertThat(filePath).hasContent("timestamp,metric,unit,value,labels\n");
        }

        @Test
        @Order(2)
        void testUpdateMetrics1() throws MetricsExportException {
            longCounter.getOrCreateLabeled("l1", "v11", "l2", "v21").increment(10L);
            // no double gauge update
            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_TWO);
            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_FIVE);
            statsGauge.getOrCreateLabeled("label", "val1").update(5);
            statsGauge.getOrCreateLabeled("label", "val1").update(4);

            MetricsCollectionSnapshot snapshot = snapshotProvider.get();
            timestamp1 = snapshot.createAt();
            exporter.export(snapshot);

            assertThat(filePath)
                    .hasContent(
                            """
               timestamp,metric,unit,value,labels
               {timestamp1},long_counter,counter_unit,10,"env=test,l1=v11,l2=v21"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_ONE"
               {timestamp1},state_set,,1,"env=test,state_set=STATE_TWO"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_THREE"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_FOUR"
               {timestamp1},state_set,,1,"env=test,state_set=STATE_FIVE"
               {timestamp1},stats_gauge,,2,"label=val1,stat=cnt"
               {timestamp1},stats_gauge,,9,"label=val1,stat=sum"
               {timestamp1},stats_gauge,,4.5,"label=val1,stat=avg"
               """
                                    .replace("{timestamp1}", timestamp1.toString()));
        }

        @Test
        @Order(3)
        void testUpdateMetrics2() throws MetricsExportException {
            longCounter.getOrCreateLabeled("l1", "v11", "l2", "v21").increment();
            longCounter.getOrCreateLabeled("l1", "v12", "l2", "v22").increment(100L);
            longCounter.getOrCreateLabeled("l1", "v12", "l2", "v22").increment(200L);
            doubleGauge.getOrCreateNotLabeled().update(10.5);
            doubleGauge.getOrCreateNotLabeled().update(20.5);
            stateSet.getOrCreateNotLabeled().setFalse(SateSetEnum.STATE_TWO);
            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_ONE);
            statsGauge.getOrCreateLabeled("label", "val2").update(5);

            MetricsCollectionSnapshot snapshot = snapshotProvider.get();
            exporter.export(snapshot);

            assertThat(filePath)
                    .hasContent(
                            """
               timestamp,metric,unit,value,labels
               {timestamp1},long_counter,counter_unit,10,"env=test,l1=v11,l2=v21"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_ONE"
               {timestamp1},state_set,,1,"env=test,state_set=STATE_TWO"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_THREE"
               {timestamp1},state_set,,0,"env=test,state_set=STATE_FOUR"
               {timestamp1},state_set,,1,"env=test,state_set=STATE_FIVE"
               {timestamp1},stats_gauge,,2,"label=val1,stat=cnt"
               {timestamp1},stats_gauge,,9,"label=val1,stat=sum"
               {timestamp1},stats_gauge,,4.5,"label=val1,stat=avg"
               {timestamp2},long_counter,counter_unit,11,"env=test,l1=v11,l2=v21"
               {timestamp2},long_counter,counter_unit,300,"env=test,l1=v12,l2=v22"
               {timestamp2},double_gauge,,20.5,""
               {timestamp2},state_set,,1,"env=test,state_set=STATE_ONE"
               {timestamp2},state_set,,0,"env=test,state_set=STATE_TWO"
               {timestamp2},state_set,,0,"env=test,state_set=STATE_THREE"
               {timestamp2},state_set,,0,"env=test,state_set=STATE_FOUR"
               {timestamp2},state_set,,1,"env=test,state_set=STATE_FIVE"
               {timestamp2},stats_gauge,,2,"label=val1,stat=cnt"
               {timestamp2},stats_gauge,,9,"label=val1,stat=sum"
               {timestamp2},stats_gauge,,4.5,"label=val1,stat=avg"
               {timestamp2},stats_gauge,,1,"label=val2,stat=cnt"
               {timestamp2},stats_gauge,,5,"label=val2,stat=sum"
               {timestamp2},stats_gauge,,5,"label=val2,stat=avg"
               """
                                    .replace("{timestamp1}", timestamp1.toString())
                                    .replace("{timestamp2}", snapshot.createAt().toString()));
        }
    }
}
