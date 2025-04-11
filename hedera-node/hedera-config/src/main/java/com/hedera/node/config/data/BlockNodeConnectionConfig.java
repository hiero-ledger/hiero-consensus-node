// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for Connecting to Block Nodes.
 * @param shutdownNodeOnNoBlockNodes whether to shut down the consensus node if there are no block node connections
 * @param blockNodeConnectionFileDir directory to get the block node configuration file
 * @param waitPeriodForActiveConnection the time in minutes to wait for an active connection
 */
@ConfigData("blockNode")
public record BlockNodeConnectionConfig(
        @ConfigProperty(defaultValue = "false") @NodeProperty boolean shutdownNodeOnNoBlockNodes,
        @ConfigProperty(defaultValue = "data/config") @NodeProperty String blockNodeConnectionFileDir,
        @ConfigProperty(defaultValue = "block-nodes.json") @NodeProperty String blockNodeConfigFile,
        @ConfigProperty(defaultValue = "2") @NetworkProperty long waitPeriodForActiveConnection) {}
