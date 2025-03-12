// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("jumboTransactions")
public record JumboTransactionsConfig(
        @ConfigProperty(value = "isEnabled", defaultValue = "false") @NetworkProperty boolean isEnabled,
        @ConfigProperty(value = "maxTxnSize", defaultValue = "133120") @NetworkProperty int maxTxnSize,
        @ConfigProperty(value = "ethereumMaxCallDataSize", defaultValue = "131072") @NetworkProperty
                int ethereumMaxCallDataSize,
        @ConfigProperty(value = "grpcMethodNames", defaultValue = "callEthereum") @NodeProperty
                List<String> grpcMethodNames) {}
