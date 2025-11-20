// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.hiero.metrics.TestWriterContext;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.GenericGauge;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.NumberSupplier;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.api.stat.container.AtomicDouble;
import org.hiero.metrics.api.utils.Unit;
import org.hiero.metrics.test.fixtures.MetricsSnapshotProvider;
import org.hiero.metrics.test.fixtures.SateSetEnum;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OpenMetricsSnapshotsWriterTest {

    @Test
    void testEscapeDescription() throws IOException {
        TestWriterContext context = new TestWriterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        LongCounter.builder("test_metric")
                .withDescription("Metric with special characters: \n Newline, \t Tab, \" Double Quote, \\ Backslash"
                        + " \\n Escaped Newline, \\t Escaped Tab, \\\" Escaped Double Quote, \\\\ Escaped Backslash"
                        + " 測試指標, тестовый_метрика, prueba_métrica")
                .register(context.getRegistry());

        context.exportAndVerify(
                """
                # TYPE test_metric counter
                # HELP test_metric Metric with special characters: \\n Newline, 	 Tab, \\" Double Quote, \\\\ Backslash \\\\n Escaped Newline, \\\\t Escaped Tab, \\\\\\" Escaped Double Quote, \\\\\\\\ Escaped Backslash 測試指標, тестовый_метрика, prueba_métrica
                # EOF
                """);
    }

    @ParameterizedTest
    @MethodSource("doubleFormattingArguments")
    void testDoubleFormatting(double value, String expectedRepresentation) throws IOException {
        TestWriterContext context = new TestWriterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        DoubleGauge.builder("double_metric")
                .register(context.getRegistry())
                .getOrCreateNotLabeled()
                .update(value);

        context.exportAndVerify(String.format(
                """
                # TYPE double_metric gauge
                double_metric %s
                # EOF
                """,
                expectedRepresentation));
    }

    private static Stream<Arguments> doubleFormattingArguments() {
        return Stream.of(
                Arguments.of(Double.NaN, "NaN"),
                Arguments.of(Double.POSITIVE_INFINITY, "+Inf"),
                Arguments.of(Double.NEGATIVE_INFINITY, "-Inf"),
                Arguments.of(123.456, "123.456"));
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RealMetricsFlow {

        static final MetricsSnapshotProvider snapshotProvider = new MetricsSnapshotProvider();
        static final OpenMetricsSnapshotsWriter writer = OpenMetricsSnapshotsWriter.DEFAULT;

        static BooleanGauge booleanGauge;
        static LongCounter longCounter;
        static DoubleCounter doubleCounter;
        static LongGauge longGaugeMax;
        static DoubleGauge doubleGaugeMin;
        static GenericGauge<Duration> durationGauge;
        static StateSet<SateSetEnum> stateSet;
        static StatsGaugeAdapter<IntSupplier, StatContainer> statsGauge;
        static GaugeAdapter<IntSupplier, StatContainer> gaugeAdapter;
        static StatelessMetric statelessMetric;

        static final AtomicLong longContainer = new AtomicLong(0);
        static final AtomicDouble doubleContainer = new AtomicDouble(0);

        @Test
        @Order(1)
        void testEmptyRegistry() throws IOException {
            exportAndVerify("""
                # EOF
                """);
        }

        @Test
        @Order(2)
        void testInitNotObservations() throws IOException {
            booleanGauge = BooleanGauge.builder("boolean_gauge")
                    .withDescription("Boolean gauge")
                    .register(snapshotProvider.getRegistry());

            longCounter = LongCounter.builder("long_counter")
                    .withDescription("A test counter")
                    .withUnit("requests")
                    .withConstantLabel(new Label("env", "test"))
                    .withDynamicLabelNames("l1", "l2")
                    .register(snapshotProvider.getRegistry());

            doubleCounter = DoubleCounter.builder("double_counter").register(snapshotProvider.getRegistry());

            longGaugeMax =
                    LongGauge.builder("long_gauge_max").withTrackingMaxSpike().register(snapshotProvider.getRegistry());

            doubleGaugeMin = DoubleGauge.builder("double_gauge_min")
                    .withDynamicLabelNames("label")
                    .withTrackingMinSpike()
                    .register(snapshotProvider.getRegistry());

            durationGauge = GenericGauge.builder(
                            "duration_gauge", GenericGauge.durationToLongFunction(ChronoUnit.SECONDS))
                    .register(snapshotProvider.getRegistry());

            stateSet = StateSet.builder("state_set", SateSetEnum.class)
                    .withConstantLabel(new Label("env", "test"))
                    .register(snapshotProvider.getRegistry());

            gaugeAdapter = GaugeAdapter.builder(
                            "gauge_adapter",
                            StatUtils.INT_INIT,
                            StatContainer::new,
                            new ToNumberFunction<>(StatContainer::getCounter))
                    .register(snapshotProvider.getRegistry());

            statsGauge = StatContainer.metricBuilder("stats_gauge")
                    .withDynamicLabelNames("label")
                    .register(snapshotProvider.getRegistry());

            statelessMetric = StatelessMetric.builder("stateless_metric")
                    .withUnit(Unit.BYTE_UNIT)
                    .withDynamicLabelNames("memory")
                    .registerDataPoint(longContainer::get, "memory", "total")
                    .register(snapshotProvider.getRegistry());

            exportAndVerify(
                    """
                # TYPE boolean_gauge gauge
                # HELP boolean_gauge Boolean gauge
                # TYPE long_counter_requests counter
                # UNIT long_counter_requests requests
                # HELP long_counter_requests A test counter
                # TYPE double_counter counter
                # TYPE long_gauge_max gauge
                # TYPE double_gauge_min gauge
                # TYPE duration_gauge gauge
                # TYPE state_set stateset
                # TYPE gauge_adapter gauge
                # TYPE stats_gauge gauge
                # TYPE stateless_metric_byte gauge
                # UNIT stateless_metric_byte byte
                stateless_metric_byte{memory="total"} 0
                # EOF
                """);
        }

        @Test
        @Order(3)
        void testUpdateMetrics1() throws IOException {
            booleanGauge.getOrCreateNotLabeled().setTrue();

            longCounter.getOrCreateLabeled("l1", "v11", "l2", "v21").increment(10L);
            longCounter.getOrCreateLabeled("l1", "v11", "l2", "v21").increment(); // total is 11

            doubleCounter.getOrCreateNotLabeled().increment(0.5);
            doubleCounter.getOrCreateNotLabeled().increment(); // result is 1.5

            longGaugeMax.getOrCreateNotLabeled().update(10L);
            longGaugeMax.getOrCreateNotLabeled().update(5L); // max is still 10

            // no double gauge update

            durationGauge.getOrCreateNotLabeled().update(Duration.ofMillis(700)); // should be 0 in seconds

            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_TWO);
            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_FIVE);

            gaugeAdapter.getOrCreateNotLabeled().update(10); // counter should be 1

            statsGauge.getOrCreateLabeled("label", "val1").update(5);
            statsGauge.getOrCreateLabeled("label", "val1").update(4);

            // update long container
            longContainer.set(100L);
            // register new data point
            statelessMetric.registerDataPoint(new NumberSupplier(doubleContainer), "memory", "used");

            exportAndVerify(
                    """
                # TYPE boolean_gauge gauge
                # HELP boolean_gauge Boolean gauge
                boolean_gauge 1
                # TYPE long_counter_requests counter
                # UNIT long_counter_requests requests
                # HELP long_counter_requests A test counter
                long_counter_requests_total{env="test",l1="v11",l2="v21"} 11
                # TYPE double_counter counter
                double_counter_total 1.5
                # TYPE long_gauge_max gauge
                long_gauge_max 10
                # TYPE double_gauge_min gauge
                # TYPE duration_gauge gauge
                duration_gauge 0
                # TYPE state_set stateset
                state_set{env="test",state_set="STATE_ONE"} 0
                state_set{env="test",state_set="STATE_TWO"} 1
                state_set{env="test",state_set="STATE_THREE"} 0
                state_set{env="test",state_set="STATE_FOUR"} 0
                state_set{env="test",state_set="STATE_FIVE"} 1
                # TYPE gauge_adapter gauge
                gauge_adapter 1
                # TYPE stats_gauge gauge
                stats_gauge{label="val1",stat="cnt"} 2
                stats_gauge{label="val1",stat="sum"} 9
                stats_gauge{label="val1",stat="avg"} 4.5
                # TYPE stateless_metric_byte gauge
                # UNIT stateless_metric_byte byte
                stateless_metric_byte{memory="total"} 100
                stateless_metric_byte{memory="used"} 0
                # EOF
                """);
        }

        @Test
        @Order(4)
        void testUpdateMetrics2() throws IOException {
            booleanGauge.getOrCreateNotLabeled().set(false); // back to false

            longCounter.getOrCreateLabeled("l1", "v11", "l2", "v21").increment(); // total is 12
            longCounter.getOrCreateLabeled("l1", "v12", "l2", "v22").increment(100L); // new data point
            longCounter.getOrCreateLabeled("l1", "v12", "l2", "v22").increment(200L); // total is 300

            // no update to double counter, should remain 1.5

            longGaugeMax.getOrCreateNotLabeled().update(11); // max is now 11

            doubleGaugeMin.getOrCreateLabeled("label", "value1").update(10.5);
            doubleGaugeMin.getOrCreateLabeled("label", "value1").update(Double.POSITIVE_INFINITY); // min is 10.5
            doubleGaugeMin.getOrCreateLabeled("label", "value2").update(Double.NEGATIVE_INFINITY);

            durationGauge.getOrCreateNotLabeled().update(Duration.ofMillis(2400)); // should be 2 in seconds

            stateSet.getOrCreateNotLabeled().setFalse(SateSetEnum.STATE_TWO);
            stateSet.getOrCreateNotLabeled().setTrue(SateSetEnum.STATE_ONE);

            gaugeAdapter.getOrCreateNotLabeled().update(10);
            gaugeAdapter.getOrCreateNotLabeled().update(100); // counter should be 3

            statsGauge.getOrCreateLabeled("label", "val2").update(5); // new data point

            doubleContainer.set(7.5);

            exportAndVerify(
                    """
                # TYPE boolean_gauge gauge
                # HELP boolean_gauge Boolean gauge
                boolean_gauge 0
                # TYPE long_counter_requests counter
                # UNIT long_counter_requests requests
                # HELP long_counter_requests A test counter
                long_counter_requests_total{env="test",l1="v11",l2="v21"} 12
                long_counter_requests_total{env="test",l1="v12",l2="v22"} 300
                # TYPE double_counter counter
                double_counter_total 1.5
                # TYPE long_gauge_max gauge
                long_gauge_max 11
                # TYPE double_gauge_min gauge
                double_gauge_min{label="value1"} 10.5
                double_gauge_min{label="value2"} -Inf
                # TYPE duration_gauge gauge
                duration_gauge 2
                # TYPE state_set stateset
                state_set{env="test",state_set="STATE_ONE"} 1
                state_set{env="test",state_set="STATE_TWO"} 0
                state_set{env="test",state_set="STATE_THREE"} 0
                state_set{env="test",state_set="STATE_FOUR"} 0
                state_set{env="test",state_set="STATE_FIVE"} 1
                # TYPE gauge_adapter gauge
                gauge_adapter 3
                # TYPE stats_gauge gauge
                stats_gauge{label="val1",stat="cnt"} 2
                stats_gauge{label="val1",stat="sum"} 9
                stats_gauge{label="val1",stat="avg"} 4.5
                stats_gauge{label="val2",stat="cnt"} 1
                stats_gauge{label="val2",stat="sum"} 5
                stats_gauge{label="val2",stat="avg"} 5
                # TYPE stateless_metric_byte gauge
                # UNIT stateless_metric_byte byte
                stateless_metric_byte{memory="total"} 100
                stateless_metric_byte{memory="used"} 7.5
                # EOF
                """);
        }

        private void exportAndVerify(String expected) throws IOException {
            UnsynchronizedByteArrayOutputStream outputStream = new UnsynchronizedByteArrayOutputStream();
            writer.write(snapshotProvider.get(), outputStream);
            assertThat(new ByteArrayInputStream(outputStream.toByteArray())).hasContent(expected);
        }
    }
}
