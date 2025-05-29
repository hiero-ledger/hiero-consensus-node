// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hedera.hapi.node.base.TokenKeyValidation.NO_VALIDATION;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.txnEstimateFactory;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.service.token.impl.util.TokenKey.METADATA_KEY;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.validation.AttributeValidator.isKeyRemoval;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.EntityCreate;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.token.TokenUpdateUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenKey;
import com.hedera.node.app.service.token.impl.validators.TokenUpdateValidator;
import com.hedera.node.app.service.token.records.TokenUpdateStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides the state transition for token update.
 */
@Singleton
public class TokenUpdateHandler extends BaseTokenHandler implements TransactionHandler {
    private static final AccountID ZERO_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(0L).build();
    private final TokenUpdateValidator tokenUpdateValidator;

    /**
     * Create a new {@link TokenUpdateHandler} instance.
     * @param tokenUpdateValidator The {@link TokenUpdateValidator} to use.
     */
    @Inject
    public TokenUpdateHandler(@NonNull final TokenUpdateValidator tokenUpdateValidator) {
        this.tokenUpdateValidator = tokenUpdateValidator;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.tokenUpdateOrThrow();
        validateTruePreCheck(op.hasToken(), INVALID_TOKEN_ID);
        // IMPORTANT: No matter the TokenKeyValidation mode, we always require keys to
        // be structurally valid. Putting structurally invalid keys into ledger state
        // makes no sense, and could create problems for mirror nodes and block explorers.
        // That is, using NO_VALIDATION only lets a user set a new key without its signature;
        // and thus set a low-entropy key that proves a role function has been "disabled"
        for (final var tokenKey : TOKEN_KEYS) {
            if (tokenKey.isPresentInUpdate(op)) {
                final var key = tokenKey.getFromUpdate(op);
                if (!isKeyRemoval(key)) {
                    validateTruePreCheck(isValid(key), tokenKey.invalidKeyStatus());
                }
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenUpdateOrThrow();
        final var token = context.createStore(ReadableTokenStore.class).get(op.tokenOrThrow());
        mustExist(token, INVALID_TOKEN_ID);
        addRequiredSigners(context, op, token);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenUpdateOrThrow();
        final var tokenId = op.tokenOrThrow();
        final var recordBuilder = context.savepointStack().getBaseBuilder(TokenUpdateStreamBuilder.class);

        // validate fields that involve config or state
        final var validationResult = tokenUpdateValidator.validateSemantics(context, op);
        // get the resolved expiry meta and token
        final var token = validationResult.token();
        final var resolvedExpiry = validationResult.resolvedExpiryMeta();

        final var storeFactory = context.storeFactory();
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var config = context.configuration();

        // If the operation has treasury change, then we need to check if the new treasury is valid
        // and if the treasury is not already associated with the token, see if it has auto associations
        // enabled and has open slots. If so, auto-associate.
        // We allow existing treasuries to have any nft balances left over, but the new treasury should
        // not have any balances left over. Transfer all balances for the current token to new treasury
        // Also check if the treasury is not a zero account if the transaction is a contract call
        if (op.hasTreasury() && isHapiCallOrNonZeroTreasuryAccount(txn.hasTransactionID(), op)) {
            final var existingTreasury = token.treasuryAccountIdOrThrow();
            final var newTreasury = op.treasuryOrThrow();
            var newTreasuryAccount = getIfUsable(
                    newTreasury, accountStore, context.expiryValidator(), INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
            final var newTreasuryRel = tokenRelStore.get(newTreasury, tokenId);
            // If there is no treasury relationship, then we need to create one if auto associations are available.
            // If not fail
            if (newTreasuryRel == null) {
                final var newRelation = autoAssociate(
                        newTreasuryAccount.accountIdOrThrow(), token, accountStore, tokenRelStore, config);
                recordBuilder.addAutomaticTokenAssociation(
                        asTokenAssociation(newRelation.tokenId(), newRelation.accountId()));
                newTreasuryAccount = requireNonNull(accountStore.get(newTreasury));
            }

            if (!newTreasury.equals(existingTreasury)) {
                final var existingTreasuryAccount = getIfUsable(
                        existingTreasury, accountStore, context.expiryValidator(), INVALID_TREASURY_ACCOUNT_FOR_TOKEN);

                updateTreasuryTitles(existingTreasuryAccount, newTreasuryAccount, token, accountStore, tokenRelStore);
                // If the token is fungible, transfer fungible balance to new treasury
                // If it is non-fungible token transfer the ownership of the NFTs from old treasury to new treasury
                transferTokensToNewTreasury(existingTreasury, newTreasury, token, tokenRelStore, accountStore);
            }
        }
        final var tokenBuilder = customizeToken(token, resolvedExpiry, op, txn.hasTransactionID());
        tokenStore.put(tokenBuilder.build());
        recordBuilder.tokenType(token.tokenType());
    }

    /**
     * Transfer tokens from old treasury to new treasury if the token is fungible. If the token is non-fungible,
     * transfer the ownership of the NFTs from old treasury to new treasury
     *
     * @param oldTreasury old treasury account
     * @param newTreasury new treasury account
     * @param token token
     * @param tokenRelStore token relationship store
     * @param accountStore account store
     */
    private void transferTokensToNewTreasury(
            final AccountID oldTreasury,
            final AccountID newTreasury,
            final Token token,
            final WritableTokenRelationStore tokenRelStore,
            final WritableAccountStore accountStore) {
        final var tokenId = token.tokenIdOrThrow();
        // Validate both accounts are not frozen and have the right keys
        final var oldTreasuryRel = getIfUsable(oldTreasury, tokenId, tokenRelStore);
        final var newTreasuryRel = getIfUsable(newTreasury, tokenId, tokenRelStore);
        if (oldTreasuryRel.balance() > 0) {
            validateFrozenAndKey(oldTreasuryRel);
            validateFrozenAndKey(newTreasuryRel);

            if (token.tokenType().equals(FUNGIBLE_COMMON)) {
                // Transfers fungible balances and updates account's numOfPositiveBalances
                // and puts to modifications on state.
                transferFungibleTokensToTreasury(oldTreasuryRel, newTreasuryRel, tokenRelStore, accountStore);
            } else {
                // Check whether new treasury has balance, if it does throw TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES
                validateTrue(newTreasuryRel.balance() == 0, TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);
                // Transfers NFT ownerships and updates account's numOwnedNfts and
                // tokenRelation's balance and puts to modifications on state.
                changeOwnerToNewTreasury(oldTreasuryRel, newTreasuryRel, tokenRelStore, accountStore);
            }
        }
    }

    /**
     * Transfer fungible tokens from old treasury to new treasury.
     * NOTE: This updates account's numOfPositiveBalances and puts to modifications on state.
     *
     * @param fromTreasuryRel old treasury relationship
     * @param toTreasuryRel new treasury relationship
     * @param tokenRelStore token relationship store
     * @param accountStore account store
     */
    private void transferFungibleTokensToTreasury(
            @NonNull final TokenRelation fromTreasuryRel,
            @NonNull final TokenRelation toTreasuryRel,
            final WritableTokenRelationStore tokenRelStore,
            final WritableAccountStore accountStore) {
        final var adjustment = fromTreasuryRel.balance();

        final var fromTreasury = requireNonNull(accountStore.getAccountById(fromTreasuryRel.accountIdOrThrow()));
        final var toTreasury = requireNonNull(accountStore.getAccountById(toTreasuryRel.accountIdOrThrow()));

        adjustBalance(fromTreasuryRel, fromTreasury, -adjustment, tokenRelStore, accountStore);
        adjustBalance(toTreasuryRel, toTreasury, adjustment, tokenRelStore, accountStore);
    }

    /**
     * Change the ownership of the NFTs from old treasury to new treasury.
     * NOTE: This updates account's numOwnedNfts and tokenRelation's balance and puts to modifications on state.
     *
     * @param fromTreasuryRel old treasury relationship
     * @param toTreasuryRel new treasury relationship
     * @param tokenRelStore token relationship store
     * @param accountStore account store
     */
    private void changeOwnerToNewTreasury(
            final TokenRelation fromTreasuryRel,
            final TokenRelation toTreasuryRel,
            final WritableTokenRelationStore tokenRelStore,
            final WritableAccountStore accountStore) {
        final var fromTreasury = accountStore.getAccountById(fromTreasuryRel.accountId());
        final var toTreasury = accountStore.getAccountById(toTreasuryRel.accountId());

        final var fromRelBalance = fromTreasuryRel.balance();
        final var toRelBalance = toTreasuryRel.balance();

        final var fromNftsOwned = fromTreasury.numberOwnedNfts();
        final var toNftsOwned = toTreasury.numberOwnedNfts();

        final var fromTreasuryCopy = fromTreasury.copyBuilder();
        final var toTreasuryCopy = toTreasury.copyBuilder();
        final var fromRelCopy = fromTreasuryRel.copyBuilder();
        final var toRelCopy = toTreasuryRel.copyBuilder();

        // Update the number of positive balances and number of owned NFTs for old and new treasuries
        final var newFromPositiveBalancesCount =
                fromRelBalance > 0 ? fromTreasury.numberPositiveBalances() - 1 : fromTreasury.numberPositiveBalances();
        final var newToPositiveBalancesCount =
                toRelBalance > 0 ? toTreasury.numberPositiveBalances() + 1 : toTreasury.numberPositiveBalances();
        accountStore.put(fromTreasuryCopy
                .numberPositiveBalances(newFromPositiveBalancesCount)
                .numberOwnedNfts(fromNftsOwned - fromRelBalance)
                .build());
        accountStore.put(toTreasuryCopy
                .numberPositiveBalances(newToPositiveBalancesCount)
                .numberOwnedNfts(toNftsOwned + fromRelBalance)
                .build());
        tokenRelStore.put(fromRelCopy.balance(0).build());
        tokenRelStore.put(toRelCopy.balance(toRelBalance + fromRelBalance).build());
    }

    /**
     * Validate both KYC is granted and token is not frozen on the token.
     *
     * @param tokenRel token relationship
     */
    private void validateFrozenAndKey(final TokenRelation tokenRel) {
        validateTrue(!tokenRel.frozen(), ACCOUNT_FROZEN_FOR_TOKEN);
        validateTrue(tokenRel.kycGranted(), TOKEN_HAS_NO_KYC_KEY);
    }

    /**
     * Build a Token based on the given token update transaction body.
     *
     * @param token token to be updated
     * @param resolvedExpiry resolved expiry
     * @param op token update transaction body
     * @return updated token builder
     */
    private Token.Builder customizeToken(
            @NonNull final Token token,
            @NonNull final ExpiryMeta resolvedExpiry,
            @NonNull final TokenUpdateTransactionBody op,
            final boolean isHapiCall) {
        final var copyToken = token.copyBuilder();
        // All these keys are validated in validateSemantics
        // If these keys did not exist on the token already, they can't be changed on update
        updateKeys(op, token, copyToken);
        updateExpiryFields(op, resolvedExpiry, copyToken);
        updateTokenAttributes(op, copyToken, token, isHapiCall);
        return copyToken;
    }

    /**
     * Updates token name, token symbol, token metadata, token memo
     * and token treasury if they are present in the token update transaction body.
     *
     * @param op token update transaction body
     * @param builder token builder
     * @param originalToken original token
     */
    private void updateTokenAttributes(
            final TokenUpdateTransactionBody op,
            final Token.Builder builder,
            final Token originalToken,
            final boolean isHapiCall) {
        if (!op.symbol().isEmpty()) {
            builder.symbol(op.symbol());
        }
        if (!op.name().isEmpty()) {
            builder.name(op.name());
        }
        if (op.hasMemo()) {
            final var memo = op.memoOrThrow();
            // Since an tokenUpdate() system call cannot encode a difference between
            // (1) choosing not to update the memo and (2) setting it to a blank string,
            // we only set a blank memo if the update came from HAPI
            if (!memo.isBlank() || isHapiCall) {
                builder.memo(memo);
            }
        }
        if (op.hasMetadata()) {
            builder.metadata(op.metadataOrThrow());
        }
        // Here we check that there is a treasury to be updated,
        // that if the transaction is a contract call the treasury shouldn't be a zero account
        // and that the provided treasury account is different from the current one
        if (op.hasTreasury()
                && isHapiCallOrNonZeroTreasuryAccount(isHapiCall, op)
                && !op.treasuryOrThrow().equals(originalToken.treasuryAccountId())) {
            builder.treasuryAccountId(op.treasuryOrThrow());
        }
    }

    /**
     * Updates expiry fields of the token if they are present in the token update transaction body.
     *
     * @param op token update transaction body
     * @param resolvedExpiry resolved expiry
     * @param builder token builder
     */
    private void updateExpiryFields(
            final TokenUpdateTransactionBody op, final ExpiryMeta resolvedExpiry, final Token.Builder builder) {
        if (op.hasExpiry()) {
            builder.expirationSecond(resolvedExpiry.expiry());
        }
        if (op.hasAutoRenewPeriod()) {
            builder.autoRenewSeconds(resolvedExpiry.autoRenewPeriod());
        }
        if (op.hasAutoRenewAccount()) {
            builder.autoRenewAccountId(resolvedExpiry.autoRenewAccountId());
        }
    }

    /**
     * Updates keys of the token if they are present in the token update transaction body.
     * All keys can be updates only if they had already existed on the token.
     * These keys can't be updated if they were not added during creation.
     *
     * @param op token update transaction body
     * @param originalToken original token
     * @param builder token builder
     */
    private void updateKeys(
            final TokenUpdateTransactionBody op, final Token originalToken, final Token.Builder builder) {
        TOKEN_KEYS.forEach(key -> key.updateKey(op, originalToken, builder));
    }

    /**
     * Add all signature requirements for TokenUpdateTx.
     * Note: those requirements drastically changed after HIP-540
     *
     * @param context pre handle context
     * @param op token update transaction body
     * @param token original token
     */
    private void addRequiredSigners(
            @NonNull PreHandleContext context, @NonNull final TokenUpdateTransactionBody op, @NonNull final Token token)
            throws PreCheckException {
        // Since we de-duplicate all the keys in the PreHandleContext,
        // we can safely add one key multiple times for the transaction to keep the logic simple.

        // metadata can be updated with either admin key or metadata key
        if (op.hasMetadata()) {
            if (token.hasMetadataKey()) {
                requireAdminOrRole(context, token, METADATA_KEY);
            } else {
                requireAdmin(context, token);
            }
        }
        // updating treasury needs admin and new treasury key
        // If the transactionID is missing we have contract call,
        // so we need to verify that the provided treasury is not a zero account.
        if (op.hasTreasury()
                && isHapiCallOrNonZeroTreasuryAccount(context.body().hasTransactionID(), op)) {
            requireAdmin(context, token);
            context.requireKeyOrThrow(op.treasuryOrThrow(), INVALID_ACCOUNT_ID);
        }
        // updating auto-renew account needs admin key and the new auto-renewal account key
        if (op.hasAutoRenewAccount()) {
            requireAdmin(context, token);
            context.requireKeyOrThrow(op.autoRenewAccountOrThrow(), INVALID_AUTORENEW_ACCOUNT);
        }
        // updating admin key needs the old admin key and the new admin key
        if (op.hasAdminKey()) {
            requireAdmin(context, token);
            context.requireKey(op.adminKeyOrThrow());
        }
        // Any key removal requires admin key
        if (containsKeyRemoval(op)) {
            requireAdmin(context, token);
        }
        // updating memo, name, symbol, auto-renew period needs admin key
        if (updatesAdminOnlyNonKeyTokenProperty(op)) {
            requireAdmin(context, token);
        }
        // Any role key can be updated if the key is not empty of the token.
        // Updating any non-admin keys with the key verification mode is NO_VALIDATION needs either
        // admin key or the role key to sign.If the key verification mode is FULL_VALIDATION, then
        // the new key also should sign the transaction.
        for (final var tokenKey : NON_ADMIN_TOKEN_KEYS) {
            if (tokenKey.isPresentInUpdate(op)) {
                final var newRoleKey = tokenKey.getFromUpdate(op);
                if (!isKeyRemoval(newRoleKey)) {
                    if (op.keyVerificationMode() == NO_VALIDATION) {
                        requireAdminOrRole(context, token, tokenKey);
                    } else {
                        // With "full" verification mode, our required key
                        // structure is a 1/2 threshold with components:
                        //   - Admin key
                        //   - A 2/2 list including the role key and its replacement key
                        final var key = tokenKey.getFromUpdate(op);
                        requireAdminOrRole(context, token, tokenKey, key);
                    }
                }
            }
        }
    }

    /**
     * Check if the token update transaction body updates any non-key token property.
     * @param context pre handle context
     * @param token original token
     * @param roleKey role key
     * @throws PreCheckException if the token is immutable
     */
    private void requireAdminOrRole(
            @NonNull final PreHandleContext context, @NonNull final Token token, @NonNull final TokenKey roleKey)
            throws PreCheckException {
        requireAdminOrRole(context, token, roleKey, null);
    }

    /**
     * If the original token has RoleKey, only then updating role key is possible. Otherwise,
     * fail with TOKEN_IS_IMMUTABLE.
     * If the original token has AdminKey, then require the admin key to sign the transaction. Otherwise, require the
     * role key to sign the transaction.
     * If the original token has neither AdminKey nor RoleKey, then fail with TOKEN_IS_IMMUTABLE.
     * @param context pre handle context
     * @param token original token
     * @param roleKey role key
     * @param replacementKey replacement key
     * @throws PreCheckException if the token is immutable
     */
    private void requireAdminOrRole(
            @NonNull final PreHandleContext context,
            @NonNull final Token token,
            @NonNull final TokenKey roleKey,
            @Nullable final Key replacementKey)
            throws PreCheckException {
        final var maybeRoleKey = roleKey.getFromToken(token);
        // Prioritize TOKEN_IS_IMMUTABLE for completely immutable tokens
        mustExist(maybeRoleKey, token.hasAdminKey() ? roleKey.tokenHasNoKeyStatus() : TOKEN_IS_IMMUTABLE);
        if (token.hasAdminKey()) {
            context.requireKey(oneOf(
                    replacementKey == null ? maybeRoleKey : allOf(maybeRoleKey, replacementKey),
                    token.adminKeyOrThrow()));
        } else {
            context.requireKey(maybeRoleKey);
            if (replacementKey != null) {
                context.requireKey(replacementKey);
            }
        }
    }

    /**
     * Checks if the token has adminKey, if so require the admin key to sign the transaction.
     * If the token does not have adminKey, then fail with TOKEN_IS_IMMUTABLE.
     * @param context pre handle context
     * @param originalToken original token
     * @throws PreCheckException if the token is immutable
     */
    private void requireAdmin(@NonNull final PreHandleContext context, @NonNull final Token originalToken)
            throws PreCheckException {
        validateTruePreCheck(originalToken.hasAdminKey(), TOKEN_IS_IMMUTABLE);
        context.requireKey(originalToken.adminKeyOrThrow());
    }

    /**
     * Checks if the token update transaction body has any key removals, by using immutable sentinel keys
     * to remove the key.
     * @param op token update transaction body
     * @return true if the token update transaction body has any key removals, false otherwise
     */
    private boolean containsKeyRemoval(@NonNull final TokenUpdateTransactionBody op) {
        for (final var tokenKey : TOKEN_KEYS) {
            if (tokenKey.containsKeyRemoval(op)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a threshold key with threshold 1 with the given keys.
     * @param keysRequired keys required
     * @return threshold key with threshold 1
     */
    public static Key oneOf(@NonNull final Key... keysRequired) {
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .keys(new KeyList(Arrays.asList(keysRequired)))
                        .threshold(1)
                        .build())
                .build();
    }

    /**
     * Creates a key list with the given keys.
     * @param keysRequired keys required
     * @return key list
     */
    private Key allOf(@NonNull final Key... keysRequired) {
        return Key.newBuilder()
                .keyList(new KeyList(Arrays.asList(keysRequired)))
                .build();
    }

    /**
     * If there is a change in treasury account, update the treasury titles of the old and
     * new treasury accounts.
     * NOTE : This updated the numberTreasuryTitles on old and new treasury accounts.
     * And also updates new treasury relationship to not be frozen
     *
     * @param existingTreasuryAccount existing treasury account
     * @param newTreasuryAccount new treasury account
     * @param originalToken original token
     * @param accountStore account store
     * @param tokenRelStore token relation store
     */
    private void updateTreasuryTitles(
            @NonNull final Account existingTreasuryAccount,
            @NonNull final Account newTreasuryAccount,
            @NonNull final Token originalToken,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableTokenRelationStore tokenRelStore) {
        final var newTokenRelation = tokenRelStore.get(newTreasuryAccount.accountId(), originalToken.tokenId());
        final var newRelCopy = newTokenRelation.copyBuilder();

        if (originalToken.hasFreezeKey()) {
            newRelCopy.frozen(false);
        }
        if (originalToken.hasKycKey()) {
            newRelCopy.kycGranted(true);
        }

        final var existingTreasuryTitles = existingTreasuryAccount.numberTreasuryTitles();
        final var newTreasuryAccountTitles = newTreasuryAccount.numberTreasuryTitles();
        final var copyOldTreasury =
                existingTreasuryAccount.copyBuilder().numberTreasuryTitles(existingTreasuryTitles - 1);
        final var copyNewTreasury = newTreasuryAccount.copyBuilder().numberTreasuryTitles(newTreasuryAccountTitles + 1);

        accountStore.put(copyOldTreasury.build());
        accountStore.put(copyNewTreasury.build());
        tokenRelStore.put(newRelCopy.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var op = body.tokenUpdateOrThrow();
        final var readableStore = feeContext.readableStore(ReadableTokenStore.class);
        final var token = readableStore.get(op.tokenOrThrow());

        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(body), sigValueObj, token));

        EntityCreate entity = new EntityCreate("Token", "TokenUpdate", "Update a token type", 0, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 0);
        params.put("numKeys", 0);
        params.put("hasCustomFee", YesOrNo.NO);
        FeeResult simpleFee = entity.computeFee(params);
        return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee);
    }

    private boolean isHapiCallOrNonZeroTreasuryAccount(final boolean isHapiCall, final TokenUpdateTransactionBody op) {
        return isHapiCall || !isZeroAccount(op.treasuryOrElse(AccountID.DEFAULT));
    }

    private boolean isZeroAccount(@NonNull final AccountID accountID) {
        return accountID.equals(ZERO_ACCOUNT_ID);
    }

    private FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.TransactionBody txn, final SigValueObj svo, final Token token) {
        final var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
        if (token != null) {
            final var estimate = TokenUpdateUsage.newEstimate(
                            txn, txnEstimateFactory.get(sigUsage, txn, ESTIMATOR_UTILS))
                    .givenCurrentAdminKey(
                            token.hasAdminKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.adminKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentFreezeKey(
                            token.hasFreezeKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.freezeKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentWipeKey(
                            token.hasWipeKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.wipeKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentSupplyKey(
                            token.hasSupplyKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.supplyKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentKycKey(
                            token.hasKycKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.kycKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentPauseKey(
                            token.hasPauseKey()
                                    ? Optional.of(CommonPbjConverters.fromPbj(token.pauseKeyOrThrow()))
                                    : Optional.empty())
                    .givenCurrentName(token.name())
                    .givenCurrentMemo(token.memo())
                    .givenCurrentSymbol(token.symbol())
                    .givenCurrentExpiry(token.expirationSecond());
            if (token.hasAutoRenewAccountId()) {
                estimate.givenCurrentlyUsingAutoRenewAccount();
            }
            return estimate.get();
        } else {
            return CONSTANT_FEE_DATA;
        }
    }
}
