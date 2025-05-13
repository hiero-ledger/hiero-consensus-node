// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
     * Pays out rewards to the possible reward receivers by updating the receiver's balance.
     *
     * @param possibleRewardReceivers The accounts that are possible reward receivers.
     * @param fundingAccountId The staking reward funding account
     * @param writableStore The store to update the receiver's balance in.
     * @param stakingRewardsStore The store to update the staking rewards in.
     * @param stakingInfoStore The store to update the staking info in.
     * @param consensusNow The current consensus time.
     * @param recordBuilder The record builder to use for deleted account beneficiaries.
     * @return The map of rewards paid to each receiver, which includes zero rewards if the calculated reward is zero
     */
    public Map<AccountID, Long> payRewardsIfPending(
            @NonNull final Set<AccountID> possibleRewardReceivers,
            @NonNull final AccountID fundingAccountId,
            @NonNull final WritableAccountStore writableStore,
            @NonNull final WritableNetworkStakingRewardsStore stakingRewardsStore,
            @NonNull final WritableStakingInfoStore stakingInfoStore,
            @NonNull final Instant consensusNow,
            @NonNull final DeleteCapableTransactionStreamBuilder recordBuilder) {
        requireNonNull(possibleRewardReceivers);

        final Map<AccountID, Long> rewardsPaid = new HashMap<>();
        long payableRewards = -1;
        for (final var receiver : possibleRewardReceivers) {
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
                            beneficiary = writableStore.getOriginalValue(receiverId);
                        } while (beneficiary.deleted());
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
        }
        return rewardsPaid;
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
