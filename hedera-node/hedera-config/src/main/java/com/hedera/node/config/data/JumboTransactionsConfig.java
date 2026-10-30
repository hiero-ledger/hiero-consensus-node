// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.Set;

@ConfigData("jumboTransactions")
public record JumboTransactionsConfig(
        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean isEnabled,

        @ConfigProperty(defaultValue = "133120") @NetworkProperty
        int maxTxnSize,

        @ConfigProperty(defaultValue = "131072") @NetworkProperty
        int ethereumMaxCallDataSize,

        @ConfigProperty(defaultValue = "callEthereum") @NodeProperty
        Set<String> grpcMethodNames,

        @ConfigProperty(defaultValue = "EthereumTransaction") @NodeProperty
        Set<HederaFunctionality> allowedHederaFunctionalities,

        @ConfigProperty(defaultValue = "133120") @NetworkProperty
        long maxBytesPerSec,
        // A file-referenced EthereumTransaction is metered (jumbo bytes throttle and per-byte node fee)
        // by the decoded size of its hydrated callData rather than only its on-wire size, and the callData
        // size ceiling and EIP-7623 gas floor are enforced at pre-handle on the hydrated payload.
        // Disabling this reverts to metering by on-wire size alone. Must be @NetworkProperty so the
        // outcome is identical on every node and at both ingest and consensus.
        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean chargeHydratedCallDataSize) {}
