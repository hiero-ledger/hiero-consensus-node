// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hedera.node.app.hapi.fees.pricing.BaseOperationUsage.THREE_MONTHS_IN_SECONDS;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER;
import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_ACCOUNT_ID;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static com.hedera.node.app.spi.validation.AttributeValidator.isImmutableKey;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.CryptoSignatureWaivers;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.service.token.records.CryptoUpdateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.AutoRenewConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CRYPTO_UPDATE}.
 */
@Singleton
public class CryptoUpdateHandler extends BaseCryptoHandler implements TransactionHandler {
    private final CryptoSignatureWaivers waivers;

    /**
     * Default constructor for injection.
     * @param waivers the {@link CryptoSignatureWaivers} to use for checking signature waivers
     */
    @Inject
    public CryptoUpdateHandler(@NonNull final CryptoSignatureWaivers waivers) {
        this.waivers = requireNonNull(waivers, "The supplied argument 'waivers' must not be null");
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoUpdateAccountOrThrow();
        validateTruePreCheck(op.hasAccountIDToUpdate(), ACCOUNT_ID_DOES_NOT_EXIST);
        validateFalsePreCheck(
                op.hasProxyAccountID() && !op.proxyAccountID().equals(AccountID.DEFAULT),
                PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        requireNonNull(waivers);
        final var txn = context.body();

        final var payer = context.payer();
        final var op = txn.cryptoUpdateAccountOrThrow();

        // update account must exist. Validated in pureChecks
        final var updateAccountId = op.accountIDToUpdateOrThrow();

        // 1. When updating the treasury account 0.0.2 key, the target account must also sign the transaction
        // 2. When updating treasury account 0.0.2, even if system admin is the payer for the transaction,
        // the target account must sign the transaction
        // 3. If the payer for the transaction is 0.0.2 or system admin, then no signatures are needed for the update
        final var targetAccountKeyMustSign = !waivers.isTargetAccountSignatureWaived(txn, payer);

        // 4. Including above 3 conditions, if the target account is 0.0.2, new key must sign the transaction
        final var newAccountKeyMustSign = !waivers.isNewKeySignatureWaived(txn, payer);
        if (targetAccountKeyMustSign) {
            context.requireKeyOrThrow(updateAccountId, INVALID_ACCOUNT_ID);
        }
        if (newAccountKeyMustSign && op.hasKey()) {
            context.requireKeyOrThrow(op.key(), INVALID_ADMIN_KEY);
        }
    }

    /**
     * This method is called during the handle workflow. It executes the actual transaction.
     * @param context the {@link HandleContext} which collects all information
     * @throws HandleException if any of the checks fail
     * @throws NullPointerException if any of the arguments are null
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        final var txn = requireNonNull(context).body();
        final var op = txn.cryptoUpdateAccountOrThrow();
        final var target = op.accountIDToUpdateOrThrow();

        // validate update account exists
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var targetAccount =
                TokenHandlerHelper.getIfUsable(target, accountStore, context.expiryValidator(), INVALID_ACCOUNT_ID);
        context.attributeValidator().validateMemo(op.memo());

        // Customize the account based on fields set in transaction body
        final var builder = updateBuilder(op, targetAccount);

        // validate all checks that involve config and state
        validateSemantics(context, targetAccount, op, builder, accountStore);

        // If an account is detached and expired, validateSemantics would have thrown an exception
        // To reach here, this update transaction has extended the expiration of the account.
        // So, we need to set the expiredAndPendingRemoval flag to false
        if (targetAccount.expiredAndPendingRemoval()) {
            builder.expiredAndPendingRemoval(false);
        }

        // Add account to the modifications in state
        accountStore.put(builder.build());
        context.savepointStack()
                .getBaseBuilder(CryptoUpdateStreamBuilder.class)
                .accountID(targetAccount.accountIdOrThrow());
    }

    /**
     * Add a builder from {@link CryptoUpdateTransactionBody} to create {@link Account.Builder} object.
     * @param op Crypto update transaction body
     * @return builder
     */
    private Account.Builder updateBuilder(
            @NonNull final CryptoUpdateTransactionBody op, @NonNull final Account currentAccount) {
        final var builder = currentAccount.copyBuilder();
        if (op.hasKey()) {
            /* Note that {@code this.validateSemantics} will have rejected any txn with an invalid key. */
            builder.key(op.key());
        }
        if (op.hasExpirationTime()) {
            builder.expirationSecond(op.expirationTime().seconds());
        }
        if (op.hasReceiverSigRequiredWrapper()) {
            builder.receiverSigRequired(op.receiverSigRequiredWrapper().booleanValue());
        } else if (op.hasReceiverSigRequired()) {
            builder.receiverSigRequired(op.receiverSigRequired());
        }
        if (op.hasAutoRenewPeriod()) {
            builder.autoRenewSeconds(op.autoRenewPeriod().seconds());
        }
        if (op.hasMemo()) {
            builder.memo(op.memo());
        }
        if (op.hasMaxAutomaticTokenAssociations()) {
            builder.maxAutoAssociations(op.maxAutomaticTokenAssociations());
        }
        if (op.hasDeclineReward()) {
            builder.declineReward(op.declineReward().booleanValue());
        }
        if (op.hasStakedAccountId()) {
            // 0.0.0 is used a sentinel value for removing staked account id
            // Once https://github.com/hashgraph/pbj/issues/160 this is closed, reset stakedId to UNSET
            if (SENTINEL_ACCOUNT_ID.equals(op.stakedAccountId())) {
                builder.stakedAccountId((AccountID) null);
            } else {
                builder.stakedAccountId(op.stakedAccountId());
            }
        } else if (op.hasStakedNodeId()) {
            // -1 is used a sentinel value for removing staked node id
            // Once https://github.com/hashgraph/pbj/issues/160 this is closed, reset stakedId to UNSET
            if (SENTINEL_NODE_ID == op.stakedNodeId()) {
                builder.stakedNodeId(SENTINEL_NODE_ID);
            } else {
                builder.stakedNodeId(op.stakedNodeId());
            }
        }
        return builder;
    }

    /**
     * Validate semantics of the transaction. This method is called during the handle workflow.
     * It validates any fields of the transaction that involves state or config.
     * @param context handle context
     * @param updateAccount account to be updated
     * @param op crypto update transaction body
     * @param builder account update builder
     * @param accountStore account store
     */
    private void validateSemantics(
            @NonNull final HandleContext context,
            @NonNull final Account updateAccount,
            @NonNull final CryptoUpdateTransactionBody op,
            @NonNull Account.Builder builder,
            @NonNull final ReadableAccountStore accountStore) {
        final var expiryValidator = context.expiryValidator();
        final var builderAccount = builder.build();
        validateFields(op, context, accountStore);

        validateTrue(!updateAccount.smartContract(), INVALID_ACCOUNT_ID);

        // get needed configs for validation
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);

        // validate expiry metadata
        final var currentMetadata = new ExpiryMeta(
                updateAccount.expirationSecond(), updateAccount.autoRenewSeconds(), updateAccount.autoRenewAccountId());
        final var updateMeta = new ExpiryMeta(
                op.hasExpirationTime() ? op.expirationTime().seconds() : NA,
                op.hasAutoRenewPeriod() ? op.autoRenewPeriod().seconds() : NA,
                null);
        context.expiryValidator().resolveUpdateAttempt(currentMetadata, updateMeta);

        // If an account is detached and pending removal, it cannot be updated
        // It can only be updated to extend expiration time
        if (expiryValidator.isDetached(
                EntityType.ACCOUNT, updateAccount.expiredAndPendingRemoval(), updateAccount.tinybarBalance())) {
            validateTrue(builderAccount.expirationSecond() != 0, ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
        }

        // validate auto associations
        if (op.hasMaxAutomaticTokenAssociations()) {
            final long newMax = builderAccount.maxAutoAssociations();
            // first validate if the associations are limited and the new max is within
            validateFalse(
                    entitiesConfig.limitTokenAssociations() && newMax > tokensConfig.maxPerAccount(),
                    REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT);
            validateFalse(
                    newMax < -1 && entitiesConfig.unlimitedAutoAssociationsEnabled()
                            || newMax < 0 && !entitiesConfig.unlimitedAutoAssociationsEnabled(),
                    INVALID_MAX_AUTO_ASSOCIATIONS);
            validateFalse(
                    newMax < updateAccount.usedAutoAssociations() && newMax != -1,
                    EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT);
            validateFalse(
                    newMax > ledgerConfig.maxAutoAssociations(),
                    REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT);
        }

        // validate if account is not deleted
        validateFalse(updateAccount.deleted(), ACCOUNT_DELETED);
    }

    /**
     * Validate basic fields of the transaction that involves state or config.
     * @param op crypto update transaction body
     * @param context handle context
     * @param accountStore account store
     */
    private void validateFields(
            @NonNull final CryptoUpdateTransactionBody op,
            @NonNull final HandleContext context,
            @NonNull final ReadableAccountStore accountStore) {
        if (op.hasMemo()) {
            context.attributeValidator().validateMemo(op.memo());
        }
        // Empty key list is allowed and is used for immutable entities (e.g. system accounts)
        if (op.hasKey() && !isImmutableKey(op.key())) {
            context.attributeValidator().validateKey(op.key());
        }

        if (op.hasAutoRenewPeriod()) {
            context.attributeValidator()
                    .validateAutoRenewPeriod(op.autoRenewPeriod().seconds());
        }

        StakingValidator.validateStakedIdForUpdate(
                context.configuration().getConfigData(StakingConfig.class).isEnabled(),
                op.hasDeclineReward(),
                op.stakedId().kind().name(),
                op.stakedAccountId(),
                op.stakedNodeId(),
                accountStore,
                context.networkInfo());
    }

    /**
     * This method calculates the fees for the CryptoUpdate transaction.
     * Currently, it just duplicates all the logic from mono-service
     * @param feeContext the {@link FeeContext} with all information needed for the calculation
     * @return the calculated fees
     */
    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        // Variable bytes plus two additional longs for balance and auto-renew period; plus a boolean for receiver sig
        // required.
        final var body = feeContext.body();
        final var accountStore = feeContext.readableStore(ReadableAccountStore.class);
        return cryptoUpdateFees(
                body,
                feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT),
                accountStore,
                feeContext.configuration());
    }

    /**
     * This method calculates the base size of the cryptoUpdate transaction.
     * This is the duplicated code as in mono-service
     * @param txBody the {@link CryptoUpdateTransactionBody}
     * @param keySize the size of the key
     * @return the calculated base size
     */
    private static long baseSizeOf(final CryptoUpdateTransactionBody txBody, final long keySize) {
        return BASIC_ENTITY_ID_SIZE
                + txBody.memoOrElse("").getBytes(StandardCharsets.UTF_8).length
                + (txBody.hasExpirationTime() ? LONG_SIZE : 0L)
                + (txBody.hasAutoRenewPeriod() ? LONG_SIZE : 0L)
                + (txBody.hasProxyAccountID() ? BASIC_ENTITY_ID_SIZE : 0L)
                + (txBody.hasMaxAutomaticTokenAssociations() ? INT_SIZE : 0L)
                + keySize;
    }

    /**
     * This method calculates the bytes for the CryptoUpdate transaction auto-renew information.
     * This is the duplicated code as in mono-service
     * @param account the {@link Account} to be updated
     * @return the calculated bytes
     */
    private static long cryptoAutoRenewRb(@Nullable final Account account) {
        final var fixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr();
        if (account == null) {
            return fixedBytes;
        }
        return fixedBytes
                + currentNonBaseBytes(account)
                + (account.numberAssociations() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr());
    }

    /**
     * This method calculates the bytes for the CryptoUpdate transaction related to memo and keys.
     * This is the duplicated code as in mono-service
     * @param account the {@link Account} to be updated
     * @return the calculated bytes
     */
    private static int currentNonBaseBytes(final Account account) {
        // TODO: should this part be a new utility method so we don't repeat it over and over?
        final var accountMemoSize = (account == null || account.memo() == null)
                ? 0
                : account.memo().getBytes(StandardCharsets.UTF_8).length;

        return accountMemoSize
                + getAccountKeyStorageSize(CommonPbjConverters.fromPbj(account.keyOrElse(Key.DEFAULT)))
                + (account.maxAutoAssociations() == 0 ? 0 : INT_SIZE);
    }

    /**
     * This method calculates the fees for the CryptoUpdate transaction.
     * This can also be used for lazy account creation logic in AutoAccountCreator class in future PRs
     * @param body the {@link TransactionBody}
     * @param feeCalculator the {@link FeeCalculator}
     * @param accountStore the {@link ReadableAccountStore}
     * @param configuration the {@link Configuration}
     * @return the calculated fees
     */
    private Fees cryptoUpdateFees(
            final TransactionBody body,
            final FeeCalculator feeCalculator,
            final ReadableAccountStore accountStore,
            final Configuration configuration) {
        final var op = body.cryptoUpdateAccountOrThrow();
        // When dispatching transaction body for hollow account we don't have update account set
        final var account = accountStore.getAccountById(op.accountIDToUpdateOrElse(AccountID.DEFAULT));
        final var autoRenewconfig = configuration.getConfigData(AutoRenewConfig.class);
        final var entityConfig = configuration.getConfigData(EntitiesConfig.class);
        final var unlimitedAutoAssoc = entityConfig.unlimitedAutoAssociationsEnabled();
        final var explicitAutoAssocSlotLifetime = autoRenewconfig.expireAccounts() ? 0L : THREE_MONTHS_IN_SECONDS;

        final var keySize = op.hasKey() ? getAccountKeyStorageSize(CommonPbjConverters.fromPbj(op.keyOrThrow())) : 0L;
        final var baseSize = baseSizeOf(op, keySize);
        final var newMemoSize = op.memoOrElse("").getBytes(StandardCharsets.UTF_8).length;

        final var accountMemoSize = (account == null || account.memo() == null)
                ? 0L
                : account.memo().getBytes(StandardCharsets.UTF_8).length;
        final long newVariableBytes = (newMemoSize != 0L ? newMemoSize : accountMemoSize)
                + (keySize == 0L && account != null
                        ? getAccountKeyStorageSize(CommonPbjConverters.fromPbj(account.keyOrElse(Key.DEFAULT)))
                        : keySize);

        final long tokenRelBytes =
                (account == null ? 0 : account.numberAssociations()) * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
        final long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
        final var effectiveNow =
                body.transactionIDOrThrow().transactionValidStartOrThrow().seconds();
        final long newLifetime = ESTIMATOR_UTILS.relativeLifetime(
                effectiveNow, op.expirationTimeOrElse(Timestamp.DEFAULT).seconds());
        final long oldLifetime =
                ESTIMATOR_UTILS.relativeLifetime(effectiveNow, (account == null ? 0 : account.expirationSecond()));
        final long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                cryptoAutoRenewRb(account), oldLifetime, sharedFixedBytes + newVariableBytes, newLifetime);

        final var oldSlotsUsage = (account == null ? 0 : account.maxAutoAssociations()) * UPDATE_SLOT_MULTIPLIER;
        final var newSlotsUsage = op.hasMaxAutomaticTokenAssociations() && !unlimitedAutoAssoc
                ? op.maxAutomaticTokenAssociations().longValue() * UPDATE_SLOT_MULTIPLIER
                : oldSlotsUsage;
        // If given an explicit auto-assoc slot lifetime, we use it as a lower bound for both old and new lifetimes
        final long slotRbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                oldSlotsUsage,
                Math.max(explicitAutoAssocSlotLifetime, oldLifetime),
                newSlotsUsage,
                Math.max(explicitAutoAssocSlotLifetime, newLifetime));
        return feeCalculator
                .addBytesPerTransaction(baseSize)
                .addRamByteSeconds(rbsDelta > 0 ? rbsDelta : 0)
                .addRamByteSeconds(slotRbsDelta > 0 ? slotRbsDelta : 0)
                .calculate();
    }
}
