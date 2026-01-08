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
import java.net.ServerSocket;
import org.hiero.metrics.api.export.MetricsExporter;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfig;
import org.junit.jupiter.api.Test;

public class OpenMetricsHttpEndpointFactoryTest {

    @Test
    void testInstantiation() {
        OpenMetricsHttpEndpointFactory factory = new OpenMetricsHttpEndpointFactory();
        assertThat(factory.name()).isEqualTo("openmetrics-http-endpoint");
    }

    @Test
    void noExporterCreatedWhenDisabled() {
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.openmetrics.http.enabled", "false")
                .build();
        MetricsExporter exporter = new OpenMetricsHttpEndpointFactory().createExporter("registry", config);

        assertThat(exporter).isNull();
    }

    @Test
    void testFailingCreation() throws IOException {
        // Occupy any port first
        try (ServerSocket socket = new ServerSocket(0)) {
            Configuration configuration = mock(Configuration.class);
            when(configuration.getConfigData(OpenMetricsHttpEndpointConfig.class))
                    .thenReturn(new OpenMetricsHttpEndpointConfig(true, socket.getLocalPort(), "/metrics", 0));

            assertThatThrownBy(() -> new OpenMetricsHttpEndpointFactory().createExporter("registry", configuration))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(BindException.class)
                    .hasRootCauseMessage("Address already in use");
        }
    }
}
