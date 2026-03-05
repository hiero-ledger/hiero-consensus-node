// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.PermissionedAccountsRange;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("governanceTransactions")
public record GovernanceTransactionsConfig(
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean isEnabled,
        @ConfigProperty(defaultValue = "133120") @NetworkProperty int maxTxnSize,
        @ConfigProperty(defaultValue = "2,42-799") PermissionedAccountsRange accountsRange) {}
