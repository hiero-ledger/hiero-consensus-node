// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.hapi.util.HapiUtils.accountIdsEqual;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakeIdChangeType.FROM_ACCOUNT_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.analyzeStakingAccounts;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.hasStakeMetaChanges;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.roundedToHbar;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.totalStake;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.StakingAccountAnalysis;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.StakingAnalysisScratch;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This handler manages the paying staking rewards for the accounts.
 */
@Singleton
public class StakingRewardsHandlerImpl implements StakingRewardsHandler {
    private static final Logger log = LogManager.getLogger(StakingRewardsHandlerImpl.class);
    private final StakingRewardsDistributor rewardsPayer;
    private final StakePeriodManager stakePeriodManager;
    private final StakeInfoHelper stakeInfoHelper;
    private final EntityIdFactory entityIdFactory;

    /**
     * Default constructor for injection.
     * @param rewardsPayer the rewards payer
     * @param stakePeriodManager the stake period manager
     * @param stakeInfoHelper the stake info helper
     */
    @Inject
    public StakingRewardsHandlerImpl(
            @NonNull final StakingRewardsDistributor rewardsPayer,
            @NonNull final StakePeriodManager stakePeriodManager,
            @NonNull final StakeInfoHelper stakeInfoHelper,
            @NonNull final EntityIdFactory entityIdFactory) {
        this.rewardsPayer = requireNonNull(rewardsPayer);
        this.stakePeriodManager = requireNonNull(stakePeriodManager);
        this.stakeInfoHelper = requireNonNull(stakeInfoHelper);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    /** {@inheritDoc} */
    @Override
    public Map<AccountID, Long> applyStakingRewards(
            @NonNull final FinalizeContext context,
            @NonNull final Set<AccountID> explicitRewardReceivers,
            @NonNull final Map<AccountID, Long> prePaidRewards) {
        return applyStakingRewardsWithAnalysis(context, explicitRewardReceivers, prePaidRewards, null)
                .rewardsPaid();
    }

    /** {@inheritDoc} */
    @Override
    public StakingRewardsResult applyStakingRewardsWithAnalysis(
            @NonNull final FinalizeContext context,
            @NonNull final Set<AccountID> explicitRewardReceivers,
            @NonNull final Map<AccountID, Long> prePaidRewards) {
        return applyStakingRewardsWithAnalysis(context, explicitRewardReceivers, prePaidRewards, null);
    }

    @Override
    public StakingRewardsResult applyStakingRewardsWithAnalysis(
            @NonNull final FinalizeContext context,
            @NonNull final Set<AccountID> explicitRewardReceivers,
            @NonNull final Map<AccountID, Long> prePaidRewards,
            @Nullable final StakingAnalysisScratch scratch) {
        requireNonNull(context);
        requireNonNull(explicitRewardReceivers);
        final var writableStore = context.writableStore(WritableAccountStore.class);
        final var analysis =
                analyzeStakingAccounts(writableStore, explicitRewardReceivers, prePaidRewards.keySet(), scratch);
        if (!analysis.hasStakingWork() && prePaidRewards.isEmpty()) {
            return new StakingRewardsResult(Collections.emptyMap(), analysis.originalAccounts());
        }

        final var stakingRewardsStore = context.writableStore(WritableNetworkStakingRewardsStore.class);
        final var stakingInfoStore = context.writableStore(WritableStakingInfoStore.class);
        final var accountsConfig = context.configuration().getConfigData(AccountsConfig.class);

        final var stakingRewardAccountId = entityIdFactory.newAccountId(accountsConfig.stakingRewardAccount());
        final var consensusNow = context.consensusTime();
        final var rewardReceivers = new LinkedHashSet<>(analysis.rewardReceivers());
        // We don't want to repeat any rewards that have already been paid (in current implementation, this means during
        // a SCHEDULED dispatch)
        rewardReceivers.removeAll(prePaidRewards.keySet());
        // Pay rewards to all possible reward receivers, returns all rewards paid
        final var recordBuilder = context.userTransactionRecordBuilder(DeleteCapableTransactionStreamBuilder.class);
        final var rewardsPaid = rewardsPayer.payRewardsIfPending(
                rewardReceivers,
                stakingRewardAccountId,
                writableStore,
                stakingRewardsStore,
                stakingInfoStore,
                consensusNow,
                recordBuilder,
                analysis.originalAccounts());

        // Decrease staking reward account balance by rewardPaid amount
        decreaseStakeRewardAccountBalance(rewardsPaid, stakingRewardAccountId, writableStore);

        if (!context.isScheduleDispatch()) {
            // We only manage stake metadata once, at the end of a transaction; but to do
            // this correctly, we need to include information about any rewards paid during
            // a SCHEDULED dispatch
            rewardReceivers.addAll(prePaidRewards.keySet());
            rewardsPaid.putAll(prePaidRewards);
            // Apply all changes related to stakedId changes, and adjust stakedToMe
            // for all accounts staking to an account
            adjustStakedToMeForAccountStakees(writableStore, analysis);
            // Adjust stakes for nodes and account's staking metadata
            adjustStakeMetadata(
                    writableStore,
                    stakingInfoStore,
                    stakingRewardsStore,
                    consensusNow,
                    rewardsPaid,
                    rewardReceivers,
                    analysis);

            // Don't double-report prepaid rewards in the parent record
            if (!prePaidRewards.isEmpty()) {
                for (AccountID accountID : prePaidRewards.keySet()) {
                    rewardsPaid.remove(accountID);
                }
            }
        }

        return new StakingRewardsResult(rewardsPaid, analysis.originalAccounts());
    }

    /**
     * Iterates through all modifications in state and sees if any account is staked to an account.
     * If there is an account X that staked to account Y. If account Y is staked to a node, then
     * change in X balance will contribute to Y's stakedToMe balance. This function will update
     * Y's stakedToMe balance which will add Y to the state modifications. In adjustStakeMetadata step, we will
     * assess if Y is staked to a node, and if so, we will update the node stake metadata.
     *
     * @param writableStore the store to write to for updated values
     * @param analysis the dispatch-scoped account analysis
     */
    private void adjustStakedToMeForAccountStakees(
            @NonNull final WritableAccountStore writableStore, @NonNull final StakingAccountAnalysis analysis) {
        for (final var accountAnalysis : analysis.modifiedAccounts()) {
            final var id = accountAnalysis.accountId();
            final var originalAccount = accountAnalysis.originalAccount();
            // In the current system, it is impossible for a user transaction to remove an account;
            // it can only be marked deleted
            final var modifiedAccount = requireNonNull(writableStore.get(id));
            final var scenario = accountAnalysis.stakeIdChangeType();
            // If the stakedId is changed from account or to account. Then we need to update the
            // stakedToMe balance of new account. This is needed in order to trigger next level rewards
            // if the account is staked to node
            if (scenario.equals(FROM_ACCOUNT_TO_ACCOUNT)
                    && accountIdsEqual(
                            requireNonNull(originalAccount).stakedAccountIdOrThrow(),
                            modifiedAccount.stakedAccountId())) {
                final var roundedFinalBalance = roundedToHbar(modifiedAccount.tinybarBalance());
                final var roundedInitialBalance = roundedToHbar(originalAccount.tinybarBalance());
                final var delta = roundedFinalBalance - roundedInitialBalance;
                // Even if the stakee's total stake hasn't changed, we still want to
                // trigger a reward situation whenever the staker balance changes
                if (modifiedAccount.tinybarBalance() != originalAccount.tinybarBalance()) {
                    updateStakedToMeFor(modifiedAccount.stakedAccountId(), delta, writableStore);
                }
            } else {
                if (scenario.withdrawsFromAccount()) {
                    final var curStakedAccountId =
                            requireNonNull(originalAccount).stakedAccountId();
                    final var roundedInitialBalance = roundedToHbar(originalAccount.tinybarBalance());
                    updateStakedToMeFor(curStakedAccountId, -roundedInitialBalance, writableStore);
                }
                if (scenario.awardsToAccount()) {
                    final var newStakedAccountId = modifiedAccount.stakedAccountId();
                    final var balance = modifiedAccount.tinybarBalance();
                    final var roundedFinalBalance = roundedToHbar(balance);
                    updateStakedToMeFor(newStakedAccountId, roundedFinalBalance, writableStore);
                }
            }
        }
    }

    /**
     * If the account is updated to be staking to a node or withdraws staking from node, adjusts the stakes for those
     * nodes. It also updates stakeAtStartOfLastRewardedPeriod and stakePeriodStart for accounts.
     *
     * @param writableStore      writable account store
     * @param stakingInfoStore   writable staking info store
     * @param stakingRewardStore writable staking reward store
     * @param consensusNow       consensus time
     * @param paidRewards        map of account to rewards paid
     * @param rewardReceivers   set of reward receivers
     */
    private void adjustStakeMetadata(
            final WritableAccountStore writableStore,
            final WritableStakingInfoStore stakingInfoStore,
            final WritableNetworkStakingRewardsStore stakingRewardStore,
            final Instant consensusNow,
            final Map<AccountID, Long> paidRewards,
            final Set<AccountID> rewardReceivers,
            final StakingAccountAnalysis analysis) {
        final var reviewedAccountIds = new LinkedHashSet<AccountID>();
        for (final var accountAnalysis : analysis.modifiedAccounts()) {
            reviewedAccountIds.add(accountAnalysis.accountId());
            adjustStakeMetadataFor(
                    accountAnalysis.accountId(),
                    accountAnalysis.originalAccount(),
                    accountAnalysis.stakeIdChangeType(),
                    accountAnalysis.hasStakeMetadataChanges(),
                    writableStore,
                    stakingInfoStore,
                    stakingRewardStore,
                    consensusNow,
                    paidRewards);
        }

        final var additionalAccountIds = LinkedHashSet.<AccountID>newLinkedHashSet(rewardReceivers.size()
                + analysis.stakedToMeAdjustmentReceivers().size()
                + paidRewards.size());
        additionalAccountIds.addAll(rewardReceivers);
        additionalAccountIds.addAll(analysis.stakedToMeAdjustmentReceivers());
        additionalAccountIds.addAll(paidRewards.keySet());
        additionalAccountIds.addAll(writableStore.modifiedAccountsInState());
        for (final var id : additionalAccountIds) {
            if (reviewedAccountIds.add(id)) {
                final var originalAccount = analysis.originalAccounts().containsKey(id)
                        ? analysis.originalAccounts().get(id)
                        : writableStore.getOriginalValue(id);
                final var modifiedAccount = requireNonNull(writableStore.get(id));
                adjustStakeMetadataFor(
                        id,
                        originalAccount,
                        StakeIdChangeType.forCase(originalAccount, modifiedAccount),
                        hasStakeMetaChanges(originalAccount, modifiedAccount),
                        writableStore,
                        stakingInfoStore,
                        stakingRewardStore,
                        consensusNow,
                        paidRewards);
            }
        }
    }

    private void adjustStakeMetadataFor(
            final AccountID id,
            final Account originalAccount,
            final StakeIdChangeType scenario,
            final boolean containStakeMetaChanges,
            final WritableAccountStore writableStore,
            final WritableStakingInfoStore stakingInfoStore,
            final WritableNetworkStakingRewardsStore stakingRewardStore,
            final Instant consensusNow,
            final Map<AccountID, Long> paidRewards) {
        final var modifiedAccount = requireNonNull(writableStore.get(id));

        // If this scenario is changing StakedId from a node or to a node, change stake of those nodes
        if ((scenario.withdrawsFromNode() || scenario.awardsToNode())) {
            adjustNodeStakes(
                    scenario,
                    originalAccount,
                    modifiedAccount,
                    stakingInfoStore,
                    stakingRewardStore,
                    containStakeMetaChanges,
                    consensusNow);
        }

        // If the account is rewarded. The reward can also be zero, if the account has zero stake
        final var rewardSituation = paidRewards.containsKey(id);
        final var reward = paidRewards.getOrDefault(id, 0L);

        final long stakeAtStartOfLastRewardedPeriod;
        final boolean updateStakeAtStartOfLastRewardedPeriod;
        if (containStakeMetaChanges) {
            stakeAtStartOfLastRewardedPeriod = NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE;
            updateStakeAtStartOfLastRewardedPeriod = true;
        } else if (shouldUpdateStakeAtStartOfLastRewardPeriod(
                originalAccount, rewardSituation, reward, stakingRewardStore, consensusNow)) {
            stakeAtStartOfLastRewardedPeriod = roundedToHbar(totalStake(originalAccount));
            updateStakeAtStartOfLastRewardedPeriod = true;
        } else {
            stakeAtStartOfLastRewardedPeriod = 0;
            updateStakeAtStartOfLastRewardedPeriod = false;
        }

        // Update stakePeriodStart if account is rewarded or if reward is zero and account has zero stake
        // If the account is autoCreated it will not be rewarded
        final var wasRewarded = rewardSituation
                && (reward > 0
                        || (reward == 0
                                && earnedZeroRewardsBecauseOfZeroStake(
                                        originalAccount, stakingRewardStore, consensusNow)));
        final var stakePeriodStart = stakePeriodManager.startUpdateFor(
                originalAccount, modifiedAccount, wasRewarded, containStakeMetaChanges);
        if (updateStakeAtStartOfLastRewardedPeriod || stakePeriodStart != -1) {
            final var copy = modifiedAccount.copyBuilder();
            if (updateStakeAtStartOfLastRewardedPeriod) {
                copy.stakeAtStartOfLastRewardedPeriod(stakeAtStartOfLastRewardedPeriod);
            }
            if (stakePeriodStart != -1) {
                copy.stakePeriodStart(stakePeriodStart);
            }
            writableStore.put(copy.build());
        }
    }

    /**
     * Given an existing account that was in a reward situation and earned zero rewards, checks if
     * this was because the account had effective stake of zero whole hbars during the rewardable
     * periods. (The alternative is that it had zero rewardable periods; i.e., it started staking
     * this period, or the last.)
     *
     * <p>This distinction matters because in the case of zero stake, we still want to update the
     * account's {@code stakePeriodStart} and {@code stakeAtStartOfLastRewardedPeriod}. Otherwise,
     * we don't want to update {@code stakePeriodStart}; and only want to update {@code
     * stakeAtStartOfLastRewardedPeriod} if the account began staking in exactly the last period.
     *
     * @param account an account presumed to have just earned zero rewards
     * @return whether the zero rewards were due to having zero stake
     */
    private boolean earnedZeroRewardsBecauseOfZeroStake(
            @NonNull final Account account,
            @NonNull final ReadableNetworkStakingRewardsStore stakingRewardStore,
            @NonNull final Instant consensusNow) {
        return Objects.requireNonNull(account).stakePeriodStart()
                < stakePeriodManager.firstNonRewardableStakePeriod(stakingRewardStore);
    }

    private void adjustNodeStakes(
            final StakeIdChangeType scenario,
            final Account originalAccount,
            final Account modifiedAccount,
            final WritableStakingInfoStore stakingInfoStore,
            final WritableNetworkStakingRewardsStore stakingRewardStore,
            final boolean containStakeMetaChanges,
            final Instant consensusNow) {
        if (scenario.withdrawsFromNode()) {
            final var currentStakedNodeId = originalAccount.stakedNodeId();
            // SENTINEL_NODE_ID is a special value to remove the account's staked node ID.
            if (currentStakedNodeId != SENTINEL_NODE_ID) {
                stakeInfoHelper.withdrawStake(currentStakedNodeId, originalAccount, stakingInfoStore);
                if (containStakeMetaChanges) {
                    // Pending rewards are calculated midnight each day for every account.
                    // If this account has changed to a different stakeId or choose to decline reward
                    // in mid of the day, it will not receive rewards for that day.
                    // So, it will be leaving some rewards from its current node unclaimed.
                    // We need to record that, so we don't include them in the pendingRewards
                    // calculation later
                    final var effectiveStakeRewardStart =
                            rewardableStakeStartFor(stakingRewardStore.isStakingRewardsActivated(), originalAccount);
                    stakeInfoHelper.increaseUnclaimedStakeRewards(
                            currentStakedNodeId, effectiveStakeRewardStart, stakingInfoStore);
                }
            }
        }
        // If account chose to stake to a node, the new node's stake will be increased
        // by the account's stake amount
        if (scenario.awardsToNode() && !modifiedAccount.deleted()) {
            final var modifiedStakedNodeId = modifiedAccount.stakedNodeId();
            // We need the latest updates to balance and stakedToMe for the account in modifications also
            // to be reflected in stake awarded. So use the modifiedAccount instead of originalAccount
            if (modifiedStakedNodeId != SENTINEL_NODE_ID) {
                stakeInfoHelper.awardStake(modifiedStakedNodeId, modifiedAccount, stakingInfoStore);
            }
        }
    }

    /**
     * List of condition sto validate if the account need to update stakeAtStartOfLastRewardedPeriod.
     * @param account the account
     * @param isRewarded if the account is rewarded
     * @param reward the reward amount
     * @param stakingRewardStore the staking reward store
     * @param consensusNow the consensus time
     * @return true if the account need to update stakeAtStartOfLastRewardedPeriod, false otherwise
     */
    public boolean shouldUpdateStakeAtStartOfLastRewardPeriod(
            @Nullable final Account account,
            final boolean isRewarded,
            final long reward,
            @NonNull final ReadableNetworkStakingRewardsStore stakingRewardStore,
            @NonNull final Instant consensusNow) {
        if (account == null
                || account.stakedNodeIdOrElse(SENTINEL_NODE_ID) == SENTINEL_NODE_ID
                || account.declineReward()) {
            // If the account is created in this transaction, or it is not staking to a node,
            // or it has chosen to decline reward, we don't need to remember stakeStart,
            // because it can't receive reward today
            return false;
        }
        if (!isRewarded) {
            // If the account is not rewarded in current transaction, it mean stake of node will not be changed
            return false;
        } else if (reward > 0) {
            // Alice earned a reward without changing her stake metadata, so she is still eligible
            // for a reward today; since her total stake will change this txn, we remember its
            // current value to reward her correctly for today no matter what happens later on
            return true;
        } else {
            // At this point, Alice is an account staking to a node, accepting rewards, and in
            // a reward situation---who nonetheless received zero rewards. There are essentially
            // four scenarios:
            //   1. Alice's stakePeriodStart is before the first non-rewardable period, but
            //   she was either staking zero whole hbars during those periods (or the reward rate
            //   was zero).
            //   2. Alice's stakePeriodStart is the first non-rewardable period because she
            //   was already rewarded earlier today.
            //   3. Alice's stakePeriodStart is the first non-rewardable period, but she was not
            //   rewarded today.
            //   4. Alice's stakePeriodStart is the current period.
            // We need to record her current stake as totalStakeAtStartOfLastRewardedPeriod in
            // scenarios 1 and 3, but not 2 and 4. (As noted below, in scenario 2 we want to
            // preserve an already-recorded memory of her stake at the beginning of this period.
            // In scenario 4 there is no point in recording anything---her stake will go unused.)
            if (earnedZeroRewardsBecauseOfZeroStake(account, stakingRewardStore, consensusNow)) {
                return true;
            }
            if (account.stakeAtStartOfLastRewardedPeriod() != NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE) {
                // Alice was in a reward situation, but did not earn anything because she already
                // received a reward earlier today; we preserve our memory of her stake from then
                return false;
            }
            // Alice was in a reward situation for the first time today, but received nothing---
            // either because she is staking < 1 hbar, or because she started staking only
            // today or yesterday; we don't care about the exact reason, we just remember
            // her total stake as long as she didn't begin staking today exactly
            return account.stakePeriodStart() < stakePeriodManager.currentStakePeriod();
        }
    }

    private long rewardableStakeStartFor(final boolean rewardsActivated, @NonNull final Account account) {
        if (!rewardsActivated || account.declineReward()) {
            return 0;
        }
        final var startPeriod = account.stakePeriodStart();
        final var currentPeriod = stakePeriodManager.currentStakePeriod();
        if (startPeriod >= currentPeriod) {
            return 0;
        } else {
            if (isRewardedSinceLastStakeMetaChange(account) && (startPeriod == currentPeriod - 1)) {
                // Special case---this account was already rewarded today, so its current stake
                // has almost certainly changed from what it was at the start of the day
                return account.stakeAtStartOfLastRewardedPeriod();
            } else {
                return roundedToHbar(totalStake(account));
            }
        }
    }

    private void decreaseStakeRewardAccountBalance(
            final Map<AccountID, Long> rewardsPaid,
            final AccountID stakingRewardAccountId,
            final WritableAccountStore writableAccountStore) {
        if (!rewardsPaid.isEmpty()) {
            long totalPaidRewards = 0L;
            for (final var value : rewardsPaid.values()) {
                totalPaidRewards += value;
            }
            if (totalPaidRewards > 0) {
                final var stakingRewardAccount = writableAccountStore.get(stakingRewardAccountId);
                final var finalBalance = stakingRewardAccount.tinybarBalance() - totalPaidRewards;
                final var copy = stakingRewardAccount.copyBuilder();
                copy.tinybarBalance(finalBalance);
                writableAccountStore.put(copy.build());
            }
        }
    }

    private void updateStakedToMeFor(
            @Nullable final AccountID stakeeId,
            final long roundedFinalBalance,
            @NonNull final WritableAccountStore writableStore) {
        // stakeeId is null when SENTINEL_ACCOUNT_ID sent as staked_account_id in update crypto transaction
        if (stakeeId != null) {
            final var stakee = writableStore.get(stakeeId);
            if (stakee == null) {
                // This should be impossible, we try to enforce staking only to existing accounts; but
                // it doesn't justify failing a user transaction, so we just log it
                log.error("Stakee account {} not found in the store", stakeeId);
                return;
            }
            if (stakee.deleted()) {
                // A deleted stakee no longer participates in staking; updating its stakedToMe would
                // put it back into the modified-accounts set and trigger a withdraw-without-award in
                // adjustNodeStakes, which corrupts the node's stake total on every subsequent
                // transaction that touches any account still pointing at this deleted stakee.
                return;
            }
            final var initialStakedToMe = stakee.stakedToMe();
            final var finalStakedToMe = initialStakedToMe + roundedFinalBalance;
            if (finalStakedToMe < 0) {
                log.error("StakedToMe for account {} is negative after reward distribution, set it to 0", stakeeId);
            }
            final var copy = stakee.copyBuilder()
                    .stakedToMe(finalStakedToMe < 0 ? 0 : finalStakedToMe)
                    .build();
            writableStore.put(copy);
        }
    }
}
