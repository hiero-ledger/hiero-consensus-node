// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.hapi.util.HapiUtils.accountIdsEqual;
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
import java.util.HashMap;
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
     * Analyzes the accounts modified by a dispatch in their encounter order. This is the only pass over the dispatch's
     * modified-account set needed by staking reward finalization.
     *
     * @param writableAccountStore the account store
     * @param explicitRewardReceivers extra accounts to consider for rewards
     * @param prePaidRewardReceivers accounts rewarded by a preceding scheduled dispatch
     * @return the immutable dispatch-scoped staking analysis
     */
    public static StakingAccountAnalysis analyzeStakingAccounts(
            @NonNull final WritableAccountStore writableAccountStore,
            @NonNull final Set<AccountID> explicitRewardReceivers,
            @NonNull final Set<AccountID> prePaidRewardReceivers) {
        return analyzeStakingAccounts(writableAccountStore, explicitRewardReceivers, prePaidRewardReceivers, null);
    }

    /**
     * Analyzes modified accounts using {@code scratch} when provided. Scratch collections are cleared on entry and
     * remain valid only until the next analysis that reuses the same scratch.
     */
    public static StakingAccountAnalysis analyzeStakingAccounts(
            @NonNull final WritableAccountStore writableAccountStore,
            @NonNull final Set<AccountID> explicitRewardReceivers,
            @NonNull final Set<AccountID> prePaidRewardReceivers,
            @Nullable final StakingAnalysisScratch scratch) {
        requireNonNull(writableAccountStore);
        requireNonNull(explicitRewardReceivers);
        requireNonNull(prePaidRewardReceivers);

        final var modifiedAccountIds = writableAccountStore.modifiedAccountsInState();
        final List<ModifiedAccountAnalysis> modifiedAccounts;
        final Map<AccountID, Account> originalAccounts;
        final Set<AccountID> stakedToMeRewardReceivers;
        final Set<AccountID> stakedToMeAdjustmentReceivers;
        final Set<AccountID> canonicalRewardReceivers;
        final Set<AccountID> possibleRewardReceivers;
        if (scratch == null) {
            modifiedAccounts = new ArrayList<>(modifiedAccountIds.size());
            originalAccounts = HashMap.newHashMap(modifiedAccountIds.size() + 4);
            stakedToMeRewardReceivers = new LinkedHashSet<>();
            stakedToMeAdjustmentReceivers = new LinkedHashSet<>();
            canonicalRewardReceivers = new LinkedHashSet<>();
            possibleRewardReceivers =
                    LinkedHashSet.newLinkedHashSet(modifiedAccountIds.size() + explicitRewardReceivers.size());
        } else {
            scratch.reset();
            modifiedAccounts = scratch.modifiedAccounts;
            originalAccounts = scratch.originalAccounts;
            stakedToMeRewardReceivers = scratch.stakedToMeRewardReceivers;
            stakedToMeAdjustmentReceivers = scratch.stakedToMeAdjustmentReceivers;
            canonicalRewardReceivers = scratch.canonicalRewardReceivers;
            possibleRewardReceivers = scratch.possibleRewardReceivers;
        }
        var hasStakeMetadataChanges = false;

        for (final var id : modifiedAccountIds) {
            final var originalAccount = writableAccountStore.getOriginalValue(id);
            final var currentAccount = requireNonNull(writableAccountStore.get(id));
            originalAccounts.put(id, originalAccount);

            final var stakeIdChangeType = StakeIdChangeType.forCase(originalAccount, currentAccount);
            final var containsStakeMetadataChanges = hasStakeMetaChanges(originalAccount, currentAccount);
            final var rewardSituation = isRewardSituation(currentAccount, originalAccount);
            modifiedAccounts.add(new ModifiedAccountAnalysis(
                    id, originalAccount, stakeIdChangeType, containsStakeMetadataChanges, rewardSituation));
            hasStakeMetadataChanges |= containsStakeMetadataChanges;
            if (rewardSituation) {
                canonicalRewardReceivers.add(id);
            }

            if (stakeIdChangeType == StakeIdChangeType.FROM_ACCOUNT_TO_ACCOUNT
                    && accountIdsEqual(
                            requireNonNull(originalAccount).stakedAccountIdOrThrow(),
                            currentAccount.stakedAccountId())) {
                if (currentAccount.tinybarBalance() != originalAccount.tinybarBalance()) {
                    addStakedToMeReceiver(
                            currentAccount.stakedAccountId(),
                            writableAccountStore,
                            originalAccounts,
                            stakedToMeAdjustmentReceivers,
                            stakedToMeRewardReceivers);
                }
            } else {
                if (stakeIdChangeType.withdrawsFromAccount()) {
                    addStakedToMeReceiver(
                            requireNonNull(originalAccount).stakedAccountId(),
                            writableAccountStore,
                            originalAccounts,
                            stakedToMeAdjustmentReceivers,
                            stakedToMeRewardReceivers);
                }
                if (stakeIdChangeType.awardsToAccount()) {
                    addStakedToMeReceiver(
                            currentAccount.stakedAccountId(),
                            writableAccountStore,
                            originalAccounts,
                            stakedToMeAdjustmentReceivers,
                            stakedToMeRewardReceivers);
                }
            }
        }

        possibleRewardReceivers.addAll(stakedToMeRewardReceivers);
        possibleRewardReceivers.addAll(canonicalRewardReceivers);
        for (final var id : explicitRewardReceivers) {
            if (isCurrentlyStakedToNode(writableAccountStore.get(id))) {
                cacheOriginal(id, writableAccountStore, originalAccounts);
                possibleRewardReceivers.add(id);
            }
        }
        for (final var id : prePaidRewardReceivers) {
            cacheOriginal(id, writableAccountStore, originalAccounts);
        }

        return new StakingAccountAnalysis(
                modifiedAccounts,
                possibleRewardReceivers,
                stakedToMeAdjustmentReceivers,
                originalAccounts,
                hasStakeMetadataChanges);
    }

    private static void addStakedToMeReceiver(
            @Nullable final AccountID id,
            @NonNull final WritableAccountStore writableAccountStore,
            @NonNull final Map<AccountID, Account> originalAccounts,
            @NonNull final Set<AccountID> stakedToMeAdjustmentReceivers,
            @NonNull final Set<AccountID> stakedToMeRewardReceivers) {
        if (id == null) {
            return;
        }
        final var originalStakee = cacheOriginal(id, writableAccountStore, originalAccounts);
        if (originalStakee != null && !originalStakee.deleted() && originalStakee.hasStakedNodeId()) {
            stakedToMeRewardReceivers.add(id);
        }
        final var currentStakee = writableAccountStore.get(id);
        if (currentStakee != null && !currentStakee.deleted()) {
            stakedToMeAdjustmentReceivers.add(id);
        }
    }

    @Nullable
    private static Account cacheOriginal(
            @NonNull final AccountID id,
            @NonNull final WritableAccountStore writableAccountStore,
            @NonNull final Map<AccountID, Account> originalAccounts) {
        if (!originalAccounts.containsKey(id)) {
            originalAccounts.put(id, writableAccountStore.getOriginalValue(id));
        }
        return originalAccounts.get(id);
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
        final var accountAmounts = new ArrayList<AccountAmount>(balanceAdjustments.size());
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

    /**
     * Immutable staking facts derived for one account modified by the dispatch. The current account is deliberately not
     * retained because reward and staked-to-me mutations can replace it before metadata adjustment.
     */
    public record ModifiedAccountAnalysis(
            @NonNull AccountID accountId,
            @Nullable Account originalAccount,
            @NonNull StakeIdChangeType stakeIdChangeType,
            boolean hasStakeMetadataChanges,
            boolean rewardSituation) {
        public ModifiedAccountAnalysis {
            requireNonNull(accountId);
            requireNonNull(stakeIdChangeType);
        }
    }

    /**
     * Dispatch-scoped staking analysis. The collections are owned by the analysis, or by a
     * {@link StakingAnalysisScratch} reused on the same thread; callers must not mutate them.
     */
    public record StakingAccountAnalysis(
            @NonNull List<ModifiedAccountAnalysis> modifiedAccounts,
            @NonNull Set<AccountID> rewardReceivers,
            @NonNull Set<AccountID> stakedToMeAdjustmentReceivers,
            @NonNull Map<AccountID, Account> originalAccounts,
            boolean hasStakeMetadataChanges) {
        public StakingAccountAnalysis {
            requireNonNull(modifiedAccounts);
            requireNonNull(rewardReceivers);
            requireNonNull(stakedToMeAdjustmentReceivers);
            requireNonNull(originalAccounts);
        }

        /**
         * Returns whether the analysis found any staking work. A zero reward amount is not considered proof that no
         * work remains, because zero rewards can still require account metadata updates.
         *
         * @return whether staking finalization has work
         */
        public boolean hasStakingWork() {
            return !rewardReceivers.isEmpty() || !stakedToMeAdjustmentReceivers.isEmpty() || hasStakeMetadataChanges;
        }
    }

    /**
     * Reusable collections for {@link #analyzeStakingAccounts}. Depth-scoped by {@link com.hedera.node.app.service.token.impl.RecordFinalizerBase}
     * so nested child finalization cannot overwrite a parent analysis.
     */
    public static final class StakingAnalysisScratch {
        private final List<ModifiedAccountAnalysis> modifiedAccounts = new ArrayList<>();
        private final Map<AccountID, Account> originalAccounts = new HashMap<>();
        private final Set<AccountID> stakedToMeRewardReceivers = new LinkedHashSet<>();
        private final Set<AccountID> stakedToMeAdjustmentReceivers = new LinkedHashSet<>();
        private final Set<AccountID> canonicalRewardReceivers = new LinkedHashSet<>();
        private final Set<AccountID> possibleRewardReceivers = new LinkedHashSet<>();

        public void reset() {
            modifiedAccounts.clear();
            originalAccounts.clear();
            stakedToMeRewardReceivers.clear();
            stakedToMeAdjustmentReceivers.clear();
            canonicalRewardReceivers.clear();
            possibleRewardReceivers.clear();
        }
    }
}
