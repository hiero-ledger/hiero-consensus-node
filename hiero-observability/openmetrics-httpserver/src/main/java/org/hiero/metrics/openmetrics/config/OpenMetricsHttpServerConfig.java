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
 * @param port the port to listen on (default: 8888, range: 1024-65535)
 * @param path the HTTP path to serve metrics on (default: /metrics)
 * @param bufferSize the buffer size for HTTP response output stream (default: 1024, range: 0-2mb, 0 = no buffering)
 * @param decimalFormat the decimal format for numbers (default: #.###)
 */
// spotless:off
@ConfigData("metrics.exporter.openmetrics.http")
public record OpenMetricsHttpServerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "localhost") String hostname,
        @ConfigProperty(defaultValue = "8888") @Min(1024) @Max(65535) int port,
        @ConfigProperty(defaultValue = "/metrics") String path,
        @ConfigProperty(defaultValue = "1024") @Min(0) @Max(2097152) int bufferSize,
        @ConfigProperty(defaultValue = "#.###") String decimalFormat) {}
// spotless:on
