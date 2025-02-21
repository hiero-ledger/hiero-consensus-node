// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.records.FinalizeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Set;

/**
 * On each transaction, before finalizing the state to a transaction record, goes through all the modified accounts
 * and pays out the staking rewards if any.
 * Staking rewards are assessed when an account that is staked to a node or if the account is staked to another
 * account that is staked to a node, has one of following conditions met:
 * 1. The account's balance changes
 * 2. The account's stakedId changes
 * 3. The account's declineReward field changes
 * 4. If stakedToMe field of the node to which the account is staked changes
 *
 */
public interface StakingRewardsHandler {
    /**
     * Goes through all the modified accounts and pays out the staking rewards if any and returns the map of account id
     * to the amount of rewards paid out.
     *
     * <p>For mono-service fidelity, also supports taking an extra set of accounts
     * to explicitly consider for staking rewards, even if they do not appear to be
     * in a reward situation. This is needed to trigger rewards for accounts that
     * are listed in the HBAR adjustments of a {@code CryptoTransfer}; but with a
     * zero adjustment amount.
     *
     * @param context the context of the transaction
     * @param explicitRewardReceivers a set of accounts that must be considered for rewards independent of the context
     * @param prePaidRewards a set of accounts that have already been paid rewards in the current transaction
     * @return a map of account id to the amount of rewards paid out
     */
    Map<AccountID, Long> applyStakingRewards(
            FinalizeContext context,
            @NonNull Set<AccountID> explicitRewardReceivers,
            @NonNull Map<AccountID, Long> prePaidRewards);

    /**
     * Checks if the account has been rewarded since the last staking metadata change.
     * @param account the account to check
     * @return true if the account has been rewarded since the last staking metadata change, false otherwise
     */
    default boolean isRewardedSinceLastStakeMetaChange(Account account) {
        return account.stakeAtStartOfLastRewardedPeriod() != NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE;
    }
}
