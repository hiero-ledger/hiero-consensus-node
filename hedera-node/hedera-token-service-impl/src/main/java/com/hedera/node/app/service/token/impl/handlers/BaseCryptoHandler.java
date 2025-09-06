// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_EXTENSION_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_IS_NOT_A_LAMBDA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

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

    protected void validateHookPureChecks(final List<HookCreationDetails> details) throws PreCheckException {
        final var hookIdsSeen = new HashSet<Long>();
        for (final var hook : details) {
            validateTruePreCheck(hook.hookId() != 0L, INVALID_HOOK_ID);
            // No duplicate hook ids are allowed inside one txn
            validateTruePreCheck(hookIdsSeen.add(hook.hookId()), HOOK_ID_REPEATED_IN_CREATION_DETAILS);
            validateTruePreCheck(hook.extensionPoint() != null, HOOK_EXTENSION_EMPTY);
            validateTruePreCheck(hook.hasLambdaEvmHook(), HOOK_IS_NOT_A_LAMBDA);

            final var lambda = hook.lambdaEvmHookOrThrow();
            validateTruePreCheck(lambda.hasSpec() && lambda.specOrThrow().hasContractId(), INVALID_HOOK_CREATION_SPEC);

            for (final var storage : lambda.storageUpdates()) {
                validateTruePreCheck(
                        storage.hasStorageSlot() || storage.hasMappingEntries(), EMPTY_LAMBDA_STORAGE_UPDATE);

                if (storage.hasStorageSlot()) {
                    final var s = storage.storageSlotOrThrow();
                    // The key for a storage slot can be empty. If present, it should have minimal encoding and maximum
                    // 32 bytes
                    validateWord(s.key());
                    validateWord(s.value());
                } else if (storage.hasMappingEntries()) {
                    final var mapping = storage.mappingEntriesOrThrow();
                    for (final var e : mapping.entries()) {
                        validateTruePreCheck(e.hasKey() || e.hasPreimage(), EMPTY_LAMBDA_STORAGE_UPDATE);
                        if (e.hasKey()) {
                            validateWord(e.keyOrThrow());
                        }
                    }
                }
            }
        }
    }

    protected HookSummary summarizeHooks(final List<HookCreationDetails> details) {
        if (details.isEmpty()) {
            return new CryptoCreateHandler.HookSummary(0L, Collections.emptyList());
        }
        // get the first id from the stream, without any sorting
        final long firstId = details.getFirst().hookId();
        long slots = 0L;
        for (final var detail : details) {
            if (detail.hasLambdaEvmHook()) {
                // count only non-empty values as consuming a slot
                for (final var u : detail.lambdaEvmHookOrThrow().storageUpdates()) {
                    if (u.hasStorageSlot()) {
                        if (u.storageSlotOrThrow().value() != null
                                && u.storageSlotOrThrow().value().length() > 0) {
                            slots++;
                        }
                    } else {
                        for (final var entry : u.mappingEntriesOrThrow().entries()) {
                            if (entry.value() != null && entry.value().length() > 0) {
                                slots++;
                            }
                        }
                    }
                }
            }
        }
        return new CryptoCreateHandler.HookSummary(
                slots, details.stream().map(HookCreationDetails::hookId).toList());
    }

    protected void dispatchCreation(final @NonNull HandleContext context, final HookCreation creation) {
        final var hookDispatch = HookDispatchTransactionBody.newBuilder()
                .creation(creation)
                .build();
        final var streamBuilder = context.dispatch(setupDispatch(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                StreamBuilder.class,
                context.dispatchMetadata()
                        .getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class)
                        .orElse(null)));
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
    }

    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, HOOK_CREATION_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes == minimalBytes, HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }

    protected record HookSummary(long initialLambdaSlots, List<Long> creationHookIds) {}
}
