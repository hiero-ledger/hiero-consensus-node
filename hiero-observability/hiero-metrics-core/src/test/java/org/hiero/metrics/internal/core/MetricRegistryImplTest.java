// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricsRegistrationProvider;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MetricRegistryImplTest {

    private static final String DUPLICATE_NAME = "duplicate_name";

    @Nested
    class Exceptions {

        @Test
        public void testDuplicateGlobalLabelName() {
            Label label1 = new Label("env", "test");
            Label label2 = new Label("env", "production");

            assertThatThrownBy(() -> new MetricRegistryImpl(label1, label2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate label name", "env");
        }

        @Test
        public void testUnmodifiableEmptyGlobalLabels() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.globalLabels().add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        public void testUnmodifiableGlobalLabels() {
            MetricRegistryImpl registry = new MetricRegistryImpl(new Label("a", "1"), new Label("b", "2"));
            List<Label> globalLabels = registry.globalLabels();

            assertThatThrownBy(() -> globalLabels.add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(globalLabels::clear)
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        public void testRegisterMetricWithBuilderAndLabelMatchingGlobalLabel() {
            Label label1 = new Label("env", "test");
            Label label2 = new Label("region", "us-west-2");
            MetricRegistryImpl registry = new MetricRegistryImpl(label1, label2);

            assertThatThrownBy(() -> registry.register(
                            LongCounter.builder("test_counter").withConstantLabel(new Label("env", "production"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Label", "conflicts with existing", "env");
        }

        @Test
        public void testRegisterMetricWithProviderAndLabelMatchingGlobalLabel() {
            Label label1 = new Label("env", "test");
            Label label2 = new Label("region", "us-west-2");
            MetricRegistryImpl registry = new MetricRegistryImpl(label1, label2);

            assertThatThrownBy(() -> registry.registerMetrics(() -> List.of(
                            LongCounter.builder("test_counter").withConstantLabel(new Label("env", "production")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Label", "conflicts with existing", "env");
        }

        @Test
        public void testUnmodifiableEmptyMetricsView() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.metrics().add(mock(Metric.class)))
                    .as("Metrics view is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        public void testUnmodifiableMetricsView() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.register(LongCounter.builder("test_counter"));
            Collection<Metric> metrics = registry.metrics();

            assertThat(metrics).isNotEmpty();
            assertThatThrownBy(() -> metrics.add(mock(Metric.class)))
                    .as("Metrics view is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(metrics::clear)
                    .as("Metrics view is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        public void testRegisterMetricsWithNullProviderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.registerMetrics(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics registration provider must not be null");
        }

        @Test
        public void testRegisterMetricsWithProviderNullMetricsThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.registerMetrics(() -> null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics collection must not be null");
        }

        @Test
        public void testRegisterNullBuilderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric builder must not be null");
        }

        @Test
        public void testRegisterDuplicateMetricWithBuilderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.register(LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        public void testRegisterDuplicateMetricsWithSingleProviderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            MetricsRegistrationProvider metricsProvider =
                    () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> registry.registerMetrics(metricsProvider))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        public void testRegisterDuplicateMetricsWithMultipleProvidersThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.registerMetrics(
                    () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("metric1")));

            assertThatThrownBy(() -> registry.registerMetrics(
                            () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("metric2"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        public void testRegisterDuplicateMetricsWithProviderAndBuilderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.registerMetrics(() -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("other")));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        public void testRegisterDuplicateMetricsWithBuilderAndProviderThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.register(LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> registry.registerMetrics(
                            () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("other"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        public void testFindMetricWithNullKeyThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.findMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        public void testGetMetricWithNullKeyThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.getMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        public void testGetMetricFromEmptyRegistryThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key("unknown_metric")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        public void testGetMetricWithWrongNameThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key(name + "_")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        public void testGetMetricWithWrongTypeThrows() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongGauge.key(name)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 1})
        public void testAccessSnapshotOfEmptyRegistryThrows(int index) {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            UpdatableMetricRegistrySnapshot snapshot = registry.snapshot();
            assertThatThrownBy(() -> snapshot.get(index)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class SingleThread {

        @Test
        public void testGlobalLabelsEmpty() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            assertThat(registry.globalLabels()).isEmpty();
        }

        @Test
        public void testGlobalLabelsNotEmpty() {
            Label label1 = new Label("env", "test");
            Label label2 = new Label("region", "us-west-2");
            MetricRegistryImpl registry = new MetricRegistryImpl(label1, label2);

            assertThat(registry.globalLabels()).containsExactly(label1, label2);
        }

        @Test
        public void testMetricsViewEmpty() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            assertThat(registry.metrics()).isEmpty();
        }

        @Test
        public void testMetricsViewNonEmptyAfterRegisterBuilders() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
            LongCounter counter2 = registry.register(LongCounter.builder("counter2"));

            assertThat(registry.metrics()).containsExactlyInAnyOrder(counter1, counter2);
        }

        @Test
        public void testMetricsViewNonEmptyAfterRegisterProviders() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            registry.registerMetrics(() -> List.of(LongCounter.builder("counter1")));
            registry.registerMetrics(() -> List.of(LongCounter.builder("counter2"), LongCounter.builder("counter3")));

            assertThat(registry.metrics().size()).isEqualTo(3);
            assertThat(registry.metrics().stream().map(Metric::metadata).map(MetricMetadata::name))
                    .containsExactlyInAnyOrder("counter1", "counter2", "counter3");
        }

        @Test
        public void testMetricsViewNonEmptyAfterRegisterProvidersAndBuilders() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            registry.register(LongCounter.builder("counter1"));
            registry.registerMetrics(() -> List.of(LongCounter.builder("counter2")));
            registry.register(LongCounter.builder("counter3"));

            assertThat(registry.metrics().size()).isEqualTo(3);
            assertThat(registry.metrics().stream().map(Metric::metadata).map(MetricMetadata::name))
                    .containsExactlyInAnyOrder("counter1", "counter2", "counter3");
        }

        @Test
        public void testMetricsViewEmptyAfterEmptyRegistrationProvider() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            registry.registerMetrics(List::of);

            assertThat(registry.metrics()).isEmpty();
        }

        @Test
        public void testMetricFoundWithBuilderRegistration() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String metricName = "test_metric";

            LongCounter registeredCounter = registry.register(LongCounter.builder(metricName));

            assertThat(registry.findMetric(LongCounter.key(metricName)))
                    .isPresent()
                    .containsSame(registeredCounter);
            assertThat(registry.getMetric(LongCounter.key(metricName))).isSameAs(registeredCounter);
        }

        @Test
        public void testMetricFoundWithProviderRegistration() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String metricName = "test_metric";

            registry.registerMetrics(() -> List.of(LongCounter.builder(metricName)));

            Optional<LongCounter> metric = registry.findMetric(LongCounter.key(metricName));
            assertThat(metric).isPresent();
            assertThat(registry.getMetric(LongCounter.key(metricName))).isSameAs(metric.get());
        }

        @Test
        public void testFindMetricWithWrongName() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThat(registry.findMetric(LongCounter.key(name + "_"))).isEmpty();
        }

        @Test
        public void testFindMetricWithWrongType() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThat(registry.findMetric(LongGauge.key(name))).isEmpty();
        }

        @Test
        public void testMetricLabelsAreTheSameWithoutGlobalLabels() {
            MetricRegistryImpl registry = new MetricRegistryImpl();
            Label label = new Label("key", "value");

            LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
            LongCounter counter2 =
                    registry.register(LongCounter.builder("counter2").withConstantLabel(label));

            assertThat(counter1.constantLabels()).isEmpty();
            assertThat(counter2.constantLabels()).containsExactly(label);
        }

        @Test
        public void testGlobalLabelsAddedToMetrics() {
            Label globsLabel = new Label("env", "test");
            Label label1 = new Label("a", "value1");
            Label label2 = new Label("z", "value2");

            MetricRegistryImpl registry = new MetricRegistryImpl(globsLabel);

            LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
            LongCounter counter2 =
                    registry.register(LongCounter.builder("counter2").withConstantLabels(label1, label2));

            assertThat(counter1.constantLabels()).containsExactly(globsLabel);
            assertThat(counter2.constantLabels()).containsExactly(label1, globsLabel, label2); // sorted alphabetically
        }

        @Test
        public void testSnapshotIsConsistent() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            UpdatableMetricRegistrySnapshot snapshot1 = registry.snapshot();
            UpdatableMetricRegistrySnapshot snapshot2 = registry.snapshot();

            assertThat(snapshot1).isSameAs(snapshot2);
        }

        @Test
        public void testSnapshotIsEmptyWithNoMetrics() {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            UpdatableMetricRegistrySnapshot snapshot = registry.snapshot();

            assertThat(snapshot.size()).isEqualTo(0);
            assertThat(snapshot.iterator().hasNext()).isFalse();
        }
    }

    @Nested
    class MultiThreaded {

        @ParameterizedTest(name = "{0}")
        @MethodSource("metricRegistrations")
        public void testMetricsViewIsConsistent(String name, BiConsumer<MetricRegistry, Integer> metricRegistration)
                throws InterruptedException {
            MetricRegistryImpl registry = new MetricRegistryImpl();

            int threadCount = 10;
            int metricsPerThread = 100;

            runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int j = 0; j < metricsPerThread; j++) {
                    metricRegistration.accept(registry, (threadIdx * metricsPerThread + j));
                }
            });

            assertThat(registry.metrics().size()).isEqualTo(threadCount * metricsPerThread);

            List<String> actualMetricNames = registry.metrics().stream()
                    .map(metric -> metric.metadata().name())
                    .toList();
            List<String> expectedMetricNames = IntStream.range(0, threadCount * metricsPerThread)
                    .mapToObj(i -> "counter_" + i)
                    .toList();
            assertThat(actualMetricNames).containsExactlyInAnyOrderElementsOf(expectedMetricNames);
        }

        private static List<Arguments> metricRegistrations() {
            return List.of(
                    Arguments.of("builder", (BiConsumer<MetricRegistry, Integer>)
                            (metricRegistry, id) -> metricRegistry.register(LongCounter.builder("counter_" + id))),
                    Arguments.of("provider", (BiConsumer<MetricRegistry, Integer>) (metricRegistry, id) ->
                            metricRegistry.registerMetrics(() -> List.of(LongCounter.builder("counter_" + id)))));
        }
    }
}
