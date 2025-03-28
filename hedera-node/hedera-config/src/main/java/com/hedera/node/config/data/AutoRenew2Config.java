// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("autorenew")
public record AutoRenew2Config(
        @ConfigProperty(defaultValue = "100") @NetworkProperty int numberOfEntitiesToScan,
        @ConfigProperty(defaultValue = "2") @NetworkProperty int maxNumberOfEntitiesToRenewOrDelete,
        @ConfigProperty(defaultValue = "604800") @NetworkProperty long gracePeriod) {}
