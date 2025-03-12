// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("jumboTransactions")
public record JumboTransactionsConfig(
        @ConfigProperty(value = "jumboTxnIsEnabled", defaultValue = "false") @NetworkProperty boolean jumboTxnIsEnabled,
        @ConfigProperty(value = "jumboTxnSize", defaultValue = "133120") @NetworkProperty int jumboTxnSize,
        @ConfigProperty(value = "jumboEthereumDataSize", defaultValue = "131072") @NetworkProperty
                int jumboEthereumDataSize,
        @ConfigProperty(value = "grpcMethodNames", defaultValue = "callEthereum") @NodeProperty
                List<String> jumboTxnGrpcMethodNames) {}
