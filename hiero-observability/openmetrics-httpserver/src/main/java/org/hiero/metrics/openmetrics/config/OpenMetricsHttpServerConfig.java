// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration for the OpenMetrics HTTP server.
 *
 * @param enabled whether the server is enabled (default: true)
 * @param hostname the hostname to bind to, while empty means all interfaces (default: localhost)
 * @param port the port to listen on (default: 8888)
 * @param path the HTTP path to serve metrics on (default: /metrics)
 * @param backlog the socket backlog (default: 0)
 * @param decimalFormat the decimal format for numbers (default: #.###)
 */
// spotless:off
@ConfigData("metrics.exporter.openmetrics.http")
public record OpenMetricsHttpServerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "localhost") String hostname,
        @ConfigProperty(defaultValue = "8888") @Min(1024) @Max(65535) int port,
        @ConfigProperty(defaultValue = "/metrics") String path,
        @ConfigProperty(defaultValue = "0") @Min(0) @Max(10) int backlog,
        @ConfigProperty(defaultValue = "#.###") String decimalFormat) {}
// spotless:on
