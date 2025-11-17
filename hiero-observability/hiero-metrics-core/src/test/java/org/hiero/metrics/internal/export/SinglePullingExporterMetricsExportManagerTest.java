// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.TestUtils.verifySnapshotHasMetrics;
import static org.hiero.metrics.TestUtils.verifySnapshotHasMetricsAnyOrder;
import static org.hiero.metrics.test.fixtures.ThreadUtils.runConcurrentAndWait;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.PullingMetricsExporter;
import org.hiero.metrics.api.export.extension.PullingMetricsExporterAdapter;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.junit.jupiter.api.Test;

public class SinglePullingExporterMetricsExportManagerTest {

    @Test
    void testNullNameThrows() {
        assertThatThrownBy(() -> new SinglePullingExporterMetricsExportManager(null, createExporter()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testEmptyNameThrows() {
        assertThatThrownBy(() -> new SinglePullingExporterMetricsExportManager("", createExporter()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullExporterThrows() {
        assertThatThrownBy(() -> new SinglePullingExporterMetricsExportManager("test-manager", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("exporter must not be null");
    }

    @Test
    void testConflictingGlobalLabelsThrows() {
        MetricsExportManager manager = createManager();

        manager.manageMetricRegistry(MetricRegistry.builder()
                .addGlobalLabel(new Label("region", "us-east-1"))
                .build());
        MetricRegistry registry = MetricRegistry.builder()
                .addGlobalLabels(new Label("region", "us-east-1"))
                .build();

        assertThatThrownBy(() -> manager.manageMetricRegistry(registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Metric registry has duplicate global labels with another registry");
    }

    @Test
    void testBasicProperties() {
        MetricsExportManager manager = createManager();

        assertThat(manager.name()).isEqualTo("test-manager");

        // check no threads without and with managed registry
        assertThat(manager.hasRunningExportThread()).isFalse();
        manager.manageMetricRegistry(MetricRegistry.builder().build());
        assertThat(manager.hasRunningExportThread()).isFalse();
    }

    @Test
    void testSnapshotAvailableAfterRegistryManaged() {
        PullingMetricsExporterAdapter exporter = createExporter();
        MetricsExportManager manager = createManager(exporter);

        // initially no snapshot available
        assertThat(exporter.getSnapshot()).isEmpty();

        // after managing a registry, snapshot should be available
        manager.manageMetricRegistry(MetricRegistry.builder().build());
        assertThat(exporter.getSnapshot()).isPresent();
    }

    @Test
    void testManageSameRegistryInstanceTwiceThrows() {
        MetricsExportManager manager = createManager();
        MetricRegistry registry = MetricRegistry.builder().build();

        manager.manageMetricRegistry(registry);
        manager.manageMetricRegistry(MetricRegistry.builder().build());

        assertThatThrownBy(() -> manager.manageMetricRegistry(registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Metric registry instance is already managed");
    }

    @Test
    void testManageRegistriesWithoutConflictingGlobalLabels() {
        PullingMetricsExporterAdapter exporter = createExporter();
        MetricsExportManager manager = createManager(exporter);
        assertThat(exporter.getSnapshot()).isEmpty();

        MetricRegistry registry1 = MetricRegistry.builder()
                .addGlobalLabel(new Label("region", "us-east-1"))
                .build();
        registry1.register(LongCounter.builder("counter1"));
        manager.manageMetricRegistry(registry1);
        Optional<MetricsSnapshot> optionalSnapshot = exporter.getSnapshot();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetrics(optionalSnapshot.get(), "counter1");

        MetricRegistry registry2 = MetricRegistry.builder()
                .addGlobalLabel(new Label("region", "us-east-2"))
                .build();
        registry2.register(LongCounter.builder("counter2"));
        manager.manageMetricRegistry(registry2);
        optionalSnapshot = exporter.getSnapshot();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetrics(optionalSnapshot.get(), "counter1", "counter2");

        MetricRegistry registry3 = MetricRegistry.builder().build();
        registry3.register(LongCounter.builder("counter3"));
        manager.manageMetricRegistry(registry3);
        optionalSnapshot = exporter.getSnapshot();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetrics(optionalSnapshot.get(), "counter1", "counter2", "counter3");

        MetricRegistry registry4 = MetricRegistry.builder().build(); // second registry without global labels
        registry4.register(LongCounter.builder("counter4"));
        manager.manageMetricRegistry(registry4);
        optionalSnapshot = exporter.getSnapshot();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetrics(optionalSnapshot.get(), "counter1", "counter2", "counter3", "counter4");
    }

    @Test
    void testMockedExporterIniAndShutdown() throws IOException {
        PullingMetricsExporter exporter = mock(PullingMetricsExporter.class);
        MetricsExportManager manager = createManager(exporter);

        verify(exporter, never()).setSnapshotProvider(any());

        manager.manageMetricRegistry(MetricRegistry.builder().build());
        verify(exporter).setSnapshotProvider(any());

        manager.shutdown();
        verify(exporter).close();
    }

    @Test
    void testClosingExporterFails() throws IOException {
        PullingMetricsExporter exporter = mock(PullingMetricsExporter.class);
        MetricsExportManager manager = createManager(exporter);

        IOException ioException = new IOException("close failed");
        doThrow(ioException).when(exporter).close();

        assertThatThrownBy(manager::shutdown)
                .isInstanceOf(RuntimeException.class)
                .hasCause(ioException);
    }

    @Test
    void testConcurrentManageRegistries() throws InterruptedException {
        PullingMetricsExporterAdapter exporter = createExporter();
        MetricsExportManager manager = createManager(exporter);

        int threadCount = 10;
        int registriesPerThread = 100;
        final MetricRegistry[] registries = IntStream.range(0, threadCount * registriesPerThread)
                .mapToObj(i -> {
                    MetricRegistry registry = MetricRegistry.builder()
                            .addGlobalLabel(new Label("region", "region_" + i))
                            .build();
                    registry.register(LongCounter.builder("counter_" + i));
                    return registry;
                })
                .toArray(MetricRegistry[]::new);

        runConcurrentAndWait(threadCount, Duration.ofSeconds(1), (IntFunction<Runnable>) treadId -> () -> {
            for (int i = 0; i < registriesPerThread; i++) {
                int id = treadId * registriesPerThread + i;
                manager.manageMetricRegistry(registries[id]);
            }
        });

        String[] expectedCounters = IntStream.range(0, threadCount * registriesPerThread)
                .mapToObj(i -> "counter_" + i)
                .toArray(String[]::new);
        Optional<MetricsSnapshot> optionalSnapshot = exporter.getSnapshot();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetricsAnyOrder(optionalSnapshot.get(), expectedCounters);
    }

    private PullingMetricsExporterAdapter createExporter() {
        return new PullingMetricsExporterAdapter("test-exporter");
    }

    private MetricsExportManager createManager(PullingMetricsExporter exporter) {
        return new SinglePullingExporterMetricsExportManager("test-manager", exporter);
    }

    private MetricsExportManager createManager() {
        return createManager(createExporter());
    }
}
