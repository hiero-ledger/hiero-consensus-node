// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ALIAS_ALREADY_ASSIGNED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_INITIAL_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVE_RECORD_THRESHOLD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SEND_RECORD_THRESHOLD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_NOT_PROVIDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.CREATE_SLOT_MULTIPLIER;
import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAlias;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAliasPreCheck;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.isEntityNumAlias;
import static com.hedera.node.app.service.token.AliasUtils.isKeyAlias;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.NO_STAKE_PERIOD_START;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CRYPTO_CREATE}. A
 * "crypto create" is the creation of a new account on the Hedera network.
 */
@Singleton
public class CryptoCreateHandler extends BaseCryptoHandler implements TransactionHandler {
    private static final TransactionBody UPDATE_TXN_BODY_BUILDER = TransactionBody.newBuilder()
            .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                    .key(Key.newBuilder().ecdsaSecp256k1(Bytes.EMPTY).build()))
            .build();

    private final CryptoCreateValidator cryptoCreateValidator;

    @Nullable
    private final AtomicBoolean systemEntitiesCreatedFlag;

    /**
     * Constructs a {@link CryptoCreateHandler} with the given {@link CryptoCreateValidator} and {@link StakingValidator}.
     * @param cryptoCreateValidator the validator for the crypto create transaction
     */
    @Inject
    public CryptoCreateHandler(
            @NonNull final CryptoCreateValidator cryptoCreateValidator,
            @Nullable final AtomicBoolean systemEntitiesCreatedFlag) {
        this.cryptoCreateValidator =
                requireNonNull(cryptoCreateValidator, "The supplied argument 'cryptoCreateValidator' must not be null");
        this.systemEntitiesCreatedFlag = systemEntitiesCreatedFlag;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoCreateAccountOrThrow();
        // Note: validation lives here for now but should take place in handle in the future
        validateTruePreCheck(op.hasAutoRenewPeriod(), INVALID_RENEWAL_PERIOD);
        validateTruePreCheck(op.autoRenewPeriodOrThrow().seconds() >= 0, INVALID_RENEWAL_PERIOD);
        if (op.hasShardID()) {
            validateTruePreCheck(op.shardIDOrThrow().shardNum() == 0, INVALID_ACCOUNT_ID);
        }
        if (op.hasRealmID()) {
            validateTruePreCheck(op.realmIDOrThrow().realmNum() == 0, INVALID_ACCOUNT_ID);
        }
        // HIP 904 now allows for unlimited auto-associations
        validateTruePreCheck(
                op.maxAutomaticTokenAssociations() >= UNLIMITED_AUTOMATIC_ASSOCIATIONS, INVALID_MAX_AUTO_ASSOCIATIONS);
        validateTruePreCheck(op.initialBalance() >= 0L, INVALID_INITIAL_BALANCE);
        // FUTURE: should this return SEND_RECORD_THRESHOLD_FIELD_IS_DEPRECATED
        validateTruePreCheck(op.sendRecordThreshold() >= 0L, INVALID_SEND_RECORD_THRESHOLD);
        // FUTURE: should this return RECEIVE_RECORD_THRESHOLD_FIELD_IS_DEPRECATED
        validateTruePreCheck(op.receiveRecordThreshold() >= 0L, INVALID_RECEIVE_RECORD_THRESHOLD);
        validateTruePreCheck(
                op.proxyAccountIDOrElse(AccountID.DEFAULT).equals(AccountID.DEFAULT),
                PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);
        // sendRecordThreshold, receiveRecordThreshold and proxyAccountID are deprecated. So no need to check them.
        validateFalsePreCheck(op.hasProxyAccountID(), PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);
        final var alias = op.alias();
        // The alias, if set, must be of EVM address size, or it must be a valid key.
        validateTruePreCheck(alias.length() == 0 || isOfEvmAddressSize(alias) || isKeyAlias(alias), INVALID_ALIAS_KEY);
        // There must be a key provided, and it must not be empty, unless in one very particular case, where the
        // transactionID is null. This code is very particular about which error code to throw in various cases.
        // FUTURE: Clean up the error codes to be consistent.
        final var key = op.key();
        final var isInternal =
                !txn.hasTransactionID() || (systemEntitiesCreatedFlag != null && !systemEntitiesCreatedFlag.get());
        final var keyIsEmpty = isEmpty(key);
        if (!isInternal && keyIsEmpty) {
            if (key == null) {
                throw new PreCheckException(alias.length() > 0 ? INVALID_ALIAS_KEY : KEY_REQUIRED);
            } else if (key.hasThresholdKey() || key.hasKeyList()) {
                throw new PreCheckException(KEY_REQUIRED);
            } else {
                throw new PreCheckException(BAD_ENCODING);
            }
        }
        validateTruePreCheck(key != null, KEY_NOT_PROVIDED);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().cryptoCreateAccountOrThrow();

        // SPEC(HIP-583): If the alias is set during CryptoCreate, then ownership of the alias must be proven by the
        // caller by signing the transaction with the key represented by the alias. It is not necessary for the key on
        // the newly created account to match the alias, but it is required for the transaction to be signed by the key
        // represented by the alias.
        final var alias = op.alias(); // will never be null
        if (alias.length() > 0) {
            // We will only require additional signing if the alias (whether evm-address or key-encoded) is different
            // from the payer key (since checking the payer key is done in every case)
            final var payerKey = context.payerKey();
            // We already know from pure checks that this isn't a long-zero address, and that the alias is either
            // an EVM address alias, or it is a key-alias. Since checking the EVM address alias is cheap, and checking
            // the key alias is more expensive, we'll check for the EVM address alias case first
            if (isOfEvmAddressSize(alias)) {
                // Convert they payer key to an EVM Address, and only gather the alias if the payer key cannot be
                // converted into the same EVM address as the alias
                final var payerEvmAddress = extractEvmAddress(payerKey);
                final var accountKeyEvmAddress = extractEvmAddress(op.keyOrThrow());
                if (!alias.equals(payerEvmAddress) && !alias.equals(accountKeyEvmAddress)) {
                    // Verify there is a signature that matches the EVM address
                    context.requireSignatureForHollowAccountCreation(alias);
                }
            } else if (context.isSyntheticTransaction()) {
                // We allow key-based aliases on _synthetic_ (non-user created) transactions, such as when an account is
                // automatically created based on a crypto transfer to a new account by alias.
                //
                // Try to extract the key. We already know from pure checks that if it isn't an evm address then it
                // must be a key account. So this extraction must work.
                final var aliasAsKey = asKeyFromAliasPreCheck(alias);
                if (!aliasAsKey.equals(payerKey)) {
                    context.requireKey(aliasAsKey);
                }
            } else {
                // We do NOT allow a crypto-create transaction sent from the user (i.e. a user transaction) to define
                // a key-based alias. Technically we could, but key-aliases are deprecated, so we don't allow it.
                throw new PreCheckException(INVALID_ALIAS_KEY);
            }
        }

        // You cannot set receiverSigRequired without asserting ownership of the key. Since these are cryptographic
        // keys, if the key was a contract key or delegatable contract key, then it would fail to be verified.
        // FUTURE Maybe a more specific error code would be better here rather than just failing at key verification.
        final var receiverSigReq = op.receiverSigRequired();
        if (receiverSigReq) {
            context.requireKey(op.keyOrThrow());
        }
    }

    /**
     * This method is called during the handle workflow. It executes the {@code CryptoCreate} transaction, creating a
     * new account with the given properties.
     *
     * <p>If the transaction is successful, the account is created and the payer account is charged the transaction fee
     * and the initial balance of new account and the balance of the new account is set to the initial balance.
     *
     * <p>If the transaction is not successful, then the account is not created and the payer account will be charged
     * the transaction fee.
     *
     * @throws NullPointerException if one of the arguments is {@code null}
     * @throws HandleException      if the transaction is not successful due to payer account being deleted or has
     *                              insufficient balance or the account is not created due to the usage of a price
     *                              regime
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var txnBody = context.body();
        final var op = txnBody.cryptoCreateAccountOrThrow();
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);

        // FUTURE: Use the config and check if accounts can be created. Currently, this check is being done in
        // `finishCryptoCreate` before `commit`
        final boolean stillCreatingSystemEntities =
                systemEntitiesCreatedFlag != null && !systemEntitiesCreatedFlag.get();

        // Validate fields in the transaction body that involves checking with dynamic properties or state
        validateSemantics(context, accountStore, op, stillCreatingSystemEntities);

        // Now that we have fully validated the transaction inputs, it is time to create an account!
        // First, charge the payer for whatever initial balance there is, unless this dispatch is specifically
        // to create system entities at genesis.
        if (op.initialBalance() > 0 && !stillCreatingSystemEntities) {
            final var payer =
                    getIfUsable(context.payer(), accountStore, context.expiryValidator(), INVALID_PAYER_ACCOUNT_ID);
            final long newPayerBalance = payer.tinybarBalance() - op.initialBalance();
            validatePayer(payer, newPayerBalance);

            // Change payer's balance to reflect the deduction of the initial balance for the new account
            final var modifiedPayer =
                    payer.copyBuilder().tinybarBalance(newPayerBalance).build();
            accountStore.put(modifiedPayer);
        }

        // Build the new account to be persisted based on the transaction body and save the newly created account
        // number in the record builder
        final var accountCreated = buildAccount(op, context);
        accountStore.putAndIncrementCount(accountCreated);

        final var createdAccountID = accountCreated.accountIdOrThrow();
        final var recordBuilder = context.savepointStack().getBaseBuilder(CryptoCreateStreamBuilder.class);
        recordBuilder.accountID(createdAccountID);

        // Put if any new alias is associated with the account into account store
        final var alias = op.alias();
        if (alias.length() > 0) {
            // If we have been given an EVM address, then we can just put it into the store
            if (isOfEvmAddressSize(alias)) {
                accountStore.putAndIncrementCountAlias(alias, createdAccountID);
            } else {
                // The only other kind of alias it could be is a key-alias. And in that case, it could be an ED25519
                // protobuf-encoded key, or it could be an ECDSA_SECP256K1 protobuf-encoded key. In this latter case,
                // we will actually store two things in the map. We'll store the raw alias, and we'll also extract the
                // EVM address from the key and store a second mapping from that EVM address to account ID. This makes
                // it trivial at look up time to see if an EVM address is already in use, or to look up the account ID
                // for an EVM address, and leads to simpler, more correct code.
                final var key = asKeyFromAlias(alias);
                validateTrue(isValid(key), INVALID_ALIAS_KEY); // In case the protobuf encoded key is BOGUS!
                final var evmAddress = extractEvmAddress(key);
                if (evmAddress != null) {
                    accountStore.putAndIncrementCountAlias(evmAddress, createdAccountID);
                    recordBuilder.evmAddress(evmAddress);
                }
                accountStore.putAndIncrementCountAlias(alias, createdAccountID);
            }
        }
    }

    /* ----------- Helper Methods ----------- */

    /**
     * Validate the fields in the transaction body that involves checking with dynamic
     * properties or state. This check is done as part of the handle workflow.
     *
     * @param context handle context
     * @param accountStore account store
     * @param op crypto create transaction body
     * @param stillCreatingSystemEntities whether system entities are still being created
     */
    private void validateSemantics(
            @NonNull final HandleContext context,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final CryptoCreateTransactionBody op,
            final boolean stillCreatingSystemEntities) {
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var accountConfig = context.configuration().getConfigData(AccountsConfig.class);
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        final var alias = op.alias();
        // You can never set the alias to be an "entity num alias" (sometimes called "long-zero").
        validateFalse(isEntityNumAlias(alias, hederaConfig.shard(), hederaConfig.realm()), INVALID_ALIAS_KEY);

        // We have a limit on the total maximum number of entities that can be created on the network, for different
        // types of entities. We need to verify that creating a new account won't exceed that number.
        if (accountStore.getNumberOfAccounts() + 1 > accountConfig.maxNumber()) {
            throw new HandleException(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        }

        // We have to check the memo, which may be too long or in some other way be invalid.
        context.attributeValidator().validateMemo(op.memo());

        // Aliases are fully supported in mainnet.
        final var hasAlias = alias.length() > 0;
        // If there is an alias, then we need to make sure no other account or contract account is using that alias.
        if (hasAlias) {
            final var config = context.configuration().getConfigData(HederaConfig.class);
            // find account by alias and check if it was deleted
            var accountId = accountStore.getAccountIDByAlias(config.shard(), config.realm(), alias);
            var account = accountId != null ? accountStore.getAccountById(accountId) : null;
            var isDeleted = account == null || account.deleted();
            validateTrue(accountId == null || isDeleted, ALIAS_ALREADY_ASSIGNED);
        }

        // We've already validated in pureChecks that there is a renewal period. Deeper validation.
        context.attributeValidator()
                .validateAutoRenewPeriod(op.autoRenewPeriodOrThrow().seconds());

        // When the account is created, it can be created with some auto-association slots. But we have some
        // additional ledger-wide limits we need to check as well.
        validateFalse(
                cryptoCreateValidator.tooManyAutoAssociations(
                        op.maxAutomaticTokenAssociations(), ledgerConfig, entitiesConfig, tokensConfig),
                INVALID_MAX_AUTO_ASSOCIATIONS);

        // This proxy field has been deprecated. We do not allow people to use it.
        validateFalse(
                op.hasProxyAccountID() && !op.proxyAccountIDOrThrow().equals(AccountID.DEFAULT),
                PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);

        // Validates the key. One special twist is that if there is no transaction ID, then the creation call came from
        // another internal service, and not through the HAPI. In that case, we have some special validation. See the
        // method for details.
        cryptoCreateValidator.validateKey(
                op.keyOrThrow(), // cannot be null by this point
                context.attributeValidator(),
                context.savepointStack().getBaseBuilder(StreamBuilder.class).isInternalDispatch()
                        || stillCreatingSystemEntities);

        // Validate the staking information included in this account creation.
        if (op.hasStakedAccountId() || op.hasStakedNodeId()) {
            StakingValidator.validateStakedIdForCreation(
                    context.configuration().getConfigData(StakingConfig.class).isEnabled(),
                    op.declineReward(),
                    op.stakedId().kind().name(),
                    op.stakedAccountId(),
                    op.stakedNodeId(),
                    accountStore,
                    context.networkInfo());
        }
    }

    /**
     * Validates the payer account exists and has enough balance to cover the initial balance of the
     * account to be created.
     *
     * @param payer the payer account
     * @param newPayerBalance the initial balance of the account to be created
     */
    private void validatePayer(@NonNull final Account payer, final long newPayerBalance) {
        // If the payer account is deleted, throw an exception
        if (payer.deleted()) {
            throw new HandleException(ACCOUNT_DELETED);
        }
        if (newPayerBalance < 0) {
            throw new HandleException(INSUFFICIENT_PAYER_BALANCE);
        }
        // FUTURE: check if payer account is detached when we have started expiring accounts ?
    }

    /**
     * Builds an account based on the transaction body and the consensus time.
     *
     * @param op the transaction body
     * @param handleContext the handle context
     * @return the account created
     */
    @NonNull
    private Account buildAccount(CryptoCreateTransactionBody op, HandleContext handleContext) {
        final var autoRenewPeriod = op.autoRenewPeriodOrThrow().seconds();
        final var consensusTime = handleContext.consensusNow().getEpochSecond();
        final var expiry = consensusTime + autoRenewPeriod;
        var builder = Account.newBuilder()
                .memo(op.memo())
                .expirationSecond(expiry)
                .autoRenewSeconds(autoRenewPeriod)
                .receiverSigRequired(op.receiverSigRequired())
                .maxAutoAssociations(op.maxAutomaticTokenAssociations())
                .tinybarBalance(op.initialBalance())
                .declineReward(op.declineReward())
                .key(op.keyOrThrow())
                .stakeAtStartOfLastRewardedPeriod(NOT_REWARDED_SINCE_LAST_STAKING_META_CHANGE)
                .stakePeriodStart(NO_STAKE_PERIOD_START)
                .alias(op.alias());

        // We do this separately because we want to let the protobuf object remain UNSET for the staked ID if neither
        // of the staking information was set in the transaction body.
        if (op.hasStakedAccountId()) {
            builder.stakedAccountId(op.stakedAccountId());
        } else if (op.hasStakedNodeId()) {
            builder.stakedNodeId(op.stakedNodeIdOrThrow());
        }

        // Set the new account number
        final var hederaConfig = handleContext.configuration().getConfigData(HederaConfig.class);
        builder.accountId(AccountID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .accountNum(handleContext.entityNumGenerator().newEntityNum())
                .build());

        return builder.build();
    }

    @Override
    @NonNull
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        // Variable bytes plus two additional longs for balance and auto-renew period; plus a boolean for receiver sig
        // required.
        final var op = feeContext.body().cryptoCreateAccountOrThrow();
        final var keySize =
                op.hasKey() ? getAccountKeyStorageSize(CommonPbjConverters.fromPbj(op.keyOrElse(Key.DEFAULT))) : 0L;
        final var unlimitedAutoAssociations =
                feeContext.configuration().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();
        final var maxAutoAssociationsSize =
                !unlimitedAutoAssociations && op.maxAutomaticTokenAssociations() > 0 ? INT_SIZE : 0L;
        final var baseSize = op.memo().length() + keySize + maxAutoAssociationsSize;
        final var lifeTime = op.autoRenewPeriodOrElse(Duration.DEFAULT).seconds();
        final var feeCalculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        final var fee = feeCalculator
                .addBytesPerTransaction(baseSize + (2 * LONG_SIZE) + BOOL_SIZE)
                .addRamByteSeconds((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * lifeTime)
                .addNetworkRamByteSeconds(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        if (!unlimitedAutoAssociations && op.maxAutomaticTokenAssociations() > 0) {
            fee.addRamByteSeconds(op.maxAutomaticTokenAssociations() * lifeTime * CREATE_SLOT_MULTIPLIER);
        }
        if (IMMUTABILITY_SENTINEL_KEY.equals(op.key())) {
            final var lazyCreationFee = feeContext.dispatchComputeFees(UPDATE_TXN_BODY_BUILDER, feeContext.payer());
            return fee.calculate().plus(lazyCreationFee);
        }
        return fee.calculate();
    }
}
