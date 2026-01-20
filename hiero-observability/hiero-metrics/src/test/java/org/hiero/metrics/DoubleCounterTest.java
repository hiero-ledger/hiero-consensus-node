// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetricBaseTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DoubleCounterTest extends SettableMetricBaseTest<DoubleCounter, DoubleCounter.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.COUNTER;
    }

    @Override
    protected DoubleCounter.Builder emptyMetricBuilder(String name) {
        return DoubleCounter.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> DoubleCounter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> DoubleCounter.builder((MetricKey<DoubleCounter>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> DoubleCounter.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class BuilderTests extends MetricBaseTest<DoubleCounter, DoubleCounter.Builder>.BuilderTests {

        @ParameterizedTest
        @ValueSource(doubles = {-1.0, -0.0001, -100.5, -Double.MAX_VALUE, -Double.MIN_VALUE, Double.NEGATIVE_INFINITY})
        void testNegativeInitValueThrows(double initValue) {
            DoubleCounter.Builder builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.setDefaultInitValue(initValue))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default initial value for counter must be non-negative");
        }
    }

    @Nested
    class ModifyMeasurementsTests {

        @Test
        void testDefaultInitialValueNoLabels() {
            DoubleCounter metric = emptyMetricBuilder().build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0.0);

            metric.getOrCreateNotLabeled().increment();
            metric.getOrCreateNotLabeled().increment(2.5);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0.0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            DoubleCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0.0);

            metric.getOrCreateLabeled("label", "1").increment();
            metric.getOrCreateLabeled(() -> 10.0, "label", "2").increment(2.5);

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(1.0);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(1.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(12.5);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(10.0);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            DoubleCounter metric =
                    emptyMetricBuilder().setDefaultInitValue(10.0).build();

            metric.getOrCreateNotLabeled().increment(3.5);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(13.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10.0);
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 0.123, 789.012, Double.POSITIVE_INFINITY})
        void testCustomInitialValueNoLabels(double initialValue) {
            DoubleCounter metric =
                    emptyMetricBuilder().setDefaultInitValue(initialValue).build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            DoubleCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10.0)
                    .build();

            metric.getOrCreateLabeled("label", "1").increment(2.0); // 10.0 + 2.0 = 12.0
            metric.getOrCreateLabeled(() -> 100.0, "label", "2").increment(2.5); // 100.0 + 2.5 = 102.5

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(12.0);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(12.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(102.5);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(10.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100.0);
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 789.012, Double.POSITIVE_INFINITY})
        void testCustomInitializerNoLabels(double initialValue) {
            DoubleCounter metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 789.012, Double.POSITIVE_INFINITY})
        void testCustomInitialValueWithLabels(double initialValue) {
            DoubleCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(initialValue)
                    .build();

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(initialValue);
            assertThat(metric.getOrCreateLabeled(() -> 1000.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(initialValue);
        }

        @Test
        void testConcurrentMeasurementModification() throws InterruptedException {
            DoubleCounter metric = emptyMetricBuilder().build();
            DoubleCounter.Measurement measurement = metric.getOrCreateNotLabeled();

            final int threadCount = 10;
            final int updatesPerThread = 10000;

            ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < updatesPerThread; i++) {
                    measurement.increment();
                }
            });

            assertThat(measurement.get()).isEqualTo(threadCount * updatesPerThread);
        }

        @ParameterizedTest
        @ValueSource(doubles = {-1.0, -0.0001, -100.5, -Double.MAX_VALUE, -Double.MIN_VALUE, Double.NEGATIVE_INFINITY})
        void testNegativeDefaultInitializerThrows(double initValue) {
            DoubleCounter metric =
                    emptyMetricBuilder().setDefaultInitializer(() -> initValue).build();

            assertThatThrownBy(metric::getOrCreateNotLabeled)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Increment value must be non-negative");
        }

        @ParameterizedTest
        @ValueSource(doubles = {-1.0, -0.0001, -100.5, -Double.MAX_VALUE, -Double.MIN_VALUE, Double.NEGATIVE_INFINITY})
        void testNegativeCustomInitializerThrows(double initValue) {
            DoubleCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();

            assertThatThrownBy(() -> metric.getOrCreateLabeled(() -> initValue, "label", "1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Increment value must be non-negative");
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<DoubleCounter, DoubleCounter.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabels() {
            DoubleCounter metric = emptyMetricBuilder().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0).verify();

            metric.getOrCreateNotLabeled().increment(1.0); // 0.0 + 1.0 = 1.0
            metric.getOrCreateNotLabeled().increment(1.1); // 1.0 + 1.1 = 2.1

            new MetricSnapshotVerifier(metric).add(2.1).verify();
            new MetricSnapshotVerifier(metric).add(2.1).verify(); // still the same value

            metric.getOrCreateNotLabeled().increment(); // 2.1 + 1.0 = 3.1

            new MetricSnapshotVerifier(metric).add(3.1).verify();
        }

        @Test
        void testSnapshotNoLabelsAndCustomInitialValue() {
            DoubleCounter metric =
                    emptyMetricBuilder().setDefaultInitValue(10.0).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10.0).verify();

            metric.getOrCreateNotLabeled().increment(1.0); // 10.0 + 1.0 = 11.0
            metric.getOrCreateNotLabeled().increment(1.1); // 11.0 + 1.1 = 12.1

            new MetricSnapshotVerifier(metric).add(12.1).verify();
            new MetricSnapshotVerifier(metric).add(12.1).verify(); // still the same

            metric.getOrCreateNotLabeled().increment(); // 12.1 + 1.0 = 13.1

            new MetricSnapshotVerifier(metric).add(13.1).verify();
        }

        @Test
        void testSnapshotWithLabels() {
            DoubleCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label1").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").increment(2.1); // 0.0 + 2.1 = 2.1
            metric.getOrCreateLabeled(() -> 10.0, "label1", "2").increment(1.1); // 10.0 + 1.1 = 11.1

            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1")
                    .add(11.1, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1")
                    .add(11.1, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").increment(3.1); // 11.1 + 3.1 = 14.2
            metric.getOrCreateLabeled("label1", "3").increment(10.1); // 0.0 + 10.1 = 10.1

            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1") // unchanged
                    .add(14.2, "label1", "2")
                    .add(10.1, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            DoubleCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1")
                    .setDefaultInitValue(10.0)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 100.0, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(100.0, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").increment(2.1); // 10.0 + 2.1 = 12.1
            metric.getOrCreateLabeled(() -> 100.0, "label1", "2").increment(1.1); // 100.0 + 1.1 = 101.1

            new MetricSnapshotVerifier(metric)
                    .add(101.1, "label1", "2")
                    .add(12.1, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(101.1, "label1", "2")
                    .add(12.1, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").increment(3.5); // 101.1 + 3.5 = 104.6
            metric.getOrCreateLabeled("label1", "3").increment(42.7); // 10.0 + 42.7 = 52.7

            new MetricSnapshotVerifier(metric)
                    .add(104.6, "label1", "2")
                    .add(12.1, "label1", "1") // unchanged
                    .add(52.7, "label1", "3")
                    .verify();
        }
    }
}
