// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testing PrometheusConfig")
class PrometheusConfigTest {

    static final String DEFAULT_PROMETHEUS_ENDPOINT_ENABLED = "true";
    static final String DEFAULT_PROMETHEUS_ENDPOINT_PORT_NUMBER = "9999";
    static final String DEFAULT_PROMETHEUS_ENDPOINT_MAX_BACKLOG_ALLOWED = "1";

    @Test
    @DisplayName("Testing default prometheus configuration")
    void testDefaultPrometheusConfig() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final PrometheusConfig prometheusConfig = configuration.getConfigData(PrometheusConfig.class);

        assertThat(prometheusConfig).isNotNull();
        assertThat(prometheusConfig.endpointEnabled()).isEqualTo(Boolean.valueOf(DEFAULT_PROMETHEUS_ENDPOINT_ENABLED));
        assertThat(prometheusConfig.endpointPortNumber())
                .isEqualTo(Integer.valueOf(DEFAULT_PROMETHEUS_ENDPOINT_PORT_NUMBER));
        assertThat(prometheusConfig.endpointMaxBacklogAllowed())
                .isEqualTo(Integer.valueOf(DEFAULT_PROMETHEUS_ENDPOINT_MAX_BACKLOG_ALLOWED));
    }

    @Test
    @DisplayName("Testing custom prometheus configuration")
    void testCustomPrometheusConfig() throws Exception {
        final Path configFile = Paths.get(Objects.requireNonNull(
                        PrometheusConfigTest.class.getClassLoader().getResource("metrics-test.properties"))
                .toURI());
        final Configuration configuration = new TestConfigBuilder()
                .withSource(new PropertyFileConfigSource(configFile))
                .getOrCreateConfig();
        final PrometheusConfig prometheusConfig = configuration.getConfigData(PrometheusConfig.class);

        assertThat(prometheusConfig).isNotNull();
        assertThat(prometheusConfig.endpointEnabled()).isTrue();
        assertThat(prometheusConfig.endpointPortNumber()).isEqualTo(9998);
        assertThat(prometheusConfig.endpointMaxBacklogAllowed()).isEqualTo(2);
    }
}
