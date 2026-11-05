// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricsExporter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ObservableGaugeTest extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected ObservableGauge.Builder emptyMetricBuilder(String name) {
        return ObservableGauge.builder(name);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIterationStartedBeforeObserveSeesFixedList() {
        MetricsExporter exporter = mock(MetricsExporter.class);
        MetricRegistry registry =
                MetricRegistry.builder().setMetricsExporter(exporter).build();
        ObservableGauge gauge = registry.register(emptyMetricBuilder().addDynamicLabelNames("label"));
        gauge.observe(() -> 1L, "label", "a");

        ArgumentCaptor<Supplier<MetricRegistrySnapshot>> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(exporter).setSnapshotSupplier(captor.capture());
        MetricSnapshot metricSnapshot = captor.getValue().get().iterator().next();

        Iterator<MeasurementSnapshot> export = metricSnapshot.iterator();
        gauge.observe(() -> 2L, "label", "b");

        List<String> seen = new ArrayList<>();
        export.forEachRemaining(s -> seen.add(s.getDynamicLabelValues().toString()));
        assertThat(seen).containsExactly("[a]");
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> ObservableGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((MetricKey<ObservableGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testObserveThrows() {
        ObservableGauge metric = emptyMetricBuilder()
                // just to ensure static labels don't interfere
                .addStaticLabels(new Label("environment", "test"))
                .addDynamicLabelNames("label")
                .build();

        LongSupplier longSupplier = () -> 42L;
        DoubleSupplier doubleSupplier = () -> 3.14;
        String[] nullArray = null;

        assertThatThrownBy(() -> metric.observe((LongSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value supplier must not be null");
        assertThatThrownBy(() -> metric.observe((DoubleSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value supplier must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, nullArray))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Label names and values must not be null");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, nullArray))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Label names and values must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label"))
                .as("accessing with not pairs of elements should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label names and values must be in pairs");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label"))
                .as("accessing with not pairs of elements should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label names and values must be in pairs");

        assertThatThrownBy(() -> metric.observe(longSupplier))
                .as("accessing getOrCreateLabeled() less labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 0");
        assertThatThrownBy(() -> metric.observe(doubleSupplier))
                .as("accessing getOrCreateLabeled() less labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 0");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", "value", "extraLabel", "extraValue"))
                .as("accessing getOrCreateLabeled() with labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 2");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", "value", "extraLabel", "extraValue"))
                .as("accessing getOrCreateLabeled() with labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 2");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", null))
                .as("accessing with null value should be NPE")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Label value must not be null");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", null))
                .as("accessing with null value should be NPE")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Label value must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, new String[] {null, "value"}))
                .as("accessing with null label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, new String[] {null, "value"}))
                .as("accessing with null label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label1", "value"))
                .as("accessing with wrong label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label1", "value"))
                .as("accessing with wrong label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");

        metric.observe(longSupplier, "label", "long");
        metric.observe(doubleSupplier, "label", "double");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", "long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A measurement with the same label values already exists");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", "double"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A measurement with the same label values already exists");
    }

    @Nested
    class BuilderTests extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder>.BuilderTests {

        @Test
        void testNullSupplierThrows() {
            ObservableGauge.Builder builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.observe((LongSupplier) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("value supplier must not be null");

            assertThatThrownBy(() -> builder.observe((DoubleSupplier) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("value supplier must not be null");
        }

        @Test
        void testNullLabelNamesAndValuesThrows() {
            ObservableGauge.Builder builder = emptyMetricBuilder();
            String[] nullLabelNamesAndValues = null;

            assertThatThrownBy(() -> builder.observe(() -> 42L, nullLabelNamesAndValues))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Label names and values must not be null");

            assertThatThrownBy(() -> builder.observe(() -> 3.14, nullLabelNamesAndValues))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Label names and values must not be null");
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder>.SnapshotTests {

        @Test
        void testConcurrentRegistrations() throws InterruptedException {
            ObservableGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("thread").buildMetric();

            final int threadCount = 10;
            final int registrationsPerThread = 1000;

            ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < registrationsPerThread; i++) {
                    int value = threadIdx * registrationsPerThread + i;

                    if (threadIdx % 2 == 0) {
                        metric.observe(() -> (long) value, "thread", "t" + value);
                    } else {
                        metric.observe(() -> (double) value, "thread", "t" + value);
                    }
                }
            });

            MetricSnapshotVerifier snapshotVerifier = new MetricSnapshotVerifier(metric).snapshotsAnyOrder();
            for (int i = 0; i < threadCount; i++) {
                for (int j = 0; j < registrationsPerThread; j++) {
                    int value = i * registrationsPerThread + j;

                    if (i % 2 == 0) {
                        snapshotVerifier.add((long) value, "thread", "t" + value);
                    } else {
                        snapshotVerifier.add((double) value, "thread", "t" + value);
                    }
                }
            }
            snapshotVerifier.verify();

            // verify reset is no-op for observable gauge
            resetMetric(metric);
            snapshotVerifier.verify();
        }
    }
}
