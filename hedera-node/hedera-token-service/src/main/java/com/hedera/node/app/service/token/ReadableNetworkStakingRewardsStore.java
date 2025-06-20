// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with network staking rewards.
 */
public interface ReadableNetworkStakingRewardsStore {
    /**
     * Whether staking rewards are activated on the network. This is set to true when the balance of
     * 0.0.800 reaches minimum required balance.
     * @return true if staking rewards are activated
     */
    boolean isStakingRewardsActivated();
    /**
     * Total of (balance + stakedToMe) for all accounts staked to all nodes in the network with declineReward=false,
     * at the beginning of the new staking period.
     * @return total stake reward start
     */
    long totalStakeRewardStart();
    /**
     * Total of (balance + stakedToMe) for all accounts staked to all nodes in the network, at the beginning of the new
     * staking period.
     * @return total staked start
     */
    long totalStakedStart();
    /**
     * The total staking rewards in tinybars that COULD be collected by all accounts staking to all nodes after the end
     * of this staking period; assuming that no account "renounces" its rewards by, for example, setting
     * declineReward=true.
     * @return total reward
     */
    long pendingRewards();

    /**
     * Returns the {link NetworkStakingRewards} in state.
     * @return the {link NetworkStakingRewards} in state
     */
    NetworkStakingRewards get();
}
