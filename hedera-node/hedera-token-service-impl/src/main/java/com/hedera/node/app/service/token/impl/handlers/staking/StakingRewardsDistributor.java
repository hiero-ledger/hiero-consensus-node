// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class for paying out staking rewards.
 * This is called for all accounts that are modified in state for the current transaction,
 * and are staking to a node
 */
@Singleton
public class StakingRewardsDistributor {
    private static final Logger log = LogManager.getLogger(StakingRewardsDistributor.class);
    private StakingRewardsHelper stakingRewardHelper;
    private StakeRewardCalculatorImpl rewardCalculator;

    /**
     * Default constructor for injection.
     * @param stakingRewardHelper the staking rewards helper
     * @param rewardCalculator the reward calculator
     */
    @Inject
    public StakingRewardsDistributor(
            @NonNull final StakingRewardsHelper stakingRewardHelper,
            @NonNull final StakeRewardCalculatorImpl rewardCalculator) {
        this.stakingRewardHelper = stakingRewardHelper;
        this.rewardCalculator = rewardCalculator;
    }

    /**
     * One earner's completed reward assessment: how much was paid out on the earner's own original
     * stake, and which account finally received it (the earner itself, unless the earner was
     * deleted and the payment was redirected along its beneficiary chain). Zero-amount assessments
     * are deliberately representable, because some zero-reward situations must still advance the
     * earner's staking metadata.
     *
     * @param paidAmount the amount actually paid from this assessment (possibly zero)
     * @param finalRecipient the account that received the amount
     */
    public record RewardAssessment(long paidAmount, @NonNull AccountID finalRecipient) {}

    /**
     * The result of one reward-distribution pass, which keeps two deliberately distinct concepts:
     * <ol>
     *   <li>{@link #rewardsPaidByRecipient()} — the tinybar amounts credited to each <b>final
     *   recipient</b>. A deleted account's reward is redirected to (and recorded under) its
     *   beneficiary, so a beneficiary's entry can aggregate its own reward with one or more
     *   redirects. Use this map for balance application, record externalization, and debiting the
     *   funding account.</li>
     *   <li>{@link #assessmentsByEarner()} — keyed by the <b>earner</b>: every account whose own
     *   pending reward was assessed in this pass, mapped to the amount paid from that assessment
     *   and the account that finally received it. Only a self-received assessment (earner equal to
     *   final recipient) may mark the earner as rewarded on its own stake; never use
     *   {@code rewardsPaidByRecipient().keySet()} for {@code stakePeriodStart} or
     *   {@code stakeAtStartOfLastRewardedPeriod} bookkeeping.</li>
     * </ol>
     *
     * @param rewardsPaidByRecipient amounts credited, keyed by final recipient
     * @param assessmentsByEarner completed assessments, keyed by earner
     */
    public record RewardsDistribution(
            @NonNull Map<AccountID, Long> rewardsPaidByRecipient,
            @NonNull Map<AccountID, RewardAssessment> assessmentsByEarner) {}

    /**
     * Pays out rewards to the possible reward receivers by updating the receiver's balance.
     *
     * <p>Processing uses a work queue seeded with the given receivers. When a deleted earner's
     * reward is redirected to a beneficiary whose own pending reward has not yet been assessed,
     * that beneficiary is enqueued for its own assessment (computed from its original state, as
     * usual). This keeps a redirect from silently forfeiting the beneficiary's own accrued reward
     * periods when its stake metadata is advanced afterward. One shared payable-rewards budget and
     * one processed-account set — pre-seeded with the given final recipients already paid by a
     * SCHEDULED child dispatch — span the whole queue, so neither beneficiary chains nor redirects
     * to already-paid recipients can assess an account twice or exceed the funding balance.
     *
     * @param possibleRewardReceivers The accounts that are possible reward receivers, in the order
     * they are to be assessed. Order matters: every assessment draws on one shared budget seeded
     * from the funding account, so when that balance cannot cover all pending rewards, earlier
     * receivers are paid in full and later ones are clamped (possibly to zero). The
     * {@link SequencedSet} type makes this encounter-order contract a compile-time guarantee
     * rather than a property callers happen to satisfy.
     * @param alreadyPaidRecipients Final recipients already paid by a SCHEDULED child dispatch of
     * this transaction, exactly as externalized in that dispatch's record (so keyed by recipient,
     * not by earner). They are never assessed for a reward of their own in this pass, neither as
     * seeded receivers nor when a redirect lands on them (the redirect itself is still credited).
     * Any own pending reward such a recipient had was assessed by that dispatch, which runs this
     * same distribution and enqueues redirect beneficiaries, so re-assessing it here from its
     * still-stale original state would double-pay it.
     * @param fundingAccountId The staking reward funding account
     * @param writableStore The store to update the receiver's balance in.
     * @param stakingRewardsStore The store to update the staking rewards in.
     * @param stakingInfoStore The store to update the staking info in.
     * @param consensusNow The current consensus time.
     * @param recordBuilder The record builder to use for deleted account beneficiaries.
     * @return the distribution, split into recipient-keyed amounts paid (including zero rewards if
     * the calculated reward is zero) and earner-keyed assessments
     */
    public RewardsDistribution payRewardsIfPending(
            @NonNull final SequencedSet<AccountID> possibleRewardReceivers,
            @NonNull final Set<AccountID> alreadyPaidRecipients,
            @NonNull final AccountID fundingAccountId,
            @NonNull final WritableAccountStore writableStore,
            @NonNull final WritableNetworkStakingRewardsStore stakingRewardsStore,
            @NonNull final WritableStakingInfoStore stakingInfoStore,
            @NonNull final Instant consensusNow,
            @NonNull final DeleteCapableTransactionStreamBuilder recordBuilder) {
        requireNonNull(possibleRewardReceivers);
        requireNonNull(alreadyPaidRecipients);

        final Map<AccountID, Long> rewardsPaid = new HashMap<>();
        final Map<AccountID, RewardAssessment> assessmentsByEarner = new LinkedHashMap<>();
        // Seeding from the receiver set in its encounter order (guaranteed by its SequencedSet type)
        // and appending enqueued beneficiaries keeps the processing order -- and with it, who is
        // clamped when the funding balance runs short -- deterministic; the processed set makes
        // each earner's own assessment happen at most once no matter how many redirects land on it
        final Deque<AccountID> workQueue = new ArrayDeque<>(possibleRewardReceivers);
        final Set<AccountID> processed = new HashSet<>(alreadyPaidRecipients);
        long payableRewards = -1;
        while (!workQueue.isEmpty()) {
            final var receiver = workQueue.poll();
            if (!processed.add(receiver)) {
                continue;
            }
            final var originalAccount = writableStore.getOriginalValue(receiver);
            if (originalAccount == null) {
                continue;
            }
            final var modifiedAccount = writableStore.get(receiver);
            long reward = rewardCalculator.computePendingReward(
                    originalAccount, stakingInfoStore, stakingRewardsStore, consensusNow);

            var receiverId = receiver;
            var beneficiary = originalAccount;
            // Only if reward is greater than zero do all operations below.
            // But, even if reward is zero add it to the rewardsPaid map, if the account is not declining reward.
            // It is important to know that if the reward is zero because of its zero stake in last period.
            // This is needed to update stakePeriodStart for the account.
            if (reward > 0) {
                if (payableRewards == -1) {
                    payableRewards =
                            requireNonNull(writableStore.get(fundingAccountId)).tinybarBalance();
                }
                reward = Math.min(reward, payableRewards);
                if (reward > 0) {
                    stakingRewardHelper.decreasePendingRewardsBy(
                            stakingInfoStore, stakingRewardsStore, reward, originalAccount.stakedNodeIdOrThrow());

                    // We cannot reward a deleted account, so keep redirecting to the beneficiaries of deleted
                    // accounts until we find a non-deleted account to try to reward (it may still decline)
                    if (modifiedAccount.deleted()) {
                        final var maxRedirects = recordBuilder.getNumberOfDeletedAccounts();
                        var curRedirects = 1;
                        // Follow the chain to the first account that is not deleted AS OF THIS transaction.
                        // The chain can run through accounts deleted earlier in this same transaction/batch
                        // (e.g. chained CryptoDelete/self-destruct), whose pre-transaction (original) value
                        // still looks live; testing the CURRENT value keeps the redirect from stopping on --
                        // and stranding the reward against -- a just-deleted beneficiary.
                        Account currentBeneficiary;
                        do {
                            if (curRedirects++ > maxRedirects) {
                                log.error(
                                        "With {} accounts deleted, last redirect in modifications led to deleted"
                                                + " beneficiary {}",
                                        maxRedirects,
                                        receiverId);
                                throw new IllegalStateException("Had to redirect reward to a deleted beneficiary");
                            }
                            receiverId = recordBuilder.getDeletedAccountBeneficiaryFor(receiverId);
                            currentBeneficiary = requireNonNull(writableStore.get(receiverId));
                        } while (currentBeneficiary.deleted());
                        // Own-reward eligibility and declineReward are properties of the beneficiary's
                        // original (pre-transaction) state; fall back to the current value only when the
                        // beneficiary was created in this same transaction and so has no original (which
                        // also means it cannot have a pending reward of its own).
                        final var originalBeneficiary = writableStore.getOriginalValue(receiverId);
                        beneficiary = originalBeneficiary != null ? originalBeneficiary : currentBeneficiary;
                        // The beneficiary receiving this redirect may have its own pending reward, even
                        // though it was never itself modified (e.g. the deleted earner was drained first, so
                        // the delete transferred zero to it). Enqueue it for its own assessment so the
                        // redirect cannot forfeit its accrued periods; only an account that existed before
                        // this transaction and stakes to a node can have a pending reward of its own. If it
                        // was already processed (assessed earlier in this queue, queued as a natural
                        // receiver, or prepaid by a SCHEDULED child dispatch), the processed set guarantees a
                        // single assessment.
                        if (!processed.contains(receiverId)
                                && originalBeneficiary != null
                                && originalBeneficiary.stakedNodeIdOrElse(SENTINEL_NODE_ID) != SENTINEL_NODE_ID) {
                            workQueue.add(receiverId);
                        }
                    }
                }
            }
            // Even if the account has declineReward set or if reward is 0, it should still
            // be added to the rewardsPaid map because ONLY this map's keys have their
            // stakeAtStartOfLastRewardedPeriod value updated; and for some reward=0
            // situations it is critical to update stakeAtStartOfLastRewardedPeriod
            final var mutableBeneficiary = writableStore.get(beneficiary.accountId());
            // If the beneficiary has declineReward set, then the reward is zero
            final var finalReward = beneficiary.declineReward() ? 0 : reward;
            // even if reward is zero it will be added to rewardsPaid
            if (finalReward > 0) {
                applyReward(finalReward, mutableBeneficiary, writableStore);
                // Ensure we never pay more than the available balance
                payableRewards -= finalReward;
            }
            rewardsPaid.merge(receiverId, finalReward, Long::sum);
            // Record the completed assessment under the EARNER, preserving both how much was paid
            // from it and which account finally received it. A deleted earner's assessment was
            // redirected away (its final recipient differs from the earner), so it must not mark
            // anyone as rewarded on their own stake.
            assessmentsByEarner.put(receiver, new RewardAssessment(finalReward, receiverId));
        }
        return new RewardsDistribution(rewardsPaid, assessmentsByEarner);
    }

    /**
     * Applies the reward to the receiver. This is done by updating the receiver's balance.
     * @param reward The reward to apply.
     * @param receiver The account that will receive the reward.
     * @param writableStore The store to update the receiver's balance in.
     */
    private void applyReward(final long reward, final Account receiver, final WritableAccountStore writableStore) {
        final var finalBalance = receiver.tinybarBalance() + reward;
        final var copy = receiver.copyBuilder();
        copy.tinybarBalance(finalBalance);
        writableStore.put(copy.build());
    }
}
