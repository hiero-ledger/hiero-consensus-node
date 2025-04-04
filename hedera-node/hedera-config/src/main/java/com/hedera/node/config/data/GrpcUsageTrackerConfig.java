package com.hedera.node.config.data;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;

@ConfigData("grpcUsageTracking")
public record GrpcUsageTrackerConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "15") @Min(1) int dumpIntervalMinutes,
        @ConfigProperty(defaultValue = "1000") @Min(0) int userAgentCacheSize
) {

}
