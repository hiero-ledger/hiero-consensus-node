// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

@ConfigData("metrics.exporter.openmetrics.http")
public record OpenMetricsHttpEndpointConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "8888") @Min(1024) @Max(65535) int port,
        @ConfigProperty(defaultValue = "/metrics") String path,
        @ConfigProperty(defaultValue = "0") @Min(0) @Max(10) int backlog) {}
