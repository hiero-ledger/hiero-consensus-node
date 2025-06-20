// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("rates")
public record RatesConfig(
        @ConfigProperty(defaultValue = "25") @NetworkProperty int intradayChangeLimitPercent,
        @ConfigProperty(defaultValue = "1") @NetworkProperty long midnightCheckInterval) {}
