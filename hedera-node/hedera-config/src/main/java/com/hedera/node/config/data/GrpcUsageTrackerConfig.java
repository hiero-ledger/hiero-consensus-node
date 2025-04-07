// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration for gRPC usage tracking.
 * <p>
 * Parts of this configuration are considered dynamic and can be altered at runtime - this includes whether tracking is
 * enabled and the interval between usage data being logged. The usage tracker will re-read the configuration at each
 * interval. For example, if the {@link #logIntervalMinutes()} is 15 minutes upon startup, but the config is updated to
 * change the interval to 5 minutes, the first period of 15 minutes must elapse before the new configuration is applied.
 *
 * @param enabled True if usage tracking is enabled, else false. This can be altered at runtime.
 * @param logIntervalMinutes The interval, in minutes, that determines the frequency in which collected usage data is
 *                           written to a log. Must be greater than or equal to 1. This can be altered at runtime.
 * @param userAgentCacheSize The size of the user-agent cache. A cache is kept of parsed user-agent headers the
 *                           application has encountered, and this value determines the maximum number of entries that
 *                           will be cached. This can NOT be altered at runtime. Must be greater than or equal to 0.
 */
@ConfigData("grpcUsageTracking")
public record GrpcUsageTrackerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "15") @Min(1) int logIntervalMinutes,
        @ConfigProperty(defaultValue = "1000") @Min(0) int userAgentCacheSize) {

    public GrpcUsageTrackerConfig {
        if (logIntervalMinutes <= 0) {
            throw new IllegalArgumentException(
                    "grpcUsageTracking.logIntervalMinutes must be greater than or equal to 1 (found: "
                            + logIntervalMinutes + ")");
        }
        if (userAgentCacheSize < 0) {
            throw new IllegalArgumentException(
                    "grpcUsageTracking.userAgentCacheSize must be greater than or equal to 0 (found: "
                            + userAgentCacheSize + ")");
        }
    }
}
