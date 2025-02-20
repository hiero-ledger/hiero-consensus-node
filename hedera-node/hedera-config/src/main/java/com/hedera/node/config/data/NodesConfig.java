// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("nodes")
public record NodesConfig(
        @ConfigProperty(defaultValue = "100") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int nodeMaxDescriptionUtf8Bytes,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxGossipEndpoint,
        @ConfigProperty(defaultValue = "8") @NetworkProperty int maxServiceEndpoint,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean gossipFqdnRestricted,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean enableDAB,
        @ConfigProperty(defaultValue = "253") @NetworkProperty int maxFqdnSize,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean updateAccountIdAllowed) {}
