// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for nodes.
 * @param maxNumber The maximum number of nodes
 * @param nodeMaxDescriptionUtf8Bytes The maximum number of bytes for a node description
 * @param maxGossipEndpoint The maximum gossip endpoint
 * @param maxServiceEndpoint The maximum service endpoint
 * @param gossipFqdnRestricted Whether the FQDN is restricted for gossip
 * @param enableDAB Whether DAB is enabled
 * @param maxFqdnSize The maximum FQDN size
 * @param nodeRewardsEnabled feature flag for all node reward payments (HIP-1064)
 * @param updateAccountIdAllowed Whether the account ID can be updated
 * @param minNodeReward A minimum daily node reward amount (applies even to inactive nodes)
 * @param targetUsdNodeRewards The target USD node rewards
 * @param numPeriodsToTargetUsd The number of periods to achieve the target USD node rewards
 * @param adjustNodeFees Whether node fees can be reduced by the average node fees already collected during that period
 * @param activeRoundsPercent A percentage value relating to active nodes
 */
@ConfigData("nodes")
public record NodesConfig(
        @ConfigProperty(defaultValue = "100") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int nodeMaxDescriptionUtf8Bytes,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxGossipEndpoint,
        @ConfigProperty(defaultValue = "8") @NetworkProperty int maxServiceEndpoint,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean gossipFqdnRestricted,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean enableDAB,
        @ConfigProperty(defaultValue = "253") @NetworkProperty int maxFqdnSize,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean updateAccountIdAllowed,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean nodeRewardsEnabled,
        @ConfigProperty(defaultValue = "0") @NetworkProperty long minNodeReward,
        @ConfigProperty(defaultValue = "25000") @NetworkProperty long targetUsdNodeRewards,
        @ConfigProperty(defaultValue = "365") @NetworkProperty long numPeriodsToTargetUsd,
        @ConfigProperty(defaultValue = "100000000000000") @NetworkProperty long preservedNodeRewardBalance,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean adjustNodeFees,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int activeRoundsPercent) {}
