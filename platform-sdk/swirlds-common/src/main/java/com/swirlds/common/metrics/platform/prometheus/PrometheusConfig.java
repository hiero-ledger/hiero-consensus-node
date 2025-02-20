// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration concerning the Prometheus endpoint.
 *
 * @param endpointEnabled
 *         Flag that is {@code true}, if the endpoint should be endpointEnabled, {@code false otherwise}.
 * @param endpointPortNumber
 *         Port of the Prometheus endpoint.
 * @param endpointMaxBacklogAllowed
 *         The maximum number of incoming TCP connections which the system will queue internally.
 *         May be {@code 1}, in which case a system default value is used.
 */
@ConfigData("prometheus")
public record PrometheusConfig(
        @ConfigProperty(defaultValue = "true") boolean endpointEnabled,
        @Min(0) @Max(65535) @ConfigProperty(defaultValue = "9999") int endpointPortNumber,
        @Min(0) @ConfigProperty(defaultValue = "1") int endpointMaxBacklogAllowed) {}
