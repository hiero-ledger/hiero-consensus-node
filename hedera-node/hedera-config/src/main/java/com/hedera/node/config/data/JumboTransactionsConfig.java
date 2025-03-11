// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("jumboTransactions")
public record JumboTransactionsConfig(
        @ConfigProperty(value = "transaction.jumboTxnIsEnabled", defaultValue = "false") @NetworkProperty
                boolean jumboTxnIsEnabled,
        @ConfigProperty(value = "transaction.jumboTxnSize", defaultValue = "133120") @NetworkProperty long jumboTxnSize,
        @ConfigProperty(value = "transaction.jumboEthereumDataSize", defaultValue = "131072") @NetworkProperty
                long jumboEthereumDataSize,
        @ConfigProperty(defaultValue = "callEthereum") @NodeProperty List<String> jumboTxnGrpcMethodNames) {}
