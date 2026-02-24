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

public class DoubleGaugeTest extends SettableMetricBaseTest<DoubleGauge, DoubleGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected DoubleGauge.Builder emptyMetricBuilder(String name) {
        return DoubleGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> DoubleGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> DoubleGauge.builder((MetricKey<DoubleGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> DoubleGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class ModifyMeasurementsTests {

        @Test
        void testDefaultInitialValueNoLabels() {
            DoubleGauge metric = emptyMetricBuilder().build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0.0);

            metric.getOrCreateNotLabeled().set(2.5);
            metric.getOrCreateNotLabeled().set(1.5);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(1.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(0.0);
        }

        @Test
        void testDefaultInitialValueWithLabels() {
            DoubleGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("label").build();
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0.0);

            metric.getOrCreateLabeled("label", "1").set(1.5);
            metric.getOrCreateLabeled("label", "1").set(2.5);
            metric.getOrCreateLabeled(() -> 10.0, "label", "2").set(3.5);

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(2.5);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(2.5);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(3.5);

            resetMetric(metric);

            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(0.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(10.0);
        }

        @Test
        void testCustomInitialValueNoLabels() {
            DoubleGauge metric = emptyMetricBuilder().setDefaultInitValue(10.0).build();

            metric.getOrCreateNotLabeled().set(3.5);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(3.5);

            resetMetric(metric);
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(10.0);
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
        void testCustomInitialValueNoLabels(double initialValue) {
            DoubleGauge metric =
                    emptyMetricBuilder().setDefaultInitValue(initialValue).build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @Test
        void testCustomInitialValueWithLabels() {
            DoubleGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label")
                    .setDefaultInitValue(10.0)
                    .build();

            metric.getOrCreateLabeled("label", "1").set(2.0);
            metric.getOrCreateLabeled(() -> 100.0, "label", "2").set(2.5);

            assertThat(metric.getOrCreateLabeled(() -> 100.0, "label", "1").get())
                    .as("initializer is not used when measurement already initialized")
                    .isEqualTo(2.0);
            assertThat(metric.getOrCreateLabeled("label", "1").get()).isEqualTo(2.0);
            assertThat(metric.getOrCreateLabeled("label", "2").get()).isEqualTo(2.5);

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
            DoubleGauge metric = emptyMetricBuilder()
                    .setDefaultInitializer(() -> initialValue)
                    .build();
            assertThat(metric.getOrCreateNotLabeled().get()).isEqualTo(initialValue);
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 789.012, Double.POSITIVE_INFINITY})
        void testCustomInitialValueWithLabels(double initialValue) {
            DoubleGauge metric = emptyMetricBuilder()
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
            DoubleGauge metric = emptyMetricBuilder().build();
            DoubleGauge.Measurement measurement = metric.getOrCreateNotLabeled();

            final Double[] threadValues = {
                Double.NEGATIVE_INFINITY, -100.5, -14.2, 14.2, 100.5, Double.POSITIVE_INFINITY
            };
            final int updatesPerThread = 10000;

            ThreadUtils.runConcurrentAndWait(threadValues.length, Duration.ofSeconds(1), threadIdx -> () -> {
                double updateValue = threadValues[threadIdx];
                for (int i = 0; i < updatesPerThread; i++) {
                    measurement.set(updateValue);
                }
            });

            assertThat(measurement.get()).isIn(Arrays.asList(threadValues));
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<DoubleGauge, DoubleGauge.Builder>.SnapshotTests {

        @Test
        void testSnapshotNoLabels() {
            DoubleGauge metric = emptyMetricBuilder().build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0).verify();

            metric.getOrCreateNotLabeled().set(1.1);

            new MetricSnapshotVerifier(metric).add(1.1).verify();
            new MetricSnapshotVerifier(metric).add(1.1).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(2.5);

            new MetricSnapshotVerifier(metric).add(2.5).verify();
        }

        @Test
        void testSnapshotNoLabelsAndCustomInitialValue() {
            DoubleGauge metric = emptyMetricBuilder().setDefaultInitValue(10.0).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(10.0).verify();

            metric.getOrCreateNotLabeled().set(1.1);

            new MetricSnapshotVerifier(metric).add(1.1).verify();
            new MetricSnapshotVerifier(metric).add(1.1).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(2.5);

            new MetricSnapshotVerifier(metric).add(2.5).verify();
        }

        @Test
        void testSnapshotWithLabels() {
            DoubleGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("label1").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label1", "1"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(0.0, "label1", "1").verify();

            metric.getOrCreateLabeled("label1", "1").set(2.1);
            metric.getOrCreateLabeled(() -> 10.0, "label1", "2").set(1.1);

            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1")
                    .add(1.1, "label1", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1")
                    .add(1.1, "label1", "2")
                    .verify(); // still the same

            metric.getOrCreateLabeled("label1", "2").set(3.1);
            metric.getOrCreateLabeled("label1", "3").set(10.1);

            new MetricSnapshotVerifier(metric)
                    .add(2.1, "label1", "1") // unchanged
                    .add(3.1, "label1", "2")
                    .add(10.1, "label1", "3")
                    .verify();
        }

        @Test
        void testSnapshotWithLabelsWithResetToCustomInialValue() {
            DoubleGauge metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1")
                    .setDefaultInitValue(10.0)
                    .build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled(() -> 100.0, "label1", "2"); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(100.0, "label1", "2").verify();

            metric.getOrCreateLabeled("label1", "1").set(2.1);
            metric.getOrCreateLabeled(() -> 100.0, "label1", "2").set(1.1);

            new MetricSnapshotVerifier(metric)
                    .add(1.1, "label1", "2")
                    .add(2.1, "label1", "1")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(1.1, "label1", "2")
                    .add(2.1, "label1", "1")
                    .verify();

            metric.getOrCreateLabeled("label1", "2").set(3.5);
            metric.getOrCreateLabeled("label1", "3").set(42.7);

            new MetricSnapshotVerifier(metric)
                    .add(3.5, "label1", "2")
                    .add(2.1, "label1", "1") // unchanged
                    .add(42.7, "label1", "3")
                    .verify();
        }
    }
}
