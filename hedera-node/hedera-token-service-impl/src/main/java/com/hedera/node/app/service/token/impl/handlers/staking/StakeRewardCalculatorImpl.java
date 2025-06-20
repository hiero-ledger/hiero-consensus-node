// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.api.StakingRewardsApi;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class manages calculations of staking rewards.
 */
@Singleton
public class StakeRewardCalculatorImpl implements StakeRewardCalculator {

    private final StakePeriodManager stakePeriodManager;

    /**
     * Default constructor for injection.
     * @param stakePeriodManager the stake period manager
     */
    @Inject
    public StakeRewardCalculatorImpl(@NonNull final StakePeriodManager stakePeriodManager) {
        this.stakePeriodManager = stakePeriodManager;
    }

    /** {@inheritDoc} */
    @Override
    public long computePendingReward(
            @NonNull final Account account,
            @NonNull final WritableStakingInfoStore stakingInfoStore,
            @NonNull final ReadableNetworkStakingRewardsStore rewardsStore,
            @NonNull final Instant consensusNow) {
        final var effectiveStart = stakePeriodManager.effectivePeriod(account.stakePeriodStart());
        if (!stakePeriodManager.isRewardable(effectiveStart, rewardsStore)) {
            return 0;
        }

        // At this point all the accounts that are eligible for computing rewards should have a
        // staked to a node
        final var nodeId = account.stakedNodeIdOrThrow();
        final var stakingInfo = stakingInfoStore.getOriginalValue(nodeId);
        if (stakingInfo != null && stakingInfo.deleted()) {
            return 0;
        }
        final var rewardOffered =
                computeRewardFromDetails(account, stakingInfo, stakePeriodManager.currentStakePeriod(), effectiveStart);
        return account.declineReward() ? 0 : rewardOffered;
    }

    /** {@inheritDoc} */
    @Override
    public long estimatePendingRewards(
            @NonNull final Account account,
            @Nullable final StakingNodeInfo nodeStakingInfo,
            @NonNull final ReadableNetworkStakingRewardsStore rewardsStore) {
        final var effectiveStart = stakePeriodManager.effectivePeriod(account.stakePeriodStart());
        if (!stakePeriodManager.isEstimatedRewardable(effectiveStart, rewardsStore)
                || (nodeStakingInfo != null && nodeStakingInfo.deleted())) {
            return 0;
        }
        final var rewardOffered = computeRewardFromDetails(
                account, nodeStakingInfo, stakePeriodManager.estimatedCurrentStakePeriod(), effectiveStart);
        return account.declineReward() ? 0 : rewardOffered;
    }

    /** {@inheritDoc} */
    @Override
    public long epochSecondAtStartOfPeriod(final long stakePeriod) {
        return stakePeriodManager.epochSecondAtStartOfPeriod(stakePeriod);
    }

    /**
     * Computes the reward for the given account based on the given node staking info.
     * @param account the account
     * @param nodeStakingInfo the node staking info
     * @param currentStakePeriod the current stake period
     * @param effectiveStart the effective start
     * @return the reward for the given account based on the given node staking info
     */
    @VisibleForTesting
    public static long computeRewardFromDetails(
            @NonNull final Account account,
            @Nullable final StakingNodeInfo nodeStakingInfo,
            final long currentStakePeriod,
            final long effectiveStart) {
        return StakingRewardsApi.computeRewardFromDetails(account, nodeStakingInfo, currentStakePeriod, effectiveStart);
    }
}
