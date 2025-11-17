// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.metrics.TestUtils.verifySnapshotHasMetrics;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.core.MetricRegistryImpl;
import org.hiero.metrics.internal.export.NoOpMetricsExportManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class MetricsExportManagerTest {

    @Test
    void testDisabledExportManagerWhenExporterAvailable() {
        Configuration configuration = configBuilder()
                .withValue("metrics.export.manager.enabled", "false")
                .build();

        MetricsExporterFactory exporterFactory = mock(MetricsExporterFactory.class);
        MetricsExportManager exportManager = createExportManagerMockDiscovery(configuration, exporterFactory);

        verifyNoOp(exportManager);
        verifyNoInteractions(exporterFactory);
    }

    @Test
    void testNoExporters() {
        Configuration configuration = configBuilder().build();

        MetricsExportManager exportManager = createExportManagerMockDiscovery(configuration);

        verifyNoOp(exportManager);
    }

    @Test
    void testAllExportersAreFiltered() {
        Configuration configuration = configBuilder()
                .withValue("metrics.export.manager.disabledExporters", "pulling-disabled")
                .build();

        final MetricsExporterFactory failingExporterFactory = mock(MetricsExporterFactory.class);
        when(failingExporterFactory.name()).thenReturn("failing");
        when(failingExporterFactory.createExporter(configuration)).thenThrow(new RuntimeException("test exception"));

        final MetricsExporterFactory emptyExporterFactory = mock(MetricsExporterFactory.class);
        when(emptyExporterFactory.name()).thenReturn("empty");
        when(emptyExporterFactory.createExporter(configuration)).thenReturn(Optional.empty());

        final MetricsExporterFactory disabledExporterFactory = mock(MetricsExporterFactory.class);
        when(disabledExporterFactory.name()).thenReturn("pulling-disabled");

        MetricsExportManager exportManager = createExportManagerMockDiscovery(
                configuration, failingExporterFactory, emptyExporterFactory, disabledExporterFactory);

        verifyNoOp(exportManager);
        verify(failingExporterFactory).createExporter(configuration);
        verify(emptyExporterFactory).createExporter(configuration);
        verify(disabledExporterFactory, never()).createExporter(configuration);
    }

    @Test
    void testSinglePullingExporter() throws IOException {
        Configuration configuration = configBuilder().build();

        final PullingMetricsExporter pullingExporter = mock(PullingMetricsExporter.class);
        final MetricsExporterFactory pullingExporterFactory = mock(MetricsExporterFactory.class);
        when(pullingExporterFactory.name()).thenReturn("pulling");
        when(pullingExporterFactory.createExporter(configuration)).thenReturn(Optional.of(pullingExporter));

        MetricsExportManager exportManager = createExportManagerMockDiscovery(configuration, pullingExporterFactory);

        assertThat(exportManager.hasRunningExportThread()).isFalse();
        verify(pullingExporter, never()).setSnapshotProvider(any());

        // manage first registry - triggers exporter initialization
        MetricRegistry registry = MetricRegistry.builder().build();
        registry.register(LongCounter.builder("test_counter"));
        boolean managed = exportManager.manageMetricRegistry(registry);

        assertThat(managed).as("Metric registry should be managed").isTrue();
        assertThat(exportManager.hasRunningExportThread()).isFalse();

        ArgumentCaptor<Supplier<Optional<MetricsSnapshot>>> snapshotSupplierCaptor =
                ArgumentCaptor.forClass(Supplier.class);
        verify(pullingExporter).setSnapshotProvider(snapshotSupplierCaptor.capture());
        Supplier<Optional<MetricsSnapshot>> snapshotSupplier = snapshotSupplierCaptor.getValue();
        Optional<MetricsSnapshot> optionalSnapshot = snapshotSupplier.get();
        assertThat(optionalSnapshot).isNotEmpty();
        verifySnapshotHasMetrics(optionalSnapshot.get(), "test_counter");

        // close manager
        exportManager.shutdown();
        verify(pullingExporter).close();

        verify(pullingExporterFactory).createExporter(configuration);
        verify(pullingExporterFactory).name();
        verifyNoMoreInteractions(pullingExporterFactory);
    }

    private void verifyNoOp(MetricsExportManager exportManager) {
        assertThat(exportManager).isInstanceOf(NoOpMetricsExportManager.class);
        assertThat(exportManager.hasRunningExportThread()).isFalse();
        assertThat(exportManager.name()).isEqualTo("no-op");
        assertThat(exportManager.manageMetricRegistry(new MetricRegistryImpl())).isFalse();
    }

    private ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create().autoDiscoverExtensions();
    }

    private MetricsExportManager createExportManagerMockDiscovery(
            Configuration configuration, MetricsExporterFactory... exportFactories) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsExporterFactory.class))
                    .thenReturn(Arrays.asList(exportFactories));
            return MetricsExportManager.builder("test")
                    .withDiscoverExporters(configuration)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
