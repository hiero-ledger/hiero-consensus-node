// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.Set;

@ConfigData("autoRenew")
public record AutoRenewConfig(@ConfigProperty(defaultValue = "") @NetworkProperty Set<String> targetTypes) {
    public boolean expireContracts() {
        return targetTypes.contains("CONTRACT");
    }

    public boolean expireAccounts() {
        return targetTypes.contains("ACCOUNT");
    }

    public boolean isAutoRenewEnabled() {
        return !targetTypes.isEmpty();
    }
}
