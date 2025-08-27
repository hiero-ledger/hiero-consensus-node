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
        @ConfigProperty(defaultValue = "0") @NodeProperty int executionTimesToTrack,
        @ConfigProperty(value = "entityUtils.gaugeUpdateIntervalMs", defaultValue = "3000") @NodeProperty
                long entityUtilsGaugeUpdateIntervalMs,
        @ConfigProperty(value = "hapiOps.speedometerUpdateIntervalMs", defaultValue = "3000") @NodeProperty
                long hapiOpsSpeedometerUpdateIntervalMs,
        @ConfigProperty(value = "throttleUtils.gaugeUpdateIntervalMs", defaultValue = "1000") @NodeProperty
                long throttleUtilsGaugeUpdateIntervalMs,
        @ConfigProperty(defaultValue = "10.0") @NodeProperty double runningAvgHalfLifeSecs,
        @ConfigProperty(defaultValue = "10.0") @NodeProperty double speedometerHalfLifeSecs) {}
