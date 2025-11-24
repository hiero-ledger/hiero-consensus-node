// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.utils.MetricUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

public class MetricRegistryTest {

    private static final String DUPLICATE_NAME = "duplicate_name";

    private MetricRegistry.Builder testBuilder() {
        return MetricRegistry.builder("test_registry");
    }

    @Nested
    class Exceptions {

        @Test
        void testNullRegistryNameThrows() {
            assertThatThrownBy(() -> MetricRegistry.builder(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("cannot be null");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void testBlankRegistryNameThrows(String name) {
            assertThatThrownBy(() -> MetricRegistry.builder(name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be blank");
        }

        @Test
        void testAddNullGlobalLabelThrows() {
            MetricRegistry.Builder builder = testBuilder();

            assertThatThrownBy(() -> builder.withGlobalLabel(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("global label must not be null");
        }

        @Test
        void testDuplicateGlobalLabelNameThrows() {
            MetricRegistry.Builder builder = testBuilder();
            builder.withGlobalLabel(new Label("env", "test"));
            builder.withGlobalLabel(new Label("other", "test"));

            assertThatThrownBy(() -> builder.withGlobalLabel(new Label("env", "prod")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate global label name", "env");
        }

        @Test
        void testUnmodifiableEmptyGlobalLabels() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.globalLabels().add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testUnmodifiableGlobalLabels() {
            MetricRegistry registry =
                    testBuilder().withGlobalLabel(new Label("env", "test")).build();
            List<Label> globalLabels = registry.globalLabels();

            assertThatThrownBy(() -> globalLabels.add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(globalLabels::clear)
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testRegisterMetricWithBuilderAndLabelMatchingGlobalLabel() {
            MetricRegistry registry = testBuilder()
                    .withGlobalLabel(new Label("env", "test"))
                    .withGlobalLabel(new Label("region", "us-west-2"))
                    .build();

            assertThatThrownBy(() -> registry.register(
                            LongCounter.builder("test_counter").withConstantLabel(new Label("env", "production"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Label", "conflicts with existing", "env");
        }

        @Test
        void testUnmodifiableEmptyMetricsView() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.metrics().add(mock(Metric.class)))
                    .as("Metrics view is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testUnmodifiableMetricsView() {
            MetricRegistry registry = testBuilder().build();
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
        void testWithDiscoverMetricsProvidersNullConfigurationThrows() {
            MetricRegistry.Builder builder = testBuilder();

            assertThatThrownBy(() -> builder.withDiscoverMetricProviders(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("configuration must not be null");
        }

        @Test
        void testRegisterMetricsWithNullProviderThrows() {
            MetricsRegistrationProvider[] providers = new MetricsRegistrationProvider[] {null};

            assertThatThrownBy(() -> createRegistryMockDiscovery(providers))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics registration provider must not be null");
        }

        @Test
        void testRegisterMetricsWithProviderReturningNullMetricsThrows() {
            assertThatThrownBy(() -> createRegistryMockDiscovery(config -> null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics collection must not be null");
        }

        @Test
        void testRegisterNullBuilderThrows() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric builder must not be null");
        }

        @Test
        void testRegisterDuplicateMetricWithBuilderThrows() {
            MetricRegistry registry = testBuilder().build();
            registry.register(LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithSingleProviderThrows() {
            MetricsRegistrationProvider metricsProvider =
                    config -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> createRegistryMockDiscovery(metricsProvider))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithMultipleProvidersThrows() {
            MetricsRegistrationProvider provider1 =
                    config -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("metric1"));
            MetricsRegistrationProvider provider2 =
                    config -> List.of(LongCounter.builder("metric2"), LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> createRegistryMockDiscovery(provider1, provider2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithProviderAndBuilderThrows() {
            MetricRegistry registry = createRegistryMockDiscovery(
                    config -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("other")));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testFindMetricWithNullKeyThrows() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.findMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        void testGetMetricWithNullKeyThrows() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.getMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        void testGetMetricFromEmptyRegistryThrows() {
            MetricRegistry registry = testBuilder().build();

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key("unknown_metric")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        void testGetMetricWithWrongNameThrows() {
            MetricRegistry registry = testBuilder().build();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key(name + "_")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        void testGetMetricWithWrongTypeThrows() {
            MetricRegistry registry = testBuilder().build();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongGauge.key(name)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric not found");
        }
    }

    @Test
    void testNameMatches() {
        String registryName = "test_registry";
        MetricRegistry registry = MetricRegistry.builder(registryName).build();

        assertThat(registry.name()).isEqualTo(registryName);
    }

    @Test
    void testCreateEmptyRegistryGlobalLabels() {
        MetricRegistry registry = testBuilder().build();

        assertThat(registry.globalLabels()).isEmpty();
    }

    @Test
    void testGlobalLabelsNotEmpty() {
        Label label1 = new Label("env", "test");
        Label label2 = new Label("region", "us-west-2");

        MetricRegistry registry =
                testBuilder().withGlobalLabel(label1).withGlobalLabel(label2).build();

        assertThat(registry.globalLabels()).containsExactly(label1, label2);
    }

    @Test
    void testMetricLabelsAreTheSameWithoutGlobalLabels() {
        MetricRegistry registry = testBuilder().build();
        Label label = new Label("key", "value");

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2").withConstantLabel(label));

        assertThat(counter1.constantLabels()).isEmpty();
        assertThat(counter2.constantLabels()).containsExactly(label);
    }

    @Test
    void testGlobalLabelsAddedToMetrics() {
        Label globsLabel = new Label("env", "test");
        Label label1 = new Label("a", "value1");
        Label label2 = new Label("z", "value2");

        MetricRegistry registry = testBuilder().withGlobalLabel(globsLabel).build();

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2").withConstantLabels(label1, label2));

        assertThat(counter1.constantLabels()).containsExactly(globsLabel);
        assertThat(counter2.constantLabels()).containsExactly(label1, globsLabel, label2); // sorted alphabetically
    }

    @Test
    void testCreateEmptyMetricsViewNoDiscovery() {
        MetricRegistry registry = testBuilder().build();

        assertThat(registry.metrics()).isEmpty();
    }

    @Test
    void testCreateEmptyMetricsViewWithDiscovery() {
        MetricRegistry registry = createRegistryMockDiscovery();

        assertThat(registry.metrics()).isEmpty();
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterBuilders() {
        MetricRegistry registry = testBuilder().build();

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2"));

        assertThat(registry.metrics()).containsExactlyInAnyOrder(counter1, counter2);
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterProviders() {
        // additionally call discoverMetricProviders on builder multiple times to verify no duplication occurs
        MetricRegistry registry = createRegistryMockDiscovery(
                testBuilder()
                        .withDiscoverMetricProviders(mock(Configuration.class))
                        .withDiscoverMetricProviders(mock(Configuration.class)),
                config -> List.of(LongCounter.builder("counter1")),
                config -> List.of(LongCounter.builder("counter2"), LongCounter.builder("counter3")));

        assertThat(registry.metrics().size()).isEqualTo(3);
        assertThat(registry.metrics().stream().map(Metric::metadata).map(MetricMetadata::name))
                .containsExactlyInAnyOrder("counter1", "counter2", "counter3");
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterProvidersAndBuilders() {
        MetricRegistry registry = createRegistryMockDiscovery(
                config -> List.of(LongCounter.builder("counter1")),
                config -> List.of(LongCounter.builder("counter2"), LongCounter.builder("counter3")));

        registry.register(LongCounter.builder("counter4"));
        registry.register(LongCounter.builder("counter5"));

        assertThat(registry.metrics().size()).isEqualTo(5);
        assertThat(registry.metrics().stream().map(Metric::metadata).map(MetricMetadata::name))
                .containsExactlyInAnyOrder("counter1", "counter2", "counter3", "counter4", "counter5");
    }

    @Test
    void testMetricFoundWithBuilderRegistration() {
        MetricRegistry registry = testBuilder().build();
        String metricName = "test_metric";

        LongCounter registeredCounter = registry.register(LongCounter.builder(metricName));

        assertThat(registry.findMetric(LongCounter.key(metricName))).isPresent().containsSame(registeredCounter);
        assertThat(registry.getMetric(LongCounter.key(metricName))).isSameAs(registeredCounter);
    }

    @Test
    void testMetricFoundWithProviderRegistration() {
        String metricName = "test_metric";
        MetricRegistry registry = createRegistryMockDiscovery(config -> List.of(LongCounter.builder(metricName)));

        Optional<LongCounter> metric = registry.findMetric(LongCounter.key(metricName));
        assertThat(metric).isPresent();
        assertThat(registry.getMetric(LongCounter.key(metricName))).isSameAs(metric.get());
    }

    @Test
    void testFindMetricWithWrongName() {
        MetricRegistry registry = testBuilder().build();
        String name = "test_metric";
        registry.register(LongCounter.builder(name));

        assertThat(registry.findMetric(LongCounter.key(name + "_"))).isEmpty();
    }

    @Test
    void testFindMetricWithWrongType() {
        MetricRegistry registry = testBuilder().build();
        String name = "test_metric";
        registry.register(LongCounter.builder(name));

        assertThat(registry.findMetric(LongGauge.key(name))).isEmpty();
    }

    @Test
    void testReset() {
        MetricRegistry registry = testBuilder().build();

        LongCounter counter = registry.register(LongCounter.builder("counter"));
        LongGauge gauge = registry.register(LongGauge.builder("gauge"));

        counter.getOrCreateNotLabeled().increment(100L);
        gauge.getOrCreateNotLabeled().update(200L);

        registry.reset();

        assertThat(counter.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);
        assertThat(gauge.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);
    }

    @Test
    void testConcurrentMetricsRegistrations() throws InterruptedException {
        MetricRegistry registry = testBuilder().build();

        int threadCount = 10;
        int metricsPerThread = 100;

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int j = 0; j < metricsPerThread; j++) {
                registry.register(LongCounter.builder("counter_" + (threadIdx * metricsPerThread + j)));
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

    private MetricRegistry createRegistryMockDiscovery(MetricsRegistrationProvider... metricProviders) {
        return createRegistryMockDiscovery(testBuilder(), metricProviders);
    }

    private MetricRegistry createRegistryMockDiscovery(
            MetricRegistry.Builder builder, MetricsRegistrationProvider... metricProviders) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsRegistrationProvider.class))
                    .thenReturn(Arrays.asList(metricProviders));
            return builder.withDiscoverMetricProviders(mock(Configuration.class))
                    .build();
        }
    }
}
