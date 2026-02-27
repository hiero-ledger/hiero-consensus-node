// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpServerConfig;
import org.junit.jupiter.api.Test;

public class OpenMetricsHttpServerFactoryTest {

    @Test
    void testNoExporterCreatedWhenDisabled() {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.openmetrics.http.enabled", "false")
                .build();
        MetricsExporter exporter = new OpenMetricsHttpServerFactory().createExporter(List.of(), config);

        assertThat(exporter).isNull();
    }

    @Test
    void testFailingCreation() throws IOException {
        // Occupy any port first
        try (ServerSocket socket = new ServerSocket(0, 10, InetAddress.getByName("localhost"))) {
            Configuration configuration = mock(Configuration.class);
            when(configuration.getConfigData(OpenMetricsHttpServerConfig.class))
                    .thenReturn(new OpenMetricsHttpServerConfig(
                            true, "localhost", socket.getLocalPort(), "/metrics", 0, "#.###"));

            assertThatThrownBy(() -> new OpenMetricsHttpServerFactory().createExporter(List.of(), configuration))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(BindException.class)
                    .hasRootCauseMessage("Address already in use");
        }
    }
}
