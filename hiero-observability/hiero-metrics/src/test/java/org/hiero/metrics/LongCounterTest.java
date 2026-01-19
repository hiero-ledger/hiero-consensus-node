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

public class LongCounterTest extends SettableMetricBaseTest<LongCounter, LongCounter.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.COUNTER;
    }

    @Override
    protected LongCounter.Builder emptyMetricBuilder(String name) {
        return LongCounter.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> LongCounter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> LongCounter.builder((MetricKey<LongCounter>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> LongCounter.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class BuilderTests extends MetricBaseTest<LongCounter, LongCounter.Builder>.BuilderTests {

        @ParameterizedTest
        @ValueSource(longs = {-1, -100, Long.MIN_VALUE})
        void testNegativeInitValueThrows(long initValue) {
            LongCounter.Builder builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.setDefaultInitValue(initValue))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default initial value for counter must be non-negative");
        }
    }

    @Nested
    class ModifyMeasurementsTests {

        @Test
        void testDefaultInitialValueNoLabels() {
            LongCounter metric = emptyMetricBuilder().build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0);

            metric.getOrCreateNotLabeled().increment();
            metric.getOrCreateNotLabeled().increment(2);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            LongCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0);

            metric.getOrCreateLabeled("label", "1").increment();
            metric.getOrCreateLabeled(() -> 10, "label", "2").increment(2);

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(1);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(1);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(12);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(10);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            LongCounter metric = emptyMetricBuilder().setDefaultInitValue(10).build();

            metric.getOrCreateNotLabeled().increment(3);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(13);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 123456789, Long.MAX_VALUE})
        void testCustomInitialValueNoLabels(long initialValue) {
            LongCounter metric =
                    emptyMetricBuilder().setDefaultInitValue(initialValue).build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            LongCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10)
                    .build();

            metric.getOrCreateLabeled("label", "1").increment(2); // 10 + 2 = 12
            metric.getOrCreateLabeled(() -> 100, "label", "2").increment(3); // 100 + 3 = 103

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(12);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(12);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(103);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(10);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 123456789, Long.MAX_VALUE})
        void testCustomInitializerNoLabels(long initialValue) {
            LongCounter metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 123456789, Long.MAX_VALUE})
        void testCustomInitialValueWithLabels(long initialValue) {
            LongCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(initialValue)
                    .build();

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(initialValue);
            assertThat(metric.getOrCreateLabeled(() -> 1000, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(initialValue);
        }

        @Test
        void testConcurrentMeasurementModification() throws InterruptedException {
            LongCounter metric = emptyMetricBuilder().build();
            LongCounter.Measurement measurement = metric.getOrCreateNotLabeled();

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
        @ValueSource(longs = {-1, -100, Long.MIN_VALUE})
        void testNegativeDefaultInitializerThrows(long initValue) {
            LongCounter metric =
                    emptyMetricBuilder().setDefaultInitializer(() -> initValue).build();

            assertThatThrownBy(metric::getOrCreateNotLabeled)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Increment value must be non-negative");
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, -100, Long.MIN_VALUE})
        void testNegativeCustomInitializerThrows(long initValue) {
            LongCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();

            assertThatThrownBy(() -> metric.getOrCreateLabeled(() -> initValue, "label", "1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Increment value must be non-negative");
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<LongCounter, LongCounter.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabels() {
            LongCounter metric = emptyMetricBuilder().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0).verify();

            metric.getOrCreateNotLabeled().increment(1); // 0 + 1 = 1
            metric.getOrCreateNotLabeled().increment(2); // 1 + 2 = 3

            new MetricSnapshotVerifier(metric).add(3).verify();
            new MetricSnapshotVerifier(metric).add(3).verify(); // still the same value

            metric.getOrCreateNotLabeled().increment(); // 3 + 1 = 4

            new MetricSnapshotVerifier(metric).add(4).verify();
        }

        @Test
        void testSnapshotNoLabelsAndCustomInitialValue() {
            LongCounter metric = emptyMetricBuilder().setDefaultInitValue(10).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10).verify();

            metric.getOrCreateNotLabeled().increment(1); // 10 + 1 = 11
            metric.getOrCreateNotLabeled().increment(2); // 11 + 2 = 13

            new MetricSnapshotVerifier(metric).add(13).verify();
            new MetricSnapshotVerifier(metric).add(13).verify(); // still the same

            metric.getOrCreateNotLabeled().increment(); // 13 + 1 = 14

            new MetricSnapshotVerifier(metric).add(14).verify();
        }

        @Test
        void testSnapshotWithLabels() {
            LongCounter metric =
                    emptyMetricBuilder().addDynamicLabelNames("label1").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").increment(2); // 0 + 2 = 2
            metric.getOrCreateLabeled(() -> 10, "label1", "2").increment(3); // 10 + 3 = 13

            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1")
                    .add(13, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1")
                    .add(13, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").increment(5); // 13 + 5 = 18
            metric.getOrCreateLabeled("label1", "3").increment(10); // 0 + 10 = 10

            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1") // unchanged
                    .add(18, "label1", "2")
                    .add(10, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            LongCounter metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1")
                    .setDefaultInitValue(10)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 100, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(100, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").increment(2); // 10 + 2 = 12
            metric.getOrCreateLabeled(() -> 100, "label1", "2").increment(); // 100 + 1 = 101

            new MetricSnapshotVerifier(metric)
                    .add(101, "label1", "2")
                    .add(12, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(101, "label1", "2")
                    .add(12, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").increment(3); // 101 + 3 = 104
            metric.getOrCreateLabeled("label1", "3").increment(42); // 10 + 42 = 52

            new MetricSnapshotVerifier(metric)
                    .add(104, "label1", "2")
                    .add(12, "label1", "1") // unchanged
                    .add(52, "label1", "3")
                    .verify();
        }
    }
}
