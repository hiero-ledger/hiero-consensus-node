// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.hiero.metrics.DoubleCounter;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.ThreadUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

public class MetricRegistryTest {

    private static final String DUPLICATE_NAME = "duplicate_name";

    @Nested
    class Exceptions {

        @Test
        void testAddNullGlobalLabelThrows() {
            MetricRegistry.Builder builder = MetricRegistry.builder();

            assertThatThrownBy(() -> builder.addGlobalLabel(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("global label must not be null");
        }

        @Test
        void testDuplicateGlobalLabelNameThrows() {
            MetricRegistry.Builder builder = MetricRegistry.builder();
            builder.addGlobalLabel(new Label("env", "test"));
            builder.addGlobalLabel(new Label("other", "test"));

            assertThatThrownBy(() -> builder.addGlobalLabel(new Label("env", "prod")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate global label name", "env");
        }

        @Test
        void testUnmodifiableEmptyGlobalLabels() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.globalLabels().add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testUnmodifiableGlobalLabels() {
            MetricRegistry registry = MetricRegistry.builder()
                    .addGlobalLabel(new Label("env", "test"))
                    .build();
            List<Label> globalLabels = registry.globalLabels();

            assertThatThrownBy(() -> globalLabels.add(new Label("key", "value")))
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(globalLabels::clear)
                    .as("Global labels list is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testRegisterMetricWithLabelMatchingGlobalLabel() {
            MetricRegistry registry = MetricRegistry.builder()
                    .addGlobalLabel(new Label("env", "test"))
                    .addGlobalLabel(new Label("region", "us-west-2"))
                    .build();

            assertThatThrownBy(() -> registry.register(
                            LongCounter.builder("test_counter").addStaticLabels(new Label("env", "production"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("conflicts with existing", "env");
        }

        @Test
        void testUnmodifiableEmptyMetricsView() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.metrics().add(mock(Metric.class)))
                    .as("Metrics view is unmodifiable")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testUnmodifiableMetricsView() {
            MetricRegistry registry = MetricRegistry.builder().build();
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
        void testRegisterMetricsWithNullProviderThrows() {
            MetricsRegistrationProvider[] providers = new MetricsRegistrationProvider[] {null};

            assertThatThrownBy(() -> createRegistryMockDiscovery(providers))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics registration provider must not be null");
        }

        @Test
        void testRegisterMetricsWithProviderReturningNullMetricsThrows() {
            assertThatThrownBy(() -> createRegistryMockDiscovery(() -> null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics collection must not be null");
        }

        @Test
        void testRegisterNullBuilderThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric builder must not be null");
        }

        @Test
        void testRegisterDuplicateMetricWithBuilderThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();
            registry.register(LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithSingleProviderThrows() {
            MetricsRegistrationProvider metricsProvider =
                    () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> createRegistryMockDiscovery(metricsProvider))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithMultipleProvidersThrows() {
            MetricsRegistrationProvider provider1 =
                    () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("metric1"));
            MetricsRegistrationProvider provider2 =
                    () -> List.of(LongCounter.builder("metric2"), LongCounter.builder(DUPLICATE_NAME));

            assertThatThrownBy(() -> createRegistryMockDiscovery(provider1, provider2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testRegisterDuplicateMetricsWithProviderAndBuilderThrows() {
            MetricRegistry registry = createRegistryMockDiscovery(
                    () -> List.of(LongCounter.builder(DUPLICATE_NAME), LongCounter.builder("other")));

            assertThatThrownBy(() -> registry.register(LongCounter.builder(DUPLICATE_NAME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("Duplicate metric name", DUPLICATE_NAME);
        }

        @Test
        void testContainsMetricWithNullKeyThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.containsMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        void testGetMetricWithNullKeyThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.getMetric(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metric key must not be null");
        }

        @Test
        void testGetMetricFromEmptyRegistryThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key("unknown_metric")))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        void testGetMetricWithWrongNameThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongCounter.key(name + "_")))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Metric not found");
        }

        @Test
        void testGetMetricWithWrongTypeThrows() {
            MetricRegistry registry = MetricRegistry.builder().build();
            String name = "test_metric";
            registry.register(LongCounter.builder(name));

            assertThatThrownBy(() -> registry.getMetric(LongGauge.key(name))).isInstanceOf(ClassCastException.class);
        }

        @Test
        void testSetNullMetricsExportsThrows() {
            MetricRegistry.Builder builder = MetricRegistry.builder();

            assertThatThrownBy(() -> builder.setMetricsExporter(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metrics exporter must not be null");
        }
    }

    @Test
    void testEmptyRegistryProperties() {
        MetricRegistry registry = MetricRegistry.builder().build();

        assertThat(registry.globalLabels()).isEmpty();
        assertThat(registry.metrics()).isEmpty();
        assertThat(registry.hasMetricsExporter()).isFalse();
    }

    @Test
    void testGlobalLabelsNotEmpty() {
        Label label1 = new Label("env", "test");
        Label label2 = new Label("region", "us-west-2");

        MetricRegistry registry = MetricRegistry.builder()
                .addGlobalLabel(label1)
                .addGlobalLabel(label2)
                .build();

        assertThat(registry.globalLabels()).containsExactly(label1, label2);
    }

    @Test
    void testMetricLabelsAreTheSameWithoutGlobalLabels() {
        MetricRegistry registry = MetricRegistry.builder().build();
        Label label = new Label("key", "value");

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2").addStaticLabels(label));

        assertThat(counter1.staticLabels()).isEmpty();
        assertThat(counter2.staticLabels()).containsExactly(label);
    }

    @Test
    void testGlobalLabelsAddedToMetrics() {
        Label globsLabel = new Label("env", "test");
        Label label1 = new Label("a", "value1");
        Label label2 = new Label("z", "value2");

        MetricRegistry registry =
                MetricRegistry.builder().addGlobalLabel(globsLabel).build();

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2").addStaticLabels(label1, label2));

        assertThat(counter1.staticLabels()).containsExactly(globsLabel);
        assertThat(counter2.staticLabels()).containsExactly(label1, globsLabel, label2); // sorted alphabetically
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterBuilders() {
        MetricRegistry registry = MetricRegistry.builder().build();

        LongCounter counter1 = registry.register(LongCounter.builder("counter1"));
        LongCounter counter2 = registry.register(LongCounter.builder("counter2"));

        assertThat(registry.metrics()).containsExactlyInAnyOrder(counter1, counter2);
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterProviders() {
        MetricRegistry registry = createRegistryMockDiscovery(
                () -> List.of(LongCounter.builder("counter1")),
                () -> List.of(LongCounter.builder("counter2"), LongCounter.builder("counter3")));

        assertThat(registry.metrics().size()).isEqualTo(3);
        assertThat(registry.metrics().stream().map(Metric::name))
                .containsExactlyInAnyOrder("counter1", "counter2", "counter3");
    }

    @Test
    void testMetricsViewNonEmptyAfterRegisterProvidersAndBuilders() {
        MetricRegistry registry = createRegistryMockDiscovery(
                () -> List.of(LongCounter.builder("counter1")),
                () -> List.of(LongCounter.builder("counter2"), LongCounter.builder("counter3")));

        registry.register(LongCounter.builder("counter4"));
        registry.register(LongCounter.builder("counter5"));

        assertThat(registry.metrics().size()).isEqualTo(5);
        assertThat(registry.metrics().stream().map(Metric::name))
                .containsExactlyInAnyOrder("counter1", "counter2", "counter3", "counter4", "counter5");
    }

    @Test
    void testMetricFoundWithBuilderRegistration() {
        MetricRegistry registry = MetricRegistry.builder().build();
        String metricName = "test_metric";

        LongCounter registeredCounter = registry.register(LongCounter.builder(metricName));

        assertThat(registry.containsMetric(LongCounter.key(metricName))).isTrue();
        assertThat(registry.getMetric(LongCounter.key(metricName))).isSameAs(registeredCounter);
    }

    @Test
    void testMetricFoundWithProviderRegistration() {
        String metricName = "test_metric";
        MetricRegistry registry = createRegistryMockDiscovery(() -> List.of(LongCounter.builder(metricName)));

        assertThat(registry.containsMetric(LongCounter.key(metricName))).isTrue();

        LongCounter metric = registry.getMetric(LongCounter.key(metricName));
        assertThat(metric).isNotNull();
        assertThat(metric.name()).isEqualTo(metricName);
    }

    @Test
    void testFindMetricWithWrongName() {
        MetricRegistry registry = MetricRegistry.builder().build();
        String name = "test_metric";
        registry.register(LongCounter.builder(name));

        assertThat(registry.containsMetric(LongCounter.key(name + "_"))).isFalse();
    }

    @Test
    void testFindMetricWithWrongType() {
        MetricRegistry registry = MetricRegistry.builder().build();
        String name = "test_metric";
        registry.register(LongCounter.builder(name));

        assertThat(registry.containsMetric(LongGauge.key(name))).isFalse();
    }

    @Test
    void testNullSnapshotWhenNoExporter() {
        MetricRegistry registry = MetricRegistry.builder().build();

        LongCounter counter = registry.register(LongCounter.builder("test_counter"));
        counter.getOrCreateNotLabeled().increment();

        LongGauge gauge = registry.register(LongGauge.builder("test_gauge").addDynamicLabelNames("label"));
        gauge.getOrCreateLabeled("label", "1").set(10);

        MetricSnapshotVerifier.verifMetricHasNoSnapshot(counter);
        MetricSnapshotVerifier.verifMetricHasNoSnapshot(gauge);
    }

    @Test
    void testConcurrentMetricsRegistrations() throws InterruptedException {
        MetricRegistry registry = MetricRegistry.builder().build();

        int threadCount = 10;
        int metricsPerThread = 100;

        ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
            for (int j = 0; j < metricsPerThread; j++) {
                registry.register(LongCounter.builder("counter_" + (threadIdx * metricsPerThread + j)));
            }
        });

        assertThat(registry.metrics().size()).isEqualTo(threadCount * metricsPerThread);

        List<String> actualMetricNames =
                registry.metrics().stream().map(Metric::name).toList();
        List<String> expectedMetricNames = IntStream.range(0, threadCount * metricsPerThread)
                .mapToObj(i -> "counter_" + i)
                .toList();
        assertThat(actualMetricNames).containsExactlyInAnyOrderElementsOf(expectedMetricNames);
    }

    @Test
    void testDiscoverAvailableMetricsExporterFactoryAndExportDisabled() {
        Configuration config = ConfigurationBuilder.create()
                .withValue("hiero.metrics.export.discovery.diasbled", "true")
                .build();

        MetricsExporterFactory exporterFactory = mock(MetricsExporterFactory.class);
        MetricsExporter exporter = mock(MetricsExporter.class);
        when(exporterFactory.createExporter(any(), any())).thenReturn(exporter);

        MetricRegistry registry = createRegistryMockDiscovery(config, exporterFactory);

        assertThat(registry.hasMetricsExporter()).isFalse();
        verifyNoInteractions(exporterFactory);
    }

    @Test
    void testDiscoverEmptyExportFactories() {
        MetricRegistry registry =
                createRegistryMockDiscovery(ConfigurationBuilder.create().build());

        assertThat(registry.hasMetricsExporter()).isFalse();
    }

    @Test
    void testDiscoverMultipleExportFactories() {
        MetricsExporterFactory exporterFactory1 = mock(MetricsExporterFactory.class);
        MetricsExporter exporter1 = mock(MetricsExporter.class);
        when(exporterFactory1.createExporter(any(), any())).thenReturn(exporter1);

        MetricsExporterFactory exporterFactory2 = mock(MetricsExporterFactory.class);
        MetricsExporter exporter2 = mock(MetricsExporter.class);
        when(exporterFactory2.createExporter(any(), any())).thenReturn(exporter2);

        MetricRegistry registry =
                createRegistryMockDiscovery(ConfigurationBuilder.create().build(), exporterFactory1, exporterFactory2);

        assertThat(registry.hasMetricsExporter()).isFalse();
        verifyNoInteractions(exporterFactory1, exporterFactory2);
    }

    @Test
    void testDiscoverSingleExportFactoryCreatingNullExporter() {
        Configuration config = ConfigurationBuilder.create().build();
        Label globalLabel = new Label("env", "test");
        MetricRegistry.Builder builder = MetricRegistry.builder().addGlobalLabel(globalLabel);

        MetricsExporterFactory exporterFactory = mock(MetricsExporterFactory.class);
        when(exporterFactory.createExporter(List.of(globalLabel), config)).thenReturn(null);

        MetricRegistry registry = createRegistryMockDiscovery(builder, config, exporterFactory);

        assertThat(registry.hasMetricsExporter()).isFalse();
        verify(exporterFactory).createExporter(registry.globalLabels(), config);
    }

    @Test
    void testDiscoverDiscoverExporterOverridesDefaultExporter() {
        Configuration config = ConfigurationBuilder.create().build();
        Label globalLabel = new Label("env", "test");
        MetricsExporter defaultExporter = mock(MetricsExporter.class);
        MetricsExporter discoverExporter = mock(MetricsExporter.class);

        MetricRegistry.Builder builder =
                MetricRegistry.builder().setMetricsExporter(defaultExporter).addGlobalLabel(globalLabel);

        MetricsExporterFactory exporterFactory = mock(MetricsExporterFactory.class);
        when(exporterFactory.createExporter(List.of(globalLabel), config)).thenReturn(discoverExporter);

        MetricRegistry registry = createRegistryMockDiscovery(builder, config, exporterFactory);

        assertThat(registry.hasMetricsExporter()).isTrue();
        verifyNoInteractions(defaultExporter);
        verify(exporterFactory).createExporter(registry.globalLabels(), config);
        verify(discoverExporter).setSnapshotSupplier(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exportTestParameters")
    void testExport(String name, MetricRegistry registry, TestMetricsExporter exporter) throws IOException {
        // register metrics
        LongCounter longCounter = registry.register(LongCounter.builder("counter:long"));
        DoubleCounter doubleCounter =
                registry.register(DoubleCounter.builder("counter:double").addStaticLabels(new Label("sl1", "static1")));
        LongGauge longGauge = registry.register(LongGauge.builder("gauge:long").addDynamicLabelNames("dl1", "dl2"));
        DoubleGauge doubleGauge = registry.register(DoubleGauge.builder("gauge:double")
                .addStaticLabels(new Label("sl2", "static2"))
                .addDynamicLabelNames("dl"));

        // should be empty before observations
        exporter.verify(new MetricRegistrySnapshotVerifier()
                .add(new MetricSnapshotVerifier(longCounter))
                .add(new MetricSnapshotVerifier(doubleCounter))
                .add(new MetricSnapshotVerifier(longGauge))
                .add(new MetricSnapshotVerifier(doubleGauge)));

        // update metrics 1
        longCounter.getOrCreateNotLabeled().increment();
        doubleCounter.getOrCreateNotLabeled().increment(5.5);
        longGauge.getOrCreateLabeled("dl1", "v11", "dl2", "v21"); // just initialize
        longGauge.getOrCreateLabeled(() -> 123L, "dl1", "v11", "dl2", "v22"); // just initialize
        doubleGauge.getOrCreateLabeled("dl", "v1").set(200.5); // new label combination

        exporter.verify(new MetricRegistrySnapshotVerifier()
                .add(new MetricSnapshotVerifier(longCounter).add(1L))
                .add(new MetricSnapshotVerifier(doubleCounter).add(5.5))
                .add(new MetricSnapshotVerifier(longGauge)
                        .add(0L, "dl1", "v11", "dl2", "v21")
                        .add(123L, "dl1", "v11", "dl2", "v22"))
                .add(new MetricSnapshotVerifier(doubleGauge).add(200.5, "dl", "v1")));

        // update metrics 2
        longCounter.getOrCreateNotLabeled().increment(4L);
        doubleCounter.getOrCreateNotLabeled().increment(0.5);
        longGauge.getOrCreateLabeled("dl1", "v11", "dl2", "v21").set(-1L); // update existing label combination
        longGauge.getOrCreateLabeled("dl1", "v11", "dl2", "v22").set(-2L); // update existing label combination
        longGauge.getOrCreateLabeled("dl1", "v11", "dl2", "v23").set(100L); // new label combination
        doubleGauge.getOrCreateLabeled("dl", "v2").set(-10.5); // new label combination

        exporter.verify(new MetricRegistrySnapshotVerifier()
                .add(new MetricSnapshotVerifier(longCounter).add(5L))
                .add(new MetricSnapshotVerifier(doubleCounter).add(6.0))
                .add(new MetricSnapshotVerifier(longGauge)
                        .add(-1L, "dl1", "v11", "dl2", "v21")
                        .add(-2L, "dl1", "v11", "dl2", "v22")
                        .add(100L, "dl1", "v11", "dl2", "v23"))
                .add(new MetricSnapshotVerifier(doubleGauge)
                        .add(200.5, "dl", "v1")
                        .add(-10.5, "dl", "v2")));

        // reset metrics
        registry.reset();
        exporter.verify(new MetricRegistrySnapshotVerifier()
                .add(new MetricSnapshotVerifier(longCounter).add(0L))
                .add(new MetricSnapshotVerifier(doubleCounter).add(0.0))
                .add(new MetricSnapshotVerifier(longGauge)
                        .add(0L, "dl1", "v11", "dl2", "v21")
                        .add(123L, "dl1", "v11", "dl2", "v22")
                        .add(0L, "dl1", "v11", "dl2", "v23"))
                .add(new MetricSnapshotVerifier(doubleGauge)
                        .add(0.0, "dl", "v1")
                        .add(0.0, "dl", "v2")));

        // close registry
        registry.close();
        exporter.verifyClosed();
    }

    private static Object[][] exportTestParameters() {
        // test1
        TestMetricsExporter exporter1 = new TestMetricsExporter();

        // test2
        TestMetricsExporter exporter2 = new TestMetricsExporter();
        MetricsExporterFactory factory2 = mock(MetricsExporterFactory.class);
        when(factory2.createExporter(any(), any())).thenReturn(exporter2);

        // test3
        TestMetricsExporter defaultExporter3 = new TestMetricsExporter();
        TestMetricsExporter discoverExporter3 = new TestMetricsExporter();
        MetricsExporterFactory factory3 = mock(MetricsExporterFactory.class);
        when(factory3.createExporter(any(), any())).thenReturn(discoverExporter3);

        return new Object[][] {
            {
                "Default Exporter",
                MetricRegistry.builder().setMetricsExporter(exporter1).build(),
                exporter1
            },
            {
                "Discovered Exporter",
                createRegistryMockDiscovery(ConfigurationBuilder.create().build(), factory2),
                exporter2
            },
            {
                "Override Exporter",
                createRegistryMockDiscovery(
                        MetricRegistry.builder().setMetricsExporter(defaultExporter3),
                        ConfigurationBuilder.create().build(),
                        factory3),
                discoverExporter3
            }
        };
    }

    private static MetricRegistry createRegistryMockDiscovery(MetricsRegistrationProvider... metricProviders) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsRegistrationProvider.class))
                    .thenReturn(Arrays.asList(metricProviders));
            return MetricRegistry.builder()
                    .discoverMetricProviders()
                    .discoverMetricProviders() // call one more time to ensure it doesn't impact result
                    .build();
        }
    }

    private static MetricRegistry createRegistryMockDiscovery(
            Configuration config, MetricsExporterFactory... exportFactories) {
        return createRegistryMockDiscovery(MetricRegistry.builder(), config, exportFactories);
    }

    private static MetricRegistry createRegistryMockDiscovery(
            MetricRegistry.Builder builder, Configuration config, MetricsExporterFactory... exportFactories) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsExporterFactory.class))
                    .thenReturn(Arrays.asList(exportFactories));
            return builder
                    // call on empty config and then on provided config to ensure config is passed correctly
                    .discoverMetricsExporter(ConfigurationBuilder.create().build())
                    .discoverMetricsExporter(config)
                    .build();
        }
    }

    private static class TestMetricsExporter implements MetricsExporter {

        private boolean closed = false;
        private Supplier<MetricRegistrySnapshot> snapshotSupplier;

        @Override
        public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
            this.snapshotSupplier = snapshotSupplier;
        }

        public void verify(MetricRegistrySnapshotVerifier verifier) {
            verifier.verify(snapshotSupplier.get());
        }

        @Override
        public void close() {
            closed = true;
        }

        public void verifyClosed() {
            assertThat(closed).isTrue();
        }
    }
}
