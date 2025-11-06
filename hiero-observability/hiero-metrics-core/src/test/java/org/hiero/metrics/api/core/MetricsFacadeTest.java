// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.MetricsExporterFactory;
import org.hiero.metrics.api.utils.MetricUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MetricsFacadeTest {

    @Test
    void disabledExportManagerWhileExportersAvailable() {
        Configuration configuration = configBuilder()
                .withValue("metrics.export.manager.enabled", "false")
                .build();

        final MetricsExporterFactory exporterFactory = Mockito.mock(MetricsExporterFactory.class);

        testWithMockExporters(
                configuration,
                exportManager -> {
                    verifyNoOp(exportManager);
                    verifyNoInteractions(exporterFactory);
                },
                exporterFactory);
    }

    @Test
    void enabledExportManagerWhileNoExporters() {
        Configuration configuration = configBuilder().build();
        testWithMockExporters(configuration, this::verifyNoOp);
    }

    @Test
    void singleEmptyExportFactory() {
        Configuration configuration = configBuilder().build();

        final MetricsExporterFactory exporterFactory = Mockito.mock(MetricsExporterFactory.class);
        when(exporterFactory.createExporter(configuration)).thenReturn(Optional.empty());

        testWithMockExporters(
                configuration,
                exportManager -> {
                    verifyNoOp(exportManager);
                    verify(exporterFactory).createExporter(configuration);
                },
                exporterFactory);
    }

    private void verifyNoOp(MetricsExportManager exportManager) {
        assertThat(exportManager.hasRunningExportThread()).isFalse();
        assertThat(exportManager.name()).isEqualTo("no-op");
    }

    private ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create().autoDiscoverExtensions();
    }

    private void testWithMockExporters(
            Configuration configuration,
            Verifier<MetricsExportManager> verification,
            MetricsExporterFactory... mockExportFactories) {
        try (MockedStatic<MetricUtils> mockedUtils = mockStatic(MetricUtils.class)) {
            mockedUtils
                    .when(() -> MetricUtils.load(MetricsExporterFactory.class))
                    .thenReturn(Arrays.asList(mockExportFactories));
            MetricsExportManager exportManager = MetricsFacade.createExportManagerWithDiscoveredExporters(
                    "test", configuration, Executors::newSingleThreadScheduledExecutor, 1);
            verification.verify(exportManager);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface Verifier<T> {
        void verify(T t) throws Exception;
    }
}
