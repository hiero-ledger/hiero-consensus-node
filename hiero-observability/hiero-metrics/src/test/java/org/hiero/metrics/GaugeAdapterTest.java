// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetricBaseTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class GaugeAdapterTest
        extends SettableMetricBaseTest<GaugeAdapter<TestContainer>, GaugeAdapter.Builder<TestContainer>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected GaugeAdapter.Builder<TestContainer> emptyMetricBuilder(String name) {
        return GaugeAdapter.builderForLong(GaugeAdapter.key(name), TestContainer::new, TestContainer::getAsLong)
                .setReset(TestContainer::reset);
    }

    private GaugeAdapter.Builder<TestContainer> longBuilder() {
        return GaugeAdapter.builderForLong(
                GaugeAdapter.key(DEFAULT_NAME), TestContainer::new, TestContainer::getAsLong);
    }

    private GaugeAdapter.Builder<TestContainer> longBuilder(long customInitialValue) {
        return GaugeAdapter.builderForLong(
                GaugeAdapter.key(DEFAULT_NAME), () -> new TestContainer(customInitialValue), TestContainer::getAsLong);
    }

    private GaugeAdapter.Builder<TestContainer> doubleBuilder() {
        return GaugeAdapter.builderForDouble(
                GaugeAdapter.key(DEFAULT_NAME), TestContainer::new, TestContainer::getAsDouble);
    }

    private GaugeAdapter.Builder<TestContainer> doubleBuilder(double customInitialValue) {
        return GaugeAdapter.builderForDouble(
                GaugeAdapter.key(DEFAULT_NAME),
                () -> new TestContainer(customInitialValue),
                TestContainer::getAsDouble);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> GaugeAdapter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class BuilderTests
            extends MetricBaseTest<GaugeAdapter<TestContainer>, GaugeAdapter.Builder<TestContainer>>.BuilderTests {

        @Test
        void testNullMetricKeyBuilder() {
            MetricKey<GaugeAdapter<TestContainer>> metricKey = null;

            assertThatThrownBy(
                            () -> GaugeAdapter.builderForLong(metricKey, TestContainer::new, TestContainer::getAsLong))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("key must not be null");
        }

        @Test
        void testNullFactoryThrows() {
            assertThatThrownBy(() ->
                            GaugeAdapter.builderForLong(GaugeAdapter.key(DEFAULT_NAME), null, TestContainer::getAsLong))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("default initializer must not be null");
        }

        @Test
        void testNullExportGetterThrows() {
            assertThatThrownBy(() -> GaugeAdapter.builderForLong(
                            GaugeAdapter.key(DEFAULT_NAME), TestContainer::new, (ToLongFunction<TestContainer>) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("exportGetter cannot be null");
            assertThatThrownBy(() -> GaugeAdapter.builderForDouble(
                            GaugeAdapter.key(DEFAULT_NAME), TestContainer::new, (ToDoubleFunction<TestContainer>) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("exportGetter cannot be null");
        }
    }

    @Nested
    class SnapshotTests
            extends MetricBaseTest<GaugeAdapter<TestContainer>, GaugeAdapter.Builder<TestContainer>>.SnapshotTests {

        @Test
        void testSnapshotNoLabelsAfterResetWhenNoResetSpecifiedForLong() {
            GaugeAdapter<TestContainer> metric = longBuilder().build();
            metric.getOrCreateNotLabeled().set(1L);
            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(1L).verify();
        }

        @Test
        void testSnapshotNoLabelsAfterResetWhenNoResetSpecifiedForDouble() {
            GaugeAdapter<TestContainer> metric = doubleBuilder().build();
            metric.getOrCreateNotLabeled().set(1.0);
            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(1.0).verify();
        }

        @Test
        void testSnapshotNoLabelsAfterResetForLong() {
            GaugeAdapter<TestContainer> metric =
                    longBuilder().setReset(TestContainer::reset).build();

            metric.getOrCreateNotLabeled().set(1L);
            new MetricSnapshotVerifier(metric).add(1L).verify();

            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(0L).verify();
        }

        @Test
        void testSnapshotNoLabelsAfterResetForDouble() {
            GaugeAdapter<TestContainer> metric =
                    doubleBuilder().setReset(TestContainer::reset).build();

            metric.getOrCreateNotLabeled().set(1.0);
            new MetricSnapshotVerifier(metric).add(1.0).verify();

            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(0.0).verify();
        }

        @Test
        void testSnapshotNoLabelsCustomInitAfterResetForLong() {
            GaugeAdapter<TestContainer> metric = longBuilder()
                    .setReset(TestContainer::reset)
                    .setDefaultInitializer(() -> new TestContainer(42L))
                    .build();

            metric.getOrCreateNotLabeled().set(1L);
            new MetricSnapshotVerifier(metric).add(1L).verify();

            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(42L).verify();
        }

        @Test
        void testSnapshotNoLabelsCustomInitAfterResetForDouble() {
            GaugeAdapter<TestContainer> metric = doubleBuilder()
                    .setReset(TestContainer::reset)
                    .setDefaultInitializer(() -> new TestContainer(3.14))
                    .build();

            metric.getOrCreateNotLabeled().set(1.0);
            new MetricSnapshotVerifier(metric).add(1.0).verify();

            resetMetric(metric);
            new MetricSnapshotVerifier(metric).add(3.14).verify();
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
        void testSnapshotLongNoLabelsWithCustomInitValue(long initValue) {
            GaugeAdapter<TestContainer> metric = longBuilder(initValue).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(initValue).verify();

            metric.getOrCreateNotLabeled().set(12345678);

            new MetricSnapshotVerifier(metric).add(12345678).verify();
            new MetricSnapshotVerifier(metric).add(12345678).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(-12345678);

            new MetricSnapshotVerifier(metric).add(-12345678).verify();
        }

        @ParameterizedTest
        @ValueSource(
                doubles = {
                    -1.0,
                    0L,
                    1.0,
                    Double.MIN_VALUE,
                    Double.MAX_VALUE,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                })
        void testSnapshotDoubleNoLabelsWithCustomInitValue(double initValue) {
            GaugeAdapter<TestContainer> metric = doubleBuilder(initValue).build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateNotLabeled(); // just initialize measurement
            new MetricSnapshotVerifier(metric).add(initValue).verify();

            metric.getOrCreateNotLabeled().set(123.0);

            new MetricSnapshotVerifier(metric).add(123.0).verify();
            new MetricSnapshotVerifier(metric).add(123.0).verify(); // still the same value

            metric.getOrCreateNotLabeled().set(-123.0);

            new MetricSnapshotVerifier(metric).add(-123.0).verify();
        }

        @Test
        void testSnapshotLongWithLabels() {
            GaugeAdapter<TestContainer> metric =
                    longBuilder().addDynamicLabelNames("label").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label", "0"); // just initialize measurement
            metric.getOrCreateLabeled(() -> new TestContainer(1), "label", "1"); // just initialize measurement

            new MetricSnapshotVerifier(metric)
                    .add(0, "label", "0")
                    .add(1, "label", "1")
                    .verify();

            metric.getOrCreateLabeled(() -> new TestContainer(1), "label", "1").set(10); // initializer ignored
            metric.getOrCreateLabeled("label", "2").set(2);

            new MetricSnapshotVerifier(metric)
                    .add(0, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .verify();
            new MetricSnapshotVerifier(metric)
                    .add(0, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .verify();

            metric.getOrCreateLabeled("label", "0").set(-1);
            metric.getOrCreateLabeled("label", "3").set(3);

            new MetricSnapshotVerifier(metric)
                    .add(-1, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .add(3, "label", "3")
                    .verify();
        }

        @Test
        void testSnapshotDoubleWithLabels() {
            GaugeAdapter<TestContainer> metric =
                    doubleBuilder().addDynamicLabelNames("label").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label", "0"); // just initialize measurement
            metric.getOrCreateLabeled(() -> new TestContainer(1.0), "label", "1"); // just initialize measurement

            new MetricSnapshotVerifier(metric)
                    .add(0.0, "label", "0")
                    .add(1.0, "label", "1")
                    .verify();

            metric.getOrCreateLabeled(() -> new TestContainer(1.0), "label", "1")
                    .set(10.0); // initializer ignored
            metric.getOrCreateLabeled("label", "2").set(2.0);

            new MetricSnapshotVerifier(metric)
                    .add(0.0, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .verify();
            // repeat to verify stability
            new MetricSnapshotVerifier(metric)
                    .add(0.0, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .verify();

            metric.getOrCreateLabeled("label", "0").set(-1.0);
            metric.getOrCreateLabeled("label", "3").set(3.0);

            new MetricSnapshotVerifier(metric)
                    .add(-1.0, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .add(3.0, "label", "3")
                    .verify();
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
        void testSnapshotLongWithLabelsAndDefaultInitializer(long initValue) {
            GaugeAdapter<TestContainer> metric =
                    longBuilder(initValue).addDynamicLabelNames("label").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label", "0"); // just initialize measurement
            metric.getOrCreateLabeled(() -> new TestContainer(1), "label", "1"); // just initialize measurement

            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(1, "label", "1")
                    .verify();

            metric.getOrCreateLabeled(() -> new TestContainer(1), "label", "1").set(10); // initializer ignored
            metric.getOrCreateLabeled("label", "2").set(2);

            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .verify();
            // repeat to verify stability
            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .verify();

            metric.getOrCreateLabeled("label", "0").set(-1);
            metric.getOrCreateLabeled("label", "3").set(3);

            new MetricSnapshotVerifier(metric)
                    .add(-1, "label", "0")
                    .add(10, "label", "1")
                    .add(2, "label", "2")
                    .add(3, "label", "3")
                    .verify();
        }

        @ParameterizedTest
        @ValueSource(
                doubles = {
                    -1.0,
                    0L,
                    1.0,
                    Double.MIN_VALUE,
                    Double.MAX_VALUE,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                })
        void testSnapshotDoubleWithLabelsAndInitValue(double initValue) {
            GaugeAdapter<TestContainer> metric =
                    doubleBuilder(initValue).addDynamicLabelNames("label").build();

            verifySnapshotIsEmpty(metric);

            metric.getOrCreateLabeled("label", "0"); // just initialize measurement
            metric.getOrCreateLabeled(() -> new TestContainer(1.0), "label", "1"); // just initialize measurement

            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(1.0, "label", "1")
                    .verify();

            metric.getOrCreateLabeled(() -> new TestContainer(1.0), "label", "1")
                    .set(10.0); // initializer ignored
            metric.getOrCreateLabeled("label", "2").set(2.0);

            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .verify();
            // repeat to verify stability
            new MetricSnapshotVerifier(metric)
                    .add(initValue, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .verify();

            metric.getOrCreateLabeled("label", "0").set(-1.0);
            metric.getOrCreateLabeled("label", "3").set(3.0);

            new MetricSnapshotVerifier(metric)
                    .add(-1.0, "label", "0")
                    .add(10.0, "label", "1")
                    .add(2.0, "label", "2")
                    .add(3.0, "label", "3")
                    .verify();
        }
    }
}
