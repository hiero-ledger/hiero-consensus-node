// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.DoubleBinaryOperator;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetricBaseTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DoubleAccumulatorGaugeTest
        extends SettableMetricBaseTest<DoubleAccumulatorGauge, DoubleAccumulatorGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected DoubleAccumulatorGauge.Builder emptyMetricBuilder(String name) {
        return DoubleAccumulatorGauge.builder(DoubleAccumulatorGauge.key(name), Double::min);
    }

    private DoubleAccumulatorGauge.Builder builder(DoubleBinaryOperator operator) {
        return DoubleAccumulatorGauge.builder(DoubleAccumulatorGauge.key(DEFAULT_NAME), operator);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> DoubleAccumulatorGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> DoubleAccumulatorGauge.builder(null, Double::min))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Nested
    class BuilderTests extends MetricBaseTest<DoubleAccumulatorGauge, DoubleAccumulatorGauge.Builder>.BuilderTests {

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
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5).build();

            metric.getOrCreateNotLabeled().accumulate(2.0); // 0.0 + 2.0 + 1.5 = 3.5
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0.0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            DoubleAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1.5).addDynamicLabelNames("label").build();

            metric.getOrCreateLabeled("label", "1").accumulate(2.0); // 0.0 + 2.0 + 1.5 = 3.5
            metric.getOrCreateLabeled(() -> 100.0, "label", "2").accumulate(2.5); // 100.0 + 2.5 + 1.5 = 104.0

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(3.5);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(3.5);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(104.0);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100.0);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            DoubleAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1.5).setDefaultInitValue(10.0).build();

            metric.getOrCreateNotLabeled().accumulate(2.0); // 10.0 + 2.0 + 1.5
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(13.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10.0);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5)
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10.0)
                    .build();

            metric.getOrCreateLabeled("label", "1").accumulate(2.0); // 10.0 + 2.0 + 1.5
            metric.getOrCreateLabeled(() -> 100.0, "label", "2").accumulate(2.5); // 100.0 + 2.5 + 1.5

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(13.5);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(13.5);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(104.0);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(10.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100.0);
        }

        @ParameterizedTest
        @ValueSource(
                doubles = {
                    Double.MIN_VALUE,
                    Double.MAX_VALUE,
                    0.0,
                    -789.012,
                    789.012,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                })
        void testCustomInitializerNoLabels(double initialValue) {
            DoubleAccumulatorGauge metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(
                doubles = {
                    Double.MIN_VALUE,
                    Double.MAX_VALUE,
                    0.0,
                    -789.012,
                    789.012,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                })
        void testCustomInitialValueWithLabels(double initialValue) {
            DoubleAccumulatorGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(initialValue)
                    .build();

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(initialValue);
            assertThat(metric.getOrCreateLabeled(() -> 1000.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(initialValue);
        }

        @Test
        void testMinBuilderNoLabels() {
            DoubleAccumulatorGauge metric = DoubleAccumulatorGauge.minBuilder(DoubleAccumulatorGauge.key(DEFAULT_NAME))
                    .build();

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(Double.POSITIVE_INFINITY);

            metric.getOrCreateNotLabeled().accumulate(5.0);
            metric.getOrCreateNotLabeled().accumulate(3.0);
            metric.getOrCreateNotLabeled().accumulate(4.0);

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3.0);
        }

        @Test
        void testMaxBuilderNoLabels() {
            DoubleAccumulatorGauge metric = DoubleAccumulatorGauge.maxBuilder(DoubleAccumulatorGauge.key(DEFAULT_NAME))
                    .build();

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(Double.NEGATIVE_INFINITY);

            metric.getOrCreateNotLabeled().accumulate(5.0);
            metric.getOrCreateNotLabeled().accumulate(7.0);
            metric.getOrCreateNotLabeled().accumulate(6.0);

            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(7.0);
        }

        @Test
        void testConcurrentMeasurementModification() throws InterruptedException {
            DoubleAccumulatorGauge metric = builder(Double::sum).build();
            DoubleAccumulatorGauge.Measurement measurement = metric.getOrCreateNotLabeled();

            final int threadCount = 10;
            final int updatesPerThread = 10000;

            ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < updatesPerThread; i++) {
                    measurement.accumulate(1.0);
                }
            });

            assertThat(measurement.get()).isEqualTo(threadCount * updatesPerThread);
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<DoubleAccumulatorGauge, DoubleAccumulatorGauge.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabelsNoReset() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0).verify();

            metric.getOrCreateNotLabeled().accumulate(1.0); // 0.0 + 1.0 + 1.5 = 2.5
            metric.getOrCreateNotLabeled().accumulate(1.1); // 2.5 + 1.1 + 1.5 = 5.1

            new MetricSnapshotVerifier(metric).add(5.1).verify();
            new MetricSnapshotVerifier(metric).add(5.1).verify(); // still the same value

            metric.getOrCreateNotLabeled().accumulate(1.9); // 5.1 + 1.9 + 1.5 = 8.5

            new MetricSnapshotVerifier(metric).add(8.5).verify();
        }

        @Test
        void testSnapshotNoLabelsWithResetToDefaultInitialValue() {
            DoubleAccumulatorGauge metric =
                    builder((l, r) -> l + r + 1.5).resetOnExport().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled().accumulate(1.0); // 0.0 + 1.0 + 1.5 = 2.5
            metric.getOrCreateNotLabeled().accumulate(1.1); // 2.5 + 1.1 + 1.5 = 5.1

            new MetricSnapshotVerifier(metric).add(5.1).verify();
            new MetricSnapshotVerifier(metric).add(0.0).verify(); // reset to default initial value

            metric.getOrCreateNotLabeled().accumulate(1.1); // 0.0 + 1.1 + 1.5 = 2.6

            new MetricSnapshotVerifier(metric).add(2.6).verify();
        }

        @Test
        void testSnapshotNoLabelsWithResetToCustomInitialValue() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5)
                    .resetOnExport()
                    .setDefaultInitValue(10.0)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10.0).verify();

            metric.getOrCreateNotLabeled().accumulate(1.0); // 10.0 + 1.0 + 1.5 = 12.5
            metric.getOrCreateNotLabeled().accumulate(1.1); // 12.5 + 1.1 + 1.5 = 15.1

            new MetricSnapshotVerifier(metric).add(15.1).verify();
            new MetricSnapshotVerifier(metric).add(10.0).verify(); // reset to custom initial value

            metric.getOrCreateNotLabeled().accumulate(1.1); // 10.0 + 1.1 + 1.5 = 12.6

            new MetricSnapshotVerifier(metric).add(12.6).verify();
        }

        @Test
        void testSnapshotWithLabelsNoReset() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5)
                    .addDynamicLabelNames("label1")
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2.1); // 0.0 + 2.1 + 1.5 = 3.6
            metric.getOrCreateLabeled(() -> 10.0, "label1", "2").accumulate(1.1); // 10.0 + 1.1 + 1.5 = 12.6

            new MetricSnapshotVerifier(metric)
                    .add(3.6, "label1", "1")
                    .add(12.6, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(3.6, "label1", "1")
                    .add(12.6, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").accumulate(3.1); // 12.6 + 3.1 + 1.5 = 17.2
            metric.getOrCreateLabeled("label1", "3").accumulate(10.1); // 0.0 + 10.1 + 1.5 = 11.6

            new MetricSnapshotVerifier(metric)
                    .add(3.6, "label1", "1") // unchanged
                    .add(17.2, "label1", "2")
                    .add(11.6, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToDefaultInitialValue() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5)
                    .addDynamicLabelNames("label1")
                    .resetOnExport()
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 10.0, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10.0, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2.1); // 0.0 + 2.1 + 1.5 = 3.6
            metric.getOrCreateLabeled(() -> 100.0, "label1", "2").accumulate(1.1); // 10.0 + 1.1 + 1.5 = 12.6

            new MetricSnapshotVerifier(metric)
                    .add(12.6, "label1", "2")
                    .add(3.6, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(10.0, "label1", "2")
                    .add(0.0, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").accumulate(3.1); // 10.0 + 3.1 + 1.5 = 14.6
            metric.getOrCreateLabeled("label1", "3").accumulate(10.1); // 0.0 + 10.1 + 1.5 = 11.6

            new MetricSnapshotVerifier(metric)
                    .add(14.6, "label1", "2")
                    .add(0.0, "label1", "1") // unchanged
                    .add(11.6, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            DoubleAccumulatorGauge metric = builder((l, r) -> l + r + 1.5)
                    .addDynamicLabelNames("label1")
                    .resetOnExport()
                    .setDefaultInitValue(10.0)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10.0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").accumulate(2.1); // 10.0 + 2.1 + 1.5 = 13.6
            metric.getOrCreateLabeled(() -> 100.0, "label1", "2").accumulate(1.1); // 100.0 + 1.1 + 1.5 = 102.6

            new MetricSnapshotVerifier(metric)
                    .add(13.6, "label1", "1")
                    .add(102.6, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(10.0, "label1", "1")
                    .add(100.0, "label1", "2")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").accumulate(3.5); // 100.0 + 3.5 + 1.5 = 105.0
            metric.getOrCreateLabeled("label1", "3").accumulate(42.7); // 10.0 + 42.7 + 1.5 = 54.2

            new MetricSnapshotVerifier(metric)
                    .add(10.0, "label1", "1") // unchanged
                    .add(105.0, "label1", "2")
                    .add(54.2, "label1", "3")
                    .verify();
        }
    }
}
