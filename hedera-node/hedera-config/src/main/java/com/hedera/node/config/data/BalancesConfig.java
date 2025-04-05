// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("balances")
public record BalancesConfig(
        @ConfigProperty(value = "exportDir.path", defaultValue = "/opt/hgcapp/accountBalances/") @NodeProperty
                String exportDirPath,
        @ConfigProperty(defaultValue = "900") @NodeProperty int exportPeriodSecs,
        @ConfigProperty(defaultValue = "0") @NodeProperty long nodeBalanceWarningThreshold) {}
