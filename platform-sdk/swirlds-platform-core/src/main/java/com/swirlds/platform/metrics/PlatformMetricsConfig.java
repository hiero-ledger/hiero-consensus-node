// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for platform specific metrics
 *
 * @param eventPipelineMetricsEnabled if true, the platform will collect and report metrics about the event pipeline
 */
@ConfigData("platform.metrics")
public record PlatformMetricsConfig(@ConfigProperty(defaultValue = "true") boolean eventPipelineMetricsEnabled) {}
