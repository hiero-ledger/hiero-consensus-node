// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the uptime detector.
 *
 * @param degradationThreshold if none of a node's events reach consensus in this amount of time then we consider that
 *                             node to be degraded.
 */
@ConfigData("uptime")
public record UptimeConfig(@ConfigProperty(defaultValue = "10s") Duration degradationThreshold) {}
