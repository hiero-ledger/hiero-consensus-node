// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.AccountsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class contains common functionality needed for crypto handlers.
 */
public class BaseCryptoHandler {
    /**
     * Gets the accountId from the account number provided.
     * @param shard the account shard
     * @param realm the account realm
     * @param num the account number
     * @return the accountID
     */
    @NonNull
    public static AccountID asAccount(final long shard, final long realm, final long num) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(num)
                .build();
    }

    /**
     * Checks that an accountId represents one of the staking accounts.
     * @param configuration the configuration
     * @param accountID    the accountID to check
     * @return {@code true} if the accountID represents one of the staking accounts, {@code false} otherwise
     */
    public static boolean isStakingAccount(
            @NonNull final Configuration configuration, @Nullable final AccountID accountID) {
        final var accountNum = accountID != null ? accountID.accountNum() : 0;
        final var accountsConfig = configuration.getConfigData(AccountsConfig.class);
        return accountNum == accountsConfig.stakingRewardAccount() || accountNum == accountsConfig.nodeRewardAccount();
    }

    /**
     * Checks if the accountID has an account number or alias.
     * @param accountID   the accountID to check
     * @return {@code true} if the accountID has an account number or alias, {@code false} otherwise
     */
    public static boolean hasAccountNumOrAlias(@Nullable final AccountID accountID) {
        return accountID != null
                && ((accountID.hasAccountNum() && accountID.accountNumOrThrow() != 0L)
                        || (accountID.hasAlias() && accountID.aliasOrThrow().length() > 0));
    }

    /**
     * Validates the hook creation details list, if there are any duplicate hook IDs.
     * @param details the list of hook creation details
     * @throws PreCheckException if there are duplicate hook IDs
     */
    protected void validateHookDuplicates(final List<HookCreationDetails> details) throws PreCheckException {
        if (!details.isEmpty()) {
            final var hookIds =
                    details.stream().map(HookCreationDetails::hookId).collect(Collectors.toSet());
            if (hookIds.size() != details.size()) {
                throw new PreCheckException(HOOK_ID_REPEATED_IN_CREATION_DETAILS);
            }
        }
    }
}
