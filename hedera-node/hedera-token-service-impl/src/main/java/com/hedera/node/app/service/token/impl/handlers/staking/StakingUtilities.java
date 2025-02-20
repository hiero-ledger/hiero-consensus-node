// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Utility class for staking.
 */
public class StakingUtilities {
    private StakingUtilities() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * The default value to represent that the account has not been rewarded since the last staking metadata change.
     */
    public static final long NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE = -1;
    /**
     * The default value to represent that the account has no stake period start.
     */
    public static final long NO_STAKE_PERIOD_START = -1;

    /**
     * Rounds the value to the nearest hbar.
     * @param value the value to round
     * @return the value rounded to the nearest hbar
     */
    public static long roundedToHbar(final long value) {
        return (value / HBARS_TO_TINYBARS) * HBARS_TO_TINYBARS;
    }

    /**
     * Calculates the total stake of the account. The total stake is the sum of the tinybars balance and the amount
     * staked to the account.
     * @param account the account
     * @return the total stake of the account
     */
    public static long totalStake(@NonNull Account account) {
        return account.tinybarBalance() + account.stakedToMe();
    }

    /**
     * Checks if the account has any changes that modified the staking metadata,
     * which is stakedId or declineReward field.
     * @param originalAccount the original account before the transaction
     * @param modifiedAccount the modified account after the transaction
     * @return true if the account has any changes that modified the staking metadata, false otherwise
     */
    public static boolean hasStakeMetaChanges(
            @Nullable final Account originalAccount, @NonNull final Account modifiedAccount) {
        if (originalAccount == null) {
            return modifiedAccount.hasStakedNodeId()
                    || modifiedAccount.hasStakedAccountId()
                    || modifiedAccount.declineReward();
        }
        final var differDeclineReward = originalAccount.declineReward() != modifiedAccount.declineReward();
        final var differStakedNodeId = !originalAccount
                .stakedNodeIdOrElse(SENTINEL_NODE_ID)
                .equals(modifiedAccount.stakedNodeIdOrElse(SENTINEL_NODE_ID));
        final var differStakeAccountId =
                !effectiveStakedAccountId(originalAccount).equals(effectiveStakedAccountId(modifiedAccount));
        return differDeclineReward || differStakedNodeId || differStakeAccountId;
    }

    /**
     * Returns the account id the given account is staked to, or {@link AccountID#DEFAULT} if it is
     * not staked to a node. Notice that {@link Account#stakedAccountIdOrElse(AccountID)} doesn't
     * work directly, because this will be explicitly set to {@code null} if a staked account id
     * was removed via the {@code 0.0.0} sentinel.
     *
     * <p>Will be unnecessary once https://github.com/hashgraph/pbj/issues/160 is done.
     *
     * @param account the account
     * @return the account id the given account is staked to, or {@link AccountID#DEFAULT}
     */
    private static AccountID effectiveStakedAccountId(@NonNull final Account account) {
        if (account.hasStakedAccountId()) {
            return account.stakedAccountId() == null ? AccountID.DEFAULT : account.stakedAccountId();
        } else {
            return AccountID.DEFAULT;
        }
    }
}
