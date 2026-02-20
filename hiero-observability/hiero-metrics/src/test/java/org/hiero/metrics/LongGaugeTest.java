// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetricBaseTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LongGaugeTest extends SettableMetricBaseTest<LongGauge, LongGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected LongGauge.Builder emptyMetricBuilder(String name) {
        return LongGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> LongGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> LongGauge.builder((MetricKey<LongGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> LongGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class ModifyMeasurementsTests {

        @Test
        void testDefaultInitialValueNoLabels() {
            LongGauge metric = emptyMetricBuilder().build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0);

            metric.getOrCreateNotLabeled().set(2);
            metric.getOrCreateNotLabeled().set(1);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(1);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            LongGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0);

            metric.getOrCreateLabeled("label", "1").set(1);
            metric.getOrCreateLabeled("label", "1").set(2);
            metric.getOrCreateLabeled(() -> 10, "label", "2").set(3);

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(2);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(2);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(3);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(10);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            LongGauge metric = emptyMetricBuilder().setDefaultInitValue(10).build();

            metric.getOrCreateNotLabeled().set(3);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10);
        }

        @ParameterizedTest
        @ValueSource(
                longs = {
                    0,
                    -123456789,
                    123456789,
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                })
        void testCustomInitialValueNoLabels(long initialValue) {
            LongGauge metric =
                    emptyMetricBuilder().setDefaultInitValue(initialValue).build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            LongGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10)
                    .build();

            metric.getOrCreateLabeled("label", "1").set(2);
            metric.getOrCreateLabeled(() -> 100, "label", "2").set(3);

            assertThat(metric.getOrCreateLabeled(() -> 100, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(2);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(2);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(3);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(10);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(100);
        }

        @ParameterizedTest
        @ValueSource(
                longs = {
                    0,
                    -123456789,
                    123456789,
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                })
        void testCustomInitializerNoLabels(long initialValue) {
            LongGauge metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(
                longs = {
                    0,
                    -123456789,
                    123456789,
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                })
        void testCustomInitialValueWithLabels(long initialValue) {
            LongGauge metric = emptyMetricBuilder()
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
            LongGauge metric = emptyMetricBuilder().build();
            LongGauge.Measurement measurement = metric.getOrCreateNotLabeled();

            final Long[] threadValues = {Long.MIN_VALUE, -12345678L, -1L, 1L, 123456789L, Long.MAX_VALUE};
            final int updatesPerThread = 10000;

            ThreadUtils.runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
                long updateValue = threadValues[threadIdx];
                for (int i = 0; i < updatesPerThread; i++) {
                    measurement.set(updateValue);
                }
            });

            assertThat(measurement.get()).isIn(Arrays.asList(threadValues));
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<LongGauge, LongGauge.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabels() {
            LongGauge metric = emptyMetricBuilder().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0).verify();

            metric.getOrCreateNotLabeled().set(1);

            new MetricSnapshotVerifier(metric).add(1).verify();
            new MetricSnapshotVerifier(metric).add(1).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(2);

            new MetricSnapshotVerifier(metric).add(2).verify();
        }

        @Test
        void testSnapshotNoLabelsAndCustomInitialValue() {
            LongGauge metric = emptyMetricBuilder().setDefaultInitValue(10).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10).verify();

            metric.getOrCreateNotLabeled().set(1);

            new MetricSnapshotVerifier(metric).add(1).verify();
            new MetricSnapshotVerifier(metric).add(1).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(2);

            new MetricSnapshotVerifier(metric).add(2).verify();
        }

        @Test
        void testSnapshotWithLabels() {
            LongGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("label1").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").set(2);
            metric.getOrCreateLabeled(() -> 10, "label1", "2").set(1);

            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1")
                    .add(1, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1")
                    .add(1, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").set(3);
            metric.getOrCreateLabeled("label1", "3").set(10);

            new MetricSnapshotVerifier(metric)
                    .add(2, "label1", "1") // unchanged
                    .add(3, "label1", "2")
                    .add(10, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            LongGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1")
                    .setDefaultInitValue(10)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 100, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(100, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").set(2);
            metric.getOrCreateLabeled(() -> 100, "label1", "2").set(1);

            new MetricSnapshotVerifier(metric)
                    .add(1, "label1", "2")
                    .add(2, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(1, "label1", "2")
                    .add(2, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").set(3);
            metric.getOrCreateLabeled("label1", "3").set(42);

            new MetricSnapshotVerifier(metric)
                    .add(3, "label1", "2")
                    .add(2, "label1", "1") // unchanged
                    .add(42, "label1", "3")
                    .verify();
        }
    }
}
