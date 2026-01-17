// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.ACCOUNT_AMOUNT_COMPARATOR;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.hasStakeMetaChanges;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class for staking reward calculations.
 */
@Singleton
public class StakingRewardsHelper {
    private static final Logger log = LogManager.getLogger(StakingRewardsHelper.class);
    /**
     * The maximum pending rewards that can be paid out in a single staking period, which is 50B hbar.
     */
    public static final long MAX_PENDING_REWARDS = 50_000_000_000L * HBARS_TO_TINYBARS;

    private final boolean assumeContiguousPeriods;

    /**
     * Default constructor for injection.
     */
    @Inject
    public StakingRewardsHelper(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        this.assumeContiguousPeriods = configProvider
                .getConfiguration()
                .getConfigData(StakingConfig.class)
                .assumeContiguousPeriods();
    }

    /**
     * Looks through all the accounts modified in state and returns a list of accounts which are staked to a node
     * and has stakedId or stakedToMe or balance or declineReward changed in this transaction.
     *
     * @param writableAccountStore The store to write to for updated values and original values
     * @param stakeToMeRewardReceivers The accounts which are staked to a node and are special reward receivers
     * @param explicitRewardReceivers Extra accounts to consider for rewards
     * @return A list of accounts which are staked to a node and could possibly receive a reward
     */
    public static Set<AccountID> getAllRewardReceivers(
            final WritableAccountStore writableAccountStore,
            final Set<AccountID> stakeToMeRewardReceivers,
            @NonNull final Set<AccountID> explicitRewardReceivers) {
        final var possibleRewardReceivers = new LinkedHashSet<>(stakeToMeRewardReceivers);
        addIdsInRewardSituation(
                writableAccountStore,
                writableAccountStore.modifiedAccountsInState(),
                possibleRewardReceivers,
                FilterType.IS_CANONICAL_REWARD_SITUATION);
        addIdsInRewardSituation(
                writableAccountStore, explicitRewardReceivers, possibleRewardReceivers, FilterType.IS_STAKED_TO_NODE);
        return possibleRewardReceivers;
    }

    private enum FilterType {
        IS_CANONICAL_REWARD_SITUATION,
        IS_STAKED_TO_NODE
    }

    private static void addIdsInRewardSituation(
            @NonNull final WritableAccountStore writableAccountStore,
            @NonNull final Collection<AccountID> ids,
            @NonNull final Set<AccountID> possibleRewardReceivers,
            @NonNull final FilterType filterType) {
        for (final AccountID id : ids) {
            if (filterType == FilterType.IS_CANONICAL_REWARD_SITUATION) {
                final var modifiedAcct = requireNonNull(writableAccountStore.get(id));
                final var originalAcct = writableAccountStore.getOriginalValue(id);
                if (isRewardSituation(modifiedAcct, originalAcct)) {
                    possibleRewardReceivers.add(id);
                }
            } else {
                if (isCurrentlyStakedToNode(writableAccountStore.get(id))) {
                    possibleRewardReceivers.add(id);
                }
            }
        }
    }

    /**
     * Returns true if the account is staked to a node and the current transaction modified the stakedToMe field
     * (by changing balance of the current account or the account which is staking to current account) or
     * declineReward or the stakedId field.
     *
     * @param modifiedAccount the account which is modified in the current transaction and is in modifications
     * @param originalAccount the account before the current transaction
     * @return true if the account is staked to a node and the current transaction modified the stakedToMe field
     */
    private static boolean isRewardSituation(
            @NonNull final Account modifiedAccount, @Nullable final Account originalAccount) {
        requireNonNull(modifiedAccount);
        if (originalAccount == null || originalAccount.stakedNodeIdOrElse(SENTINEL_NODE_ID) == SENTINEL_NODE_ID) {
            return false;
        }

        // No need to check for stakeMetaChanges again here, since they are captured in possibleRewardReceivers
        // in previous step
        final var hasBalanceChange = modifiedAccount.tinybarBalance() != originalAccount.tinybarBalance();
        final var hasStakeMetaChanges = hasStakeMetaChanges(originalAccount, modifiedAccount);
        return hasBalanceChange || hasStakeMetaChanges;
    }

    /**
     * Returns true if there is a non-zero reward paid.
     *
     * @param rewardsPaid the rewards paid (possibly empty or all zero)
     * @return true if there is a non-zero reward paid
     */
    public static boolean requiresExternalization(@NonNull final Map<AccountID, Long> rewardsPaid) {
        if (rewardsPaid.isEmpty()) {
            return false;
        }
        for (final var reward : rewardsPaid.values()) {
            if (reward != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decrease pending rewards on the network staking rewards store by the given amount.
     * Once we pay reward to an account, the pending rewards on the network should be
     * reduced by that amount, since they no more need to be paid.
     * If the node is deleted, we do not decrease the pending rewards on the network, and on the node.
     *
     * @param stakingInfoStore   The store to write to for updated values
     * @param stakingRewardsStore The store to write to for updated values
     * @param amount              The amount to decrease by
     * @param nodeId              The node id to decrease pending rewards for
     */
    public void decreasePendingRewardsBy(
            @NonNull final WritableStakingInfoStore stakingInfoStore,
            @NonNull final WritableNetworkStakingRewardsStore stakingRewardsStore,
            final long amount,
            @NonNull final Long nodeId) {
        // decrement the total pending rewards being tracked for the network
        final var currentPendingRewards = stakingRewardsStore.pendingRewards();
        var newPendingRewards = currentPendingRewards - amount;
        if (newPendingRewards < 0) {
            // If staking periods have been skipped in an environment, it is no longer
            // guaranteed that pending rewards are maintained accurately
            if (assumeContiguousPeriods) {
                log.error(
                        "Pending rewards decreased by {} to a meaningless {}, fixing to zero hbar",
                        amount,
                        newPendingRewards);
            }
            newPendingRewards = 0;
        }
        final var stakingRewards = stakingRewardsStore.get();
        final var copy = stakingRewards.copyBuilder();
        stakingRewardsStore.put(copy.pendingRewards(newPendingRewards).build());

        // decrement pendingRewards per node also
        final var stakingInfo = stakingInfoStore.get(nodeId);
        final var currentNodePendingRewards = stakingInfo.pendingRewards();
        var newNodePendingRewards = currentNodePendingRewards - amount;
        if (newNodePendingRewards < 0) {
            // If staking periods have been skipped in an environment, it is no longer
            // guaranteed that pending rewards are maintained accurately
            if (assumeContiguousPeriods) {
                log.error(
                        "Pending rewards decreased by {} to a meaningless {} for node {}, fixing to zero hbar",
                        amount,
                        newNodePendingRewards,
                        nodeId);
            }
            newNodePendingRewards = 0;
        }
        final var stakingInfoCopy =
                stakingInfo.copyBuilder().pendingRewards(newNodePendingRewards).build();
        stakingInfoStore.put(nodeId, stakingInfoCopy);
    }

    /**
     * Increase pending rewards on the network staking rewards store by the given amount.
     * This is called in EndOdStakingPeriod when we calculate the pending rewards on the network
     * to be paid in next staking period. Whne the node is deleted, we do not increase the pending rewards
     * on the network, and on the node.
     *
     * @param stakingRewardsStore The store to write to for updated values
     * @param amount              The amount to increase by
     * @param currStakingInfo    The current staking info
     * @return The clamped pending rewards
     */
    public StakingNodeInfo increasePendingRewardsBy(
            @NonNull final WritableNetworkStakingRewardsStore stakingRewardsStore,
            final long amount,
            @NonNull final StakingNodeInfo currStakingInfo) {
        requireNonNull(stakingRewardsStore);
        requireNonNull(currStakingInfo);
        // increment the total pending rewards being tracked for the network
        final var currentPendingRewards = stakingRewardsStore.pendingRewards();
        long nodePendingRewards = currStakingInfo.pendingRewards();
        long newNetworkPendingRewards;
        long newNodePendingRewards;
        // Only increase the pending rewards if the node is not deleted
        if (!currStakingInfo.deleted()) {
            newNetworkPendingRewards = clampedAdd(currentPendingRewards, amount);
            newNodePendingRewards = clampedAdd(nodePendingRewards, amount);
        } else {
            newNetworkPendingRewards = currentPendingRewards;
            newNodePendingRewards = 0L;
        }
        if (newNetworkPendingRewards > MAX_PENDING_REWARDS) {
            log.error(
                    "Pending rewards increased by {} to an un-payable {}, fixing to 50B hbar",
                    amount,
                    newNetworkPendingRewards);
            newNetworkPendingRewards = MAX_PENDING_REWARDS;
        }
        if (newNodePendingRewards > MAX_PENDING_REWARDS) {
            log.error(
                    "Pending rewards increased by {} to an un-payable {} for node {}, fixing to 50B hbar",
                    amount,
                    newNodePendingRewards,
                    currStakingInfo.nodeNumber());
            newNodePendingRewards = MAX_PENDING_REWARDS;
        }
        final var stakingRewards = stakingRewardsStore.get();
        final var copy = stakingRewards.copyBuilder();
        stakingRewardsStore.put(copy.pendingRewards(newNetworkPendingRewards).build());

        // Update the individual node pending node rewards. If the node is deleted the pending rewards
        // should be zero
        return currStakingInfo
                .copyBuilder()
                .pendingRewards(newNodePendingRewards)
                .build();
    }

    /**
     * Translates any non-zero balance adjustments in the given map into a list of
     * {@link AccountAmount}s ordered by account id.
     *
     * @param balanceAdjustments the balance adjustments
     * @return the list of account amounts (excluding zero adjustments)
     */
    public static List<AccountAmount> asAccountAmounts(@NonNull final Map<AccountID, Long> balanceAdjustments) {
        final var accountAmounts = new ArrayList<AccountAmount>();
        for (final var entry : balanceAdjustments.entrySet()) {
            if (entry.getValue() != 0) {
                accountAmounts.add(AccountAmount.newBuilder()
                        .accountID(entry.getKey())
                        .amount(entry.getValue())
                        .build());
            }
        }
        accountAmounts.sort(ACCOUNT_AMOUNT_COMPARATOR);
        return accountAmounts;
    }

    private static boolean isCurrentlyStakedToNode(@Nullable final Account account) {
        // Null check here because it's possible for the contract service to naively
        // list the id of an account that doesn't exist in the store, but was created
        // and then reverted inside an overall successful transaction
        return account != null && account.stakedNodeIdOrElse(SENTINEL_NODE_ID) != SENTINEL_NODE_ID;
    }
}
