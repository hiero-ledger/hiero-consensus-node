// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.CongestionMultipliers;
import com.hedera.node.config.types.EntityScaleFactors;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("fees")
public record FeesConfig(
        @ConfigProperty(defaultValue = "60") @NetworkProperty int minCongestionPeriod,
        @ConfigProperty(defaultValue = "90,10x,95,25x,99,100x") CongestionMultipliers percentCongestionMultipliers,
        @ConfigProperty(defaultValue = "DEFAULT(0,1:1)") EntityScaleFactors percentUtilizationScaleFactors,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean simpleFeesEnabled,
        @ConfigProperty(defaultValue = "380") @NetworkProperty int tokenTransferUsageMultiplier) {}
