// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class OpenMetricsHttpEndpointConfigTest {

    @Test
    void defaultValues() {
        OpenMetricsHttpEndpointConfig endpointConfig =
                configBuilder().build().getConfigData(OpenMetricsHttpEndpointConfig.class);

        assertThat(endpointConfig.enabled())
                .as("Open Metrics HTTP endpoint must be enabled by default.")
                .isTrue();

        assertThat(endpointConfig.port())
                .as("Open Metrics HTTP endpoint default port must be 8888.")
                .isEqualTo(8888);

        assertThat(endpointConfig.path())
                .as("Open Metrics HTTP endpoint default path must be '/metrics'.")
                .isEqualTo("/metrics");

        assertThat(endpointConfig.backlog())
                .as("Open Metrics HTTP endpoint default backlog must be 0.")
                .isEqualTo(0);
    }

    @Test
    void nonDefaultValues() {
        OpenMetricsHttpEndpointConfig endpointConfig = configBuilder()
                .withValue("metrics.exporter.openmetrics.http.enabled", "false")
                .withValue("metrics.exporter.openmetrics.http.port", "1234")
                .withValue("metrics.exporter.openmetrics.http.path", "/custom-metrics")
                .withValue("metrics.exporter.openmetrics.http.backlog", "5")
                .build()
                .getConfigData(OpenMetricsHttpEndpointConfig.class);

        assertThat(endpointConfig.enabled()).isFalse();
        assertThat(endpointConfig.port()).isEqualTo(1234);
        assertThat(endpointConfig.path()).isEqualTo("/custom-metrics");
        assertThat(endpointConfig.backlog()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 80, 1023, 65536, 70000, Integer.MAX_VALUE})
    void nonAllowedPorts(int port) {
        assertThatThrownBy(() -> configBuilder()
                        .withValue("metrics.exporter.openmetrics.http.port", String.valueOf(port))
                        .build())
                .as("Invalid port " + port + " must cause a ConfigViolationException.")
                .isInstanceOf(ConfigViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 1025, 8080, 8888, 9999, 65534, 65535})
    void allowedPorts(int port) {
        OpenMetricsHttpEndpointConfig endpointConfig = configBuilder()
                .withValue("metrics.exporter.openmetrics.http.port", String.valueOf(port))
                .build()
                .getConfigData(OpenMetricsHttpEndpointConfig.class);

        assertThat(endpointConfig.port())
                .as("Open Metrics HTTP endpoint port must be set to " + port)
                .isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -10, -1, 11, 50, 100, Integer.MAX_VALUE})
    void nonAllowedBacklogs(int backlog) {
        assertThatThrownBy(() -> configBuilder()
                        .withValue("metrics.exporter.openmetrics.http.backlog", String.valueOf(backlog))
                        .build())
                .as("Invalid backlog " + backlog + " must cause a ConfigViolationException.")
                .isInstanceOf(ConfigViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5, 10})
    void allowedBacklogs(int backlog) {
        OpenMetricsHttpEndpointConfig endpointConfig = configBuilder()
                .withValue("metrics.exporter.openmetrics.http.backlog", String.valueOf(backlog))
                .build()
                .getConfigData(OpenMetricsHttpEndpointConfig.class);

        assertThat(endpointConfig.backlog())
                .as("Open Metrics HTTP endpoint backlog must be set to " + backlog)
                .isEqualTo(backlog);
    }

    private ConfigurationBuilder configBuilder() {
        return ConfigurationBuilder.create().autoDiscoverExtensions();
    }
}
