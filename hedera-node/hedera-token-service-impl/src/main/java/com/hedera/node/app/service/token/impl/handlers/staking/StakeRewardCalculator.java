// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Interface for calculating stake rewards.
 */
public interface StakeRewardCalculator {
    /**
     * Compute the pending rewards for the given account.
     * This method is called only on accounts that are staked to nodes. It is not called on accounts that are
     * staked to the accounts. If the account is staked to a node, and has declined to receive reward the result is 0.
     * Else, the result is calculated based on the rewardSumHistory of the node and the stake of the account
     * and start of reward period.
     * @param account The account for which the pending rewards are to be calculated.
     * @param stakingInfoStore The store from which the staking info of the node is to be retrieved.
     * @param rewardsStore The store from which the rewards are to be retrieved.
     * @param consensusNow The consensus time at which the rewards are to be calculated.
     * @return The pending rewards for the account
     */
    long computePendingReward(
            @NonNull Account account,
            @NonNull WritableStakingInfoStore stakingInfoStore,
            @NonNull ReadableNetworkStakingRewardsStore rewardsStore,
            @NonNull Instant consensusNow);

    /**
     * Estimate the pending rewards for the given account. This method is called only on
     * {@link com.hedera.node.app.service.token.impl.handlers.CryptoGetAccountInfoHandler} query
     * and when recomputing node stakes and pending rewards during upgrade house-keeping.
     * @param account The account for which the pending rewards are to be calculated.
     * @param nodeStakingInfo The staking info of the node to which the account is staked.
     * @param rewardsStore The store from which the rewards are to be retrieved.
     * @return The pending rewards for the account
     */
    long estimatePendingRewards(
            @NonNull Account account,
            @Nullable StakingNodeInfo nodeStakingInfo,
            @NonNull ReadableNetworkStakingRewardsStore rewardsStore);

    /**
     * Gives the epoch second at the start of the given stake period.
     * @param stakePeriod The stake period for which the epoch second is to be calculated.
     * @return The epoch second at the start of the given stake period
     */
    long epochSecondAtStartOfPeriod(long stakePeriod);
}
