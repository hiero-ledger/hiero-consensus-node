// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAlias;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.THREE_MONTHS_IN_SECONDS;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Functionality needed for auto-creating accounts when a CryptoTransfer transaction sends hbar or tokens to an
 * alias that does not yet have an account.
 */
public class AutoAccountCreator {
    private WritableAccountStore accountStore;
    private HandleContext handleContext;

    /**
     * Constructs an {@link AutoAccountCreator} with the given {@link HandleContext}.
     * @param handleContext the context to use for the creation
     */
    public AutoAccountCreator(@NonNull final HandleContext handleContext) {
        this.handleContext = requireNonNull(handleContext);
        this.accountStore = handleContext.storeFactory().writableStore(WritableAccountStore.class);
    }

    /**
     * Creates an account for the given alias.
     *
     * @param alias                      the alias to create the account for
     * @param requiredAutoAssociations   the requiredAutoAssociations to set on the account
     * @return the account ID of the created account
     */
    public AccountID create(@NonNull final Bytes alias, int requiredAutoAssociations) {
        requireNonNull(alias);

        final var accountsConfig = handleContext.configuration().getConfigData(AccountsConfig.class);

        validateTrue(
                accountStore.sizeOfAccountState() + 1 <= accountsConfig.maxNumber(),
                ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        final TransactionBody.Builder syntheticCreation;

        final var isAliasEVMAddress = isOfEvmAddressSize(alias);
        final var entitiesConfig = handleContext.configuration().getConfigData(EntitiesConfig.class);
        final int autoAssociations = entitiesConfig.unlimitedAutoAssociationsEnabled()
                ? UNLIMITED_AUTOMATIC_ASSOCIATIONS
                : requiredAutoAssociations;
        if (isAliasEVMAddress) {
            syntheticCreation = createHollowAccount(alias, 0L, autoAssociations);
        } else {
            final var key = asKeyFromAlias(alias);
            syntheticCreation = createZeroBalanceAccount(alias, key, autoAssociations);
        }

        // Dispatch the auto-creation record as a preceding record; note we pass null for the
        // "verification assistant" since we have no non-payer signatures to verify here
        final var streamBuilder = handleContext.dispatch(setupDispatch(
                handleContext.payer(),
                syntheticCreation.build(),
                CryptoCreateStreamBuilder.class,
                handleContext
                        .dispatchMetadata()
                        .getMetadata(CUSTOM_FEE_CHARGING, FeeCharging.class)
                        .orElse(null)));
        // If the child transaction failed, we should fail the parent transaction as well and propagate the failure.
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());

        final var config = handleContext.configuration().getConfigData(HederaConfig.class);
        // Since we succeeded, we can now look up the account ID of the created account. This really should always
        // work, since the child transaction succeeded. If it did not work for some reason, we have a bug in our
        // code (rather than a bad transaction), so we will fail with FAIL_INVALID.
        final var createdAccountId = accountStore.getAccountIDByAlias(config.shard(), config.realm(), alias);
        validateTrue(createdAccountId != null, FAIL_INVALID);
        return createdAccountId;
    }

    /**
     * Create a transaction body for new hollow-account with the given alias.
     *
     * @param alias alias of the account
     * @param balance initial balance of the account
     * @param maxAutoAssociations maxAutoAssociations of the account
     * @return transaction body for new hollow-account
     */
    public TransactionBody.Builder createHollowAccount(
            @NonNull final Bytes alias, final long balance, final int maxAutoAssociations) {
        requireNonNull(alias);
        final var baseBuilder = createAccountBase(balance, maxAutoAssociations);
        baseBuilder.key(IMMUTABILITY_SENTINEL_KEY).alias(alias);
        return TransactionBody.newBuilder().cryptoCreateAccount(baseBuilder.build());
    }

    /**
     * Create a transaction body for new account with the given balance and other common fields.
     * @param balance initial balance of the account
     * @param maxAutoAssociations maxAutoAssociations of the account
     * @return transaction body for new account
     */
    private CryptoCreateTransactionBody.Builder createAccountBase(final long balance, final int maxAutoAssociations) {
        return CryptoCreateTransactionBody.newBuilder()
                .initialBalance(balance)
                .maxAutomaticTokenAssociations(maxAutoAssociations)
                .autoRenewPeriod(Duration.newBuilder().seconds(THREE_MONTHS_IN_SECONDS));
    }

    /**
     * Create a transaction body for new account with the given alias, key, balance and maxAutoAssociations.
     *
     * @param alias alias of the account
     * @param key key of the account
     * @param maxAutoAssociations maxAutoAssociations of the account
     * @return transaction body for new account
     */
    private TransactionBody.Builder createZeroBalanceAccount(
            @NonNull final Bytes alias, @NonNull final Key key, final int maxAutoAssociations) {
        final var baseBuilder = createAccountBase(0L, maxAutoAssociations);
        baseBuilder.key(key).alias(alias).receiverSigRequired(false);
        return TransactionBody.newBuilder().cryptoCreateAccount(baseBuilder.build());
    }
}
