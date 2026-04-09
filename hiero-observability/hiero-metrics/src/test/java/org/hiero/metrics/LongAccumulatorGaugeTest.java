// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.LongBinaryOperator;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetricBaseTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LongAccumulatorGaugeTest
        extends SettableMetricBaseTest<LongAccumulatorGauge, LongAccumulatorGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected LongAccumulatorGauge.Builder emptyMetricBuilder(String name) {
        return LongAccumulatorGauge.builder(LongAccumulatorGauge.key(name), Long::min);
    }

    private LongAccumulatorGauge.Builder builder(LongBinaryOperator operator) {
        return LongAccumulatorGauge.builder(LongAccumulatorGauge.key(DEFAULT_NAME), operator);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> LongAccumulatorGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> LongAccumulatorGauge.builder(null, Long::min))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Nested
    class BuilderTests extends MetricBaseTest<LongAccumulatorGauge, LongAccumulatorGauge.Builder>.BuilderTests {

        @Test
        void testNullOperatorThrows() {
            assertThatThrownBy(() -> builder(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("operator must not be null");
        }
    }

    @Nested
    class ModifyMeasurementsTests {

        @Test
        void testDefaultInitialValueNoLabels() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1).build();

            metric.getOrCreateNotLabeled().accumulate(2); // 0 + 2 + 1 = 3
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            LongAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1).addDynamicLabelNames("label").build();

            metric.getOrCreateLabeled("label", "1").accumulate(2); // 0.0 + 2 + 1 = 3
            metric.getOrCreateLabeled(() -> 100, "label", "2").accumulate(3); // 100 + 3 + 1 = 104

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(3);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(3);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(104);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            LongAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1).setDefaultInitValue(10).build();

            metric.getOrCreateNotLabeled().accumulate(2); // 10 + 2 + 1
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(13);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1)
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10)
                    .build();

            metric.getOrCreateLabeled("label", "1").accumulate(2); // 10 + 2 + 1 = 13
            metric.getOrCreateLabeled(() -> 100, "label", "2").accumulate(3); // 100 + 3 + 1 = 104

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(13);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(13);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(104);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(10);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100);
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, 0, -123456789, 123456789})
        void testCustomInitializerNoLabels(long initialValue) {
            LongAccumulatorGauge metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, 0, -123456789, 123456789})
        void testCustomInitialValueWithLabels(long initialValue) {
            LongAccumulatorGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(initialValue)
                    .build();

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(initialValue);
            assertThat(metric.getOrCreateLabeled(() -> 1000, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(initialValue);
        }

        @Test
        void testMinBuilderNoLabels() {
            LongAccumulatorGauge metric = LongAccumulatorGauge.minBuilder(LongAccumulatorGauge.key(DEFAULT_NAME))
                    .build();

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(Long.MAX_VALUE);

            metric.getOrCreateNotLabeled().accumulate(5);
            metric.getOrCreateNotLabeled().accumulate(3);
            metric.getOrCreateNotLabeled().accumulate(4);

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3);
        }

        @Test
        void testMaxBuilderNoLabels() {
            LongAccumulatorGauge metric = LongAccumulatorGauge.maxBuilder(LongAccumulatorGauge.key(DEFAULT_NAME))
                    .build();

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(Long.MIN_VALUE);

            metric.getOrCreateNotLabeled().accumulate(5);
            metric.getOrCreateNotLabeled().accumulate(7);
            metric.getOrCreateNotLabeled().accumulate(6);

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(7);
        }

        @Test
        void testConcurrentMeasurementModification() throws InterruptedException {
            LongAccumulatorGauge metric = builder(Long::sum).build();
            LongAccumulatorGauge.Measurement measurement = metric.getOrCreateNotLabeled();

            final int threadCount = 10;
            final int updatesPerThread = 10000;

            ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < updatesPerThread; i++) {
                    measurement.accumulate(1);
                }
            });

            assertThat(measurement.get()).isEqualTo(threadCount * updatesPerThread);
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<LongAccumulatorGauge, LongAccumulatorGauge.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabelsNoReset() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0).verify();

            metric.getOrCreateNotLabeled().accumulate(1); // 0 + 1 + 1 = 2
            metric.getOrCreateNotLabeled().accumulate(2); // 2 + 2 + 1 = 5

            new MetricSnapshotVerifier(metric).add(5).verify();
            new MetricSnapshotVerifier(metric).add(5).verify(); // still the same value

            metric.getOrCreateNotLabeled().accumulate(1); // 5 + 1 + 1 = 7

            new MetricSnapshotVerifier(metric).add(7).verify();
        }

        @Test
        void testSnapshotNoLabelsWithResetToDefaultInitialValue() {
            LongAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1).resetOnExport().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled().accumulate(1); // 0 + 1 + 1 = 2
            metric.getOrCreateNotLabeled().accumulate(2); // 2 + 2 + 1 = 5

            new MetricSnapshotVerifier(metric).add(5).verify();
            new MetricSnapshotVerifier(metric).add(0).verify(); // reset to default initial value

            metric.getOrCreateNotLabeled().accumulate(3); // 0 + 3 + 1 = 4

            new MetricSnapshotVerifier(metric).add(4).verify();
        }

        @Test
        void testSnapshotNoLabelsWithResetToCustomInitialValue() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1)
                    .resetOnExport()
                    .setDefaultInitValue(10)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10).verify();

            metric.getOrCreateNotLabeled().accumulate(1); // 10 + 1 + 1 = 12
            metric.getOrCreateNotLabeled().accumulate(2); // 12 + 2 + 1 = 15

            new MetricSnapshotVerifier(metric).add(15).verify();
            new MetricSnapshotVerifier(metric).add(10).verify(); // reset to custom initial value

            metric.getOrCreateNotLabeled().accumulate(3); // 10 + 3 + 1 = 14

            new MetricSnapshotVerifier(metric).add(14).verify();
        }

        @Test
        void testSnapshotWithLabelsNoReset() {
            LongAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1).addDynamicLabelNames("label1").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2); // 0 + 2 + 1 = 3
            metric.getOrCreateLabeled(() -> 10, "label1", "2").accumulate(1); // 10 + 1 + 1 = 12

            new MetricSnapshotVerifier(metric)
                    .add(3, "label1", "1")
                    .add(12, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(3, "label1", "1")
                    .add(12, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").accumulate(3); // 12 + 3 + 1 = 16
            metric.getOrCreateLabeled("label1", "3").accumulate(10); // 0 + 10 + 1 = 11

            new MetricSnapshotVerifier(metric)
                    .add(3, "label1", "1") // unchanged
                    .add(16, "label1", "2")
                    .add(11, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToDefaultInitialValue() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1)
                    .addDynamicLabelNames("label1")
                    .resetOnExport()
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 10, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2); // 0 + 2 + 1 = 3
            metric.getOrCreateLabeled(() -> 100, "label1", "2").accumulate(1); // 10 + 1 + 1 = 12

            new MetricSnapshotVerifier(metric)
                    .add(12, "label1", "2")
                    .add(3, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(10, "label1", "2")
                    .add(0, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").accumulate(3); // 10 + 3 + 1 = 14
            metric.getOrCreateLabeled("label1", "3").accumulate(10); // 0 + 10 + 1 = 11

            new MetricSnapshotVerifier(metric)
                    .add(14, "label1", "2")
                    .add(0, "label1", "1") // unchanged
                    .add(11, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            LongAccumulatorGauge metric = builder((l, r) -> l + r + 1)
                    .addDynamicLabelNames("label1")
                    .resetOnExport()
                    .setDefaultInitValue(10)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2); // 10 + 2 + 1 = 13
            metric.getOrCreateLabeled(() -> 100, "label1", "2").accumulate(1); // 100 + 1 + 1 = 102

            new MetricSnapshotVerifier(metric)
                    .add(13, "label1", "1")
                    .add(102, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(10, "label1", "1")
                    .add(100, "label1", "2")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").accumulate(3); // 100 + 3 + 1 = 104
            metric.getOrCreateLabeled("label1", "3").accumulate(42); // 10 + 42 + 1 = 53

            new MetricSnapshotVerifier(metric)
                    .add(10, "label1", "1") // unchanged
                    .add(104, "label1", "2")
                    .add(53, "label1", "3")
                    .verify();
        }
    }
}
