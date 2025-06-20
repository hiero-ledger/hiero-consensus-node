// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("traceability")
public record TraceabilityConfig(
        @ConfigProperty(defaultValue = "10") @NetworkProperty long maxExportsPerConsSec,
        @ConfigProperty(defaultValue = "9") @NetworkProperty long minFreeToUsedGasThrottleRatio) {}
