// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.asStakingRewardBuilder;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.roundedToHbar;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.totalStake;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class for mutating staking info in the {@link WritableStakingInfoStore}.
 */
@Singleton
public class StakeInfoHelper {
    private static final Logger log = LogManager.getLogger(StakeInfoHelper.class);

    private static final String POST_UPGRADE_MEMO = "Post upgrade stake adjustment record";

    /**
     * Default constructor for injection.
     */
    @Inject
    public StakeInfoHelper() {
        // Needed for Dagger injection
    }

    /**
     * Increases the unclaimed stake reward start for the given node by the given amount.
     *
     * @param nodeId the node's numeric ID
     * @param amount the amount to increase the unclaimed stake reward start by
     * @param stakingInfoStore the store for the staking info
     */
    public void increaseUnclaimedStakeRewards(
            @NonNull final Long nodeId, final long amount, @NonNull final WritableStakingInfoStore stakingInfoStore) {
        requireNonNull(nodeId);
        requireNonNull(stakingInfoStore);

        final var currentStakingInfo = stakingInfoStore.get(nodeId);
        final var currentStakeRewardStart = currentStakingInfo.stakeRewardStart();
        final var newUnclaimedStakeRewardStart = currentStakingInfo.unclaimedStakeRewardStart() + amount;

        final var newStakingInfo = currentStakingInfo.copyBuilder();
        if (newUnclaimedStakeRewardStart > currentStakeRewardStart) {
            log.warn(
                    "Asked to release {} more rewards for node{} (now {}), but only {} was staked",
                    amount,
                    nodeId,
                    newUnclaimedStakeRewardStart,
                    currentStakeRewardStart);
            newStakingInfo.unclaimedStakeRewardStart(currentStakeRewardStart);
        } else {
            newStakingInfo.unclaimedStakeRewardStart(newUnclaimedStakeRewardStart);
        }

        stakingInfoStore.put(nodeId, newStakingInfo.build());
    }

    /**
     * Awards the stake to the node's stakeToReward or stakeToNotReward depending on the account's decline reward.
     * If declineReward is true, the stake is awarded to stakeToNotReward, otherwise it is awarded to stakeToReward.
     *
     * @param nodeId the node's numeric ID
     * @param account the account stake to be awarded to the node
     * @param stakingInfoStore the store for the staking info
     */
    public void awardStake(
            @NonNull final Long nodeId,
            @NonNull final Account account,
            @NonNull final WritableStakingInfoStore stakingInfoStore) {
        requireNonNull(nodeId);
        requireNonNull(account);
        requireNonNull(stakingInfoStore);

        final var stakeToAward = roundedToHbar(totalStake(account));
        final var isDeclineReward = account.declineReward();

        final var stakingInfo = stakingInfoStore.get(nodeId);
        if (stakingInfo != null) {
            final var copy = stakingInfo.copyBuilder();
            if (isDeclineReward) {
                final var stakedToNotReward = stakingInfo.stakeToNotReward() + stakeToAward;
                copy.stakeToNotReward(stakedToNotReward);
            } else {
                final var stakedToReward = stakingInfo.stakeToReward() + stakeToAward;
                copy.stakeToReward(stakedToReward);
            }
            stakingInfoStore.put(nodeId, copy.build());
        } else {
            log.error("Staking info is null for node {}", nodeId);
        }
    }

    /**
     * Withdraws the stake from the node's stakeToReward or stakeToNotReward depending on the account's decline reward.
     * If declineReward is true, the stake is withdrawn from stakeToNotReward, otherwise it is withdrawn from
     * stakeToReward.
     *
     * @param nodeId the node's numeric ID
     * @param account the account 's stake to be withdrawn from node
     * @param stakingInfoStore the store for the staking info
     */
    public void withdrawStake(
            @NonNull final Long nodeId,
            @NonNull final Account account,
            @NonNull final WritableStakingInfoStore stakingInfoStore) {
        requireNonNull(nodeId);
        requireNonNull(account);
        requireNonNull(stakingInfoStore);

        final var stakeToWithdraw = roundedToHbar(totalStake(account));
        final var isDeclineReward = account.declineReward();

        final var stakingInfo = stakingInfoStore.get(nodeId);
        final var copy = requireNonNull(stakingInfo).copyBuilder();
        if (isDeclineReward) {
            final var stakedToNotReward = stakingInfo.stakeToNotReward() - stakeToWithdraw;
            if (stakedToNotReward < 0) {
                log.warn(
                        "Asked to withdraw {} more unrewarded stake for node{} (now {}), but only {} was staked to not reward",
                        stakeToWithdraw,
                        nodeId,
                        stakedToNotReward,
                        stakingInfo.stakeToNotReward());
            }
            copy.stakeToNotReward(Math.max(0, stakedToNotReward));
        } else {
            final var stakeToReward = stakingInfo.stakeToReward() - stakeToWithdraw;
            if (stakeToReward < 0) {
                log.warn(
                        "Asked to withdraw {} more rewarded stake for node{} (now {}), but only {} was staked to reward",
                        stakeToWithdraw,
                        nodeId,
                        stakeToReward,
                        stakingInfo.stakeToReward());
            }
            copy.stakeToReward(Math.max(0, stakeToReward));
        }
        stakingInfoStore.put(nodeId, copy.build());
    }

    /**
     * Adjusts the stakes of the nodes after an upgrade based on the given {@link NodeInfo} list from the current
     * address book and the given {@link Configuration}, and returns the synthetic {@link StreamBuilder}
     * from the given context that should externalize these changes.
     * <p>
     * Also clears any pending rewards from the {@link NetworkStakingRewards} singleton for nodes that are no
     * longer in the address book.
     *
     * @param networkInfo the list of node infos from the address book
     * @param config the configuration for the node
     * @param infoStore the writable store for the staking info
     * @param rewardsStore the store for the staking rewards
     */
    public void adjustPostUpgradeStakes(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final Configuration config,
            @NonNull final WritableStakingInfoStore infoStore,
            @NonNull final WritableNetworkStakingRewardsStore rewardsStore) {
        requireNonNull(infoStore);
        requireNonNull(networkInfo);
        requireNonNull(config);
        requireNonNull(rewardsStore);
        final var preUpgradeNodeIds = infoStore.getAll();
        preUpgradeNodeIds.stream().sorted().forEach(nodeId -> {
            final var stakingInfo = requireNonNull(infoStore.get(nodeId));
            if (!networkInfo.containsNode(nodeId) && !stakingInfo.deleted()) {
                infoStore.put(
                        nodeId,
                        stakingInfo.copyBuilder().weight(0).deleted(true).build());
                log.info("Marked node{} as deleted since it has been removed from the address book", nodeId);
                // None of this node's rewards can ever be claimed now, so clear them from pending
                final var rewards = asStakingRewardBuilder(rewardsStore)
                        .pendingRewards(rewardsStore.pendingRewards() - stakingInfo.pendingRewards())
                        .build();
                rewardsStore.put(rewards);
            }
        });
        // Validate if any new nodes are added in addressBook and not in staking info.
        // If so, add them to staking info/ with weight 0. Also update maxStake and
        // minStake for the new nodes.
        completeUpdateFromNewAddressBook(infoStore, networkInfo.addressBook(), config);
    }

    private void completeUpdateFromNewAddressBook(
            @NonNull final WritableStakingInfoStore store,
            @NonNull final List<NodeInfo> nodeInfos,
            @NonNull final Configuration config) {
        final var stakingConfig = config.getConfigData(StakingConfig.class);
        final var numRewardHistoryStoredPeriods = stakingConfig.rewardHistoryNumStoredPeriods();
        final long maxStakePerNode = stakingConfig.maxStake();
        for (final var nodeId : nodeInfos) {
            final var stakingInfo = store.get(nodeId.nodeId());
            if (stakingInfo != null) {
                if (stakingInfo.maxStake() != maxStakePerNode) {
                    store.put(
                            nodeId.nodeId(),
                            stakingInfo.copyBuilder().maxStake(maxStakePerNode).build());
                }
            } else {
                final var newNodeStakingInfo = StakingNodeInfo.newBuilder()
                        .nodeNumber(nodeId.nodeId())
                        .maxStake(maxStakePerNode)
                        .minStake(stakingConfig.minStake())
                        .rewardSumHistory(
                                nCopies(numRewardHistoryStoredPeriods + 1, 0L).toArray(Long[]::new))
                        .weight(0)
                        .build();
                store.putAndIncrementCount(nodeId.nodeId(), newNodeStakingInfo);
            }
        }
    }
}
