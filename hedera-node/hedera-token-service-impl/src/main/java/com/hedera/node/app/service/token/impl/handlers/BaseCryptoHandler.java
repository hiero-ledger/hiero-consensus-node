// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * This class contains common functionality needed for crypto handlers.
 */
public class BaseCryptoHandler {
    private static final long MAX_UPDATE_BYTES_LEN = 32L;

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
     * Dispatches the hook creation to the given context.
     * @param context the handle context
     * @param creation the hook creation to dispatch
     */
    protected void dispatchCreation(final @NonNull HandleContext context, final HookCreation creation) {
        final var hookDispatch =
                HookDispatchTransactionBody.newBuilder().creation(creation).build();
        final var streamBuilder = context.dispatch(setupDispatch(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                StreamBuilder.class,
                context.dispatchMetadata()
                        .getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class)
                        .orElse(null)));
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
    }

    /**
     * Validates that the given bytes are a valid "word" (i.e. a 32-byte value) for use in a lambda storage update.
     * Specifically, it checks that the length is at most 32 bytes, and that it is in its minimal representation
     * (i.e. no leading zeros).
     * @param bytes the bytes to validate
     * @throws PreCheckException if the bytes are not a valid word
     */
    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, HOOK_CREATION_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes.equals(minimalBytes), HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }
}
