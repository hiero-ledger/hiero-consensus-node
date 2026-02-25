// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("stats")
public record StatsConfig(
        @ConfigProperty(defaultValue = "<GAS>,ThroughputLimits,CreationLimits,<OPS_DURATION>") @NodeProperty
                List<String> consThrottlesToSample,
        @ConfigProperty(
                        defaultValue =
                                "<GAS>,ThroughputLimits,OffHeapQueryLimits,CreationLimits,FreeQueryLimits,BalanceQueryLimits")
                @NodeProperty
                List<String> hapiThrottlesToSample,
        @ConfigProperty(defaultValue = "10.0") @NodeProperty double runningAvgHalfLifeSecs,
        @ConfigProperty(defaultValue = "10.0") @NodeProperty double speedometerHalfLifeSecs) {}
