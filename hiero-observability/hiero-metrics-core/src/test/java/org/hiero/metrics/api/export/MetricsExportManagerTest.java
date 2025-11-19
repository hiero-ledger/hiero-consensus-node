// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.TestUtils.verifySnapshotHasMetricsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.export.NoOpMetricsExportManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class MetricsExportManagerTest {

    @Nested
    class ExceptionTests {

        @Test
        void testNullExecutorThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            assertThatThrownBy(() -> builder.withExecutorServiceFactory(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("executor factory must not be null");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -2, Integer.MIN_VALUE})
        void testNegativeExportIntervalThrows(int interval) {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            assertThatThrownBy(() -> builder.withExportIntervalSeconds(interval))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("export interval seconds must be positive");
        }

        @Test
        void testNullPullingExportersThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            PullingMetricsExporter exporter = null;

            assertThatThrownBy(() -> builder.addExporter(exporter))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("exporter must not be null");
        }

        @Test
        void testNullExporterNAmeThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            PullingMetricsExporter exporter = mockPulling(null);

            assertThatThrownBy(() -> builder.addExporter(exporter))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("exporter name must not be null");
        }

        @Test
        void testNullPushingExportersThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            PushingMetricsExporter exporter = null;

            assertThatThrownBy(() -> builder.addExporter(exporter))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("exporter must not be null");
        }

        @Test
        void testDuplicateExportersThrows() {
            PullingMetricsExporter pulling = mockPulling("duplicate-exporter");
            PushingMetricsExporter pushing = mockPushing("duplicate-exporter");

            MetricsExportManager.Builder builder1 =
                    MetricsExportManager.builder().addExporter(pulling);
            assertThatThrownBy(() -> builder1.addExporter(pushing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Duplicate exporter name: duplicate-exporter");

            MetricsExportManager.Builder builder2 =
                    MetricsExportManager.builder().addExporter(pushing);
            assertThatThrownBy(() -> builder2.addExporter(pulling))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Duplicate exporter name: duplicate-exporter");

            MetricsExportManager.Builder builder3 =
                    MetricsExportManager.builder().addExporter(pushing);
            assertThatThrownBy(() -> builder3.addExporter(pushing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Duplicate exporter name: duplicate-exporter");

            MetricsExportManager.Builder builder4 =
                    MetricsExportManager.builder().addExporter(pulling);
            assertThatThrownBy(() -> builder4.addExporter(pulling))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Duplicate exporter name: duplicate-exporter");
        }

        @Test
        void testNullConfigurationInDiscoverExportersThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            assertThatThrownBy(() -> builder.withDiscoverExporters(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("configuration must not be null");
        }

        @Test
        void testNullMetricsRegistryThrows() {
            MetricsExportManager.Builder builder = MetricsExportManager.builder();
            assertThatThrownBy(() -> builder.build(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("metrics registry must not be null");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noOpExportManagerConfigArgs")
    void testNoOpExportManagerWithEmptyBuilder(String name, MetricsExportManager exportManager) {
        try (exportManager) {
            verifyNoOp(exportManager);
        }
    }

    private static Object[][] noOpExportManagerConfigArgs() {
        return new Object[][] {
            {
                "emptyBuilder",
                MetricsExportManager.builder()
                        .build(MetricRegistry.builder("test").build())
            },
            {
                "disabledExporting",
                MetricsExportManager.builder()
                        .addExporter(mockPulling("pulling-exporter"))
                        .addExporter(mockPushing("pushing-exporter"))
                        .withDiscoverExporters(configBuilder()
                                .withValue("metrics.export.manager.enabled", "false")
                                .build())
                        .build(MetricRegistry.builder("test").build())
            },
            {
                "noDiscoveredExporters",
                createExportManagerMockDiscovery(configBuilder().build())
            }
        };
    }

    @Test
    void testAllExportersAreFilteredOrFailedToCreate() throws MetricsExportException {
        MetricRegistry registry = MetricRegistry.builder("test_registry").build();

        Configuration configuration = configBuilder()
                .withValue(
                        "metrics.export.manager.disabledExporters",
                        "factory-discovery-disabled,pushing-disabled,pulling-disabled")
                .build();

        MetricsExporterFactory failingExporterFactory = mock(MetricsExporterFactory.class);
        when(failingExporterFactory.name()).thenReturn("failing");
        when(failingExporterFactory.createExporter("test_registry", configuration))
                .thenThrow(new RuntimeException("test exception"));

        MetricsExporterFactory emptyExporterFactory = mock(MetricsExporterFactory.class);
        when(emptyExporterFactory.name()).thenReturn("empty");
        when(emptyExporterFactory.createExporter("test_registry", configuration))
                .thenReturn(Optional.empty());

        MetricsExporterFactory disabledExporterFactory = mock(MetricsExporterFactory.class);
        when(disabledExporterFactory.name()).thenReturn("factory-discovery-disabled");

        PushingMetricsExporter disabledPushingExporter = mockPushing("pushing-disabled");
        PullingMetricsExporter disabledPullingExporter = mockPulling("pulling-disabled");

        MetricsExportManager exportManager = createExportManagerMockDiscovery(
                MetricsExportManager.builder()
                        .addExporter(disabledPushingExporter)
                        .addExporter(disabledPullingExporter),
                registry,
                configuration,
                failingExporterFactory,
                emptyExporterFactory,
                disabledExporterFactory);

        // wrap in try block to ensure close is called even if exceptions are thrown
        try (exportManager) {
            verifyNoOp(exportManager);

            verify(failingExporterFactory).createExporter("test_registry", configuration);
            verify(emptyExporterFactory).createExporter("test_registry", configuration);

            verify(disabledExporterFactory, never()).createExporter("test_registry", configuration);
            verify(disabledPushingExporter, never()).export(any());
            verify(disabledPullingExporter, never()).setSnapshotProvider(any());
        }
    }

    @Test
    void testSinglePullingExporter() throws IOException {
        PullingMetricsExporter pullingExporter = mockPulling("pulling-exporter");

        MetricRegistry registry = MetricRegistry.builder("test_registry").build();
        registry.register(LongCounter.builder("test_counter"));

        MetricsExportManager exportManager =
                MetricsExportManager.builder().addExporter(pullingExporter).build(registry);

        // wrap in try block to ensure close is called even if exceptions are thrown
        try (exportManager) {
            assertThat(exportManager.hasRunningExportThread()).isFalse();
            assertThat(exportManager.registry()).isSameAs(registry);

            ArgumentCaptor<Supplier<Optional<MetricsSnapshot>>> snapshotSupplierCaptor =
                    ArgumentCaptor.forClass(Supplier.class);
            verify(pullingExporter).setSnapshotProvider(snapshotSupplierCaptor.capture());
            Supplier<Optional<MetricsSnapshot>> snapshotSupplier = snapshotSupplierCaptor.getValue();
            Optional<MetricsSnapshot> optionalSnapshot = snapshotSupplier.get();
            assertThat(optionalSnapshot).isNotEmpty();
            verifySnapshotHasMetricsInOrder(optionalSnapshot.get(), "test_counter");

            exportManager.shutdown();
            verify(pullingExporter).close();
        }
    }

    @Test
    void testMockedScheduledExecutorForPushingExporter() throws IOException {
        PushingMetricsExporter pushingExporter = mockPushing("pushing-exporter");
        MetricRegistry registry = MetricRegistry.builder("test_registry").build();

        ScheduledFuture exportThreadFuture = mock(ScheduledFuture.class);
        when(exportThreadFuture.isDone()).thenReturn(false);

        ScheduledExecutorService executorService = mock(ScheduledExecutorService.class);
        when(executorService.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(exportThreadFuture);
        Supplier<ScheduledExecutorService> executorServiceFactory = () -> executorService;

        MetricsExportManager exportManager = MetricsExportManager.builder()
                .addExporter(pushingExporter)
                .withExportIntervalSeconds(15)
                .withExecutorServiceFactory(executorServiceFactory)
                .build(registry);

        // wrap in try block to ensure close is called even if exceptions are thrown
        try (exportManager) {
            assertThat(exportManager.hasRunningExportThread()).isTrue();
            assertThat(exportManager.registry()).isSameAs(registry);

            verify(executorService).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(15L), eq(TimeUnit.SECONDS));

            exportManager.shutdown();
            verify(exportThreadFuture).isDone();
            verify(exportThreadFuture).cancel(false);

            // try to shut down again to verify idempotency
            exportManager.shutdown();
            verifyNoMoreInteractions(exportThreadFuture);
            verifyNoMoreInteractions(executorService);
            verify(pushingExporter, times(1)).close(); // closed only once
        }
    }

    @Test
    void testMockedScheduledExecutorFailingExporters() throws IOException {
        PushingMetricsExporter pushingExporter = mockPushing("pushing-exporter");
        doThrow(new RuntimeException()).when(pushingExporter).close();

        PullingMetricsExporter pullingExporter = mockPulling("pulling-exporter");
        doThrow(new RuntimeException()).when(pullingExporter).setSnapshotProvider(any());
        doThrow(new RuntimeException()).when(pullingExporter).close();

        MetricRegistry registry = MetricRegistry.builder("test_registry").build();

        ScheduledFuture exportThreadFuture = mock(ScheduledFuture.class);
        when(exportThreadFuture.isDone()).thenReturn(false);

        ScheduledExecutorService executorService = mock(ScheduledExecutorService.class);
        when(executorService.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(exportThreadFuture);
        Supplier<ScheduledExecutorService> executorServiceFactory = () -> executorService;

        MetricsExportManager exportManager = MetricsExportManager.builder()
                .addExporter(pushingExporter)
                .addExporter(pullingExporter)
                .withExecutorServiceFactory(executorServiceFactory)
                .build(registry);

        // wrap in try block to ensure close is called even if exceptions are thrown
        try (exportManager) {
            assertThat(exportManager.hasRunningExportThread()).isTrue();
            assertThat(exportManager.registry()).isSameAs(registry);

            verify(executorService)
                    .scheduleAtFixedRate(
                            any(Runnable.class),
                            eq(0L),
                            eq(3L), // default interval
                            eq(TimeUnit.SECONDS));

            exportManager.shutdown();
            verify(exportThreadFuture).isDone();
            verify(exportThreadFuture).cancel(false);

            // try to shut down again to verify idempotency
            exportManager.shutdown();
            verifyNoMoreInteractions(exportThreadFuture);
            verifyNoMoreInteractions(executorService);
            verify(pushingExporter, times(1)).close(); // closed only once
            verify(pullingExporter, times(1)).close(); // closed only once
        }
    }

    @Test
    void testMockedScheduledExecutorRunnablePropagatesSnapshots() throws MetricsExportException, IOException {
        MetricRegistry registry = MetricRegistry.builder("test_registry").build();
        registry.register(LongCounter.builder("test_counter"));

        PushingMetricsExporter pushingExporter = mockPushing("pushing-exporter");
        PushingMetricsExporter pushingExporterFailingRuntime = mockPushing("pushing-exporter-failing-runtime");
        doThrow(new RuntimeException()).when(pushingExporterFailingRuntime).export(any());
        PushingMetricsExporter pushingExporterFailingChecked = mockPushing("pushing-exporter-failing-checked");
        doThrow(new MetricsExportException(""))
                .when(pushingExporterFailingChecked)
                .export(any());

        PullingMetricsExporter pullingExporter = mockPulling("pulling-exporter");

        ScheduledFuture exportThreadFuture = mock(ScheduledFuture.class);
        when(exportThreadFuture.isDone()).thenReturn(false);

        ScheduledExecutorService executorService = mock(ScheduledExecutorService.class);
        when(executorService.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
                .thenReturn(exportThreadFuture);
        Supplier<ScheduledExecutorService> executorServiceFactory = () -> executorService;

        MetricsExportManager exportManager = MetricsExportManager.builder()
                .addExporter(pushingExporter)
                .addExporter(pushingExporterFailingRuntime)
                .addExporter(pushingExporterFailingChecked)
                .addExporter(pullingExporter)
                .withExecutorServiceFactory(executorServiceFactory)
                .build(registry);

        // wrap in try block to ensure close is called even if exceptions are thrown
        try (exportManager) {
            assertThat(exportManager.hasRunningExportThread()).isTrue();
            assertThat(exportManager.registry()).isSameAs(registry);

            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(executorService).scheduleAtFixedRate(captor.capture(), eq(0L), eq(3L), eq(TimeUnit.SECONDS));

            Runnable exportRunnable = captor.getValue();
            assertThat(exportRunnable).isNotNull();
            exportRunnable.run(); // should not fail with failing exporter

            ArgumentCaptor<MetricsSnapshot> snapshotCaptor = ArgumentCaptor.forClass(MetricsSnapshot.class);
            ArgumentCaptor<Supplier<Optional<MetricsSnapshot>>> snapshotSupplierCaptor =
                    ArgumentCaptor.forClass(Supplier.class);

            verify(pullingExporter).setSnapshotProvider(snapshotSupplierCaptor.capture());
            verify(pushingExporterFailingRuntime).export(snapshotCaptor.capture());
            verify(pushingExporterFailingChecked).export(snapshotCaptor.capture());
            verify(pushingExporter).export(snapshotCaptor.capture());

            Optional<MetricsSnapshot> optionalSnapshot =
                    snapshotSupplierCaptor.getValue().get();
            assertThat(optionalSnapshot).isNotEmpty();
            MetricsSnapshot snapshot = optionalSnapshot.get();
            assertThat(snapshot).isSameAs(snapshotCaptor.getValue());

            assertThat(snapshotCaptor.getAllValues()).hasSize(3);
            assertThat(snapshotCaptor.getAllValues().get(0)).isSameAs(snapshot);
            assertThat(snapshotCaptor.getAllValues().get(1)).isSameAs(snapshot);
            assertThat(snapshotCaptor.getAllValues().get(2)).isSameAs(snapshot);

            verifySnapshotHasMetricsInOrder(snapshot, "test_counter", "export:push_export_duration");

            exportManager.shutdown();
            verify(exportThreadFuture).isDone();
            verify(exportThreadFuture).cancel(false);

            // try to shut down again to verify idempotency
            exportManager.shutdown();
            verifyNoMoreInteractions(exportThreadFuture);
            verifyNoMoreInteractions(executorService);
            verify(pushingExporter, times(1)).close(); // closed only once
            verify(pushingExporterFailingRuntime, times(1)).close(); // closed only once
            verify(pushingExporterFailingChecked, times(1)).close(); // closed only once
            verify(pullingExporter, times(1)).close(); // closed only once
        }
    }

    private static PullingMetricsExporter mockPulling(String name) {
        PullingMetricsExporter exporter = mock(PullingMetricsExporter.class);
        when(exporter.name()).thenReturn(name);
        return exporter;
    }

    private static PushingMetricsExporter mockPushing(String name) {
        PushingMetricsExporter exporter = mock(PushingMetricsExporter.class);
        when(exporter.name()).thenReturn(name);
        return exporter;
    }

    private void verifyNoOp(MetricsExportManager exportManager) {
        assertThat(exportManager).isInstanceOf(NoOpMetricsExportManager.class);
        assertThat(exportManager.hasRunningExportThread()).isFalse();
    }

    private static ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create().autoDiscoverExtensions();
    }

    private static MetricsExportManager createExportManagerMockDiscovery(
            Configuration configuration, MetricsExporterFactory... exportFactories) {
        return createExportManagerMockDiscovery(MetricRegistry.builder("test").build(), configuration, exportFactories);
    }

    private static MetricsExportManager createExportManagerMockDiscovery(
            MetricRegistry registry, Configuration configuration, MetricsExporterFactory... exportFactories) {
        return createExportManagerMockDiscovery(
                MetricsExportManager.builder(), registry, configuration, exportFactories);
    }

    private static MetricsExportManager createExportManagerMockDiscovery(
            MetricsExportManager.Builder builder,
            MetricRegistry registry,
            Configuration configuration,
            MetricsExporterFactory... exportFactories) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsExporterFactory.class))
                    .thenReturn(Arrays.asList(exportFactories));
            return builder.withDiscoverExporters(configuration).build(registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
