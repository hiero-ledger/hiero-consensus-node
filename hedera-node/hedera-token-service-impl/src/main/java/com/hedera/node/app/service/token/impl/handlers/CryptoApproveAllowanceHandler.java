// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_DELEGATING_SPENDER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.CRYPTO_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TOKEN_ALLOWANCE_SIZE;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.isValidOwner;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.validateAllowanceLimit;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.validation.Validations.validateNullableAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding
 * {@link HederaFunctionality#CRYPTO_APPROVE_ALLOWANCE}.
 */
@Singleton
public class CryptoApproveAllowanceHandler implements TransactionHandler {
    private final ApproveAllowanceValidator allowanceValidator;

    /**
     * Constructs a {@link CryptoApproveAllowanceHandler} with the given {@link ApproveAllowanceValidator}.
     * @param allowanceValidator the validator to use for validating the transaction
     */
    @Inject
    public CryptoApproveAllowanceHandler(@NonNull final ApproveAllowanceValidator allowanceValidator) {
        this.allowanceValidator = allowanceValidator;
    }

    /**
     * Validates the transaction body for {@link HederaFunctionality#CRYPTO_APPROVE_ALLOWANCE}.
     * @param context the pure checks context
     * @throws PreCheckException if the transaction is invalid for any reason
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.cryptoApproveAllowanceOrThrow();

        // The transaction must have at least one type of allowance. There is also an upper limit to the allowed number
        // of allowances in a single transaction, but that check requires the config, and is thus not a pure check.
        // So we will check that later in handle.
        final var cryptoAllowances = op.cryptoAllowances();
        final var tokenAllowances = op.tokenAllowances();
        final var nftAllowances = op.nftAllowances();
        final var totalAllowancesSize = cryptoAllowances.size() + tokenAllowances.size() + nftAllowances.size();
        validateTruePreCheck(totalAllowancesSize != 0, EMPTY_ALLOWANCES);

        // It is OK for the owner to be null, because that just means that we should use the payer as the owner.
        // But the spender always needs to be specified.
        for (final var allowance : cryptoAllowances) {
            validateNullableAccountID(allowance.owner());
            validateTruePreCheck(allowance.amount() >= 0, NEGATIVE_ALLOWANCE_AMOUNT);
            validateAccountID(allowance.spender(), INVALID_ALLOWANCE_SPENDER_ID);
        }

        for (final var allowance : tokenAllowances) {
            validateNullableAccountID(allowance.owner());
            validateTruePreCheck(allowance.amount() >= 0, NEGATIVE_ALLOWANCE_AMOUNT);
            validateAccountID(allowance.spender(), INVALID_ALLOWANCE_SPENDER_ID);
            mustExist(allowance.tokenId(), INVALID_TOKEN_ID);
        }

        for (final var allowance : nftAllowances) {
            validateNullableAccountID(allowance.owner());
            validateAccountID(allowance.spender(), INVALID_ALLOWANCE_SPENDER_ID);
            mustExist(allowance.tokenId(), INVALID_TOKEN_ID);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var payerId = context.payer();
        final var op = txn.cryptoApproveAllowanceOrThrow();

        // If the allowance owner is not the payer, then the transaction must have been signed by the owner. Note, if
        // the owner is missing, then the owner is assumed to be the payer. This is true for crypto allowances and
        // for token allowances.
        for (final var allowance : op.cryptoAllowances()) {
            final var owner = allowance.owner();
            if (owner != null && !owner.equals(payerId)) {
                context.requireKeyOrThrow(owner, INVALID_ALLOWANCE_OWNER_ID);
            }
        }

        // Fungible token allowances are the same as basic crypto approvals and allowances
        for (final var allowance : op.tokenAllowances()) {
            final var owner = allowance.owner();
            // (TEMPORARY) Remove after diff testing is complete
            if (owner != null && owner.hasAlias()) {
                throw new PreCheckException(INVALID_ALLOWANCE_OWNER_ID);
            }
            if (owner != null && !owner.equals(payerId)) {
                context.requireKeyOrThrow(owner, INVALID_ALLOWANCE_OWNER_ID);
            }
        }

        // NFT allowances are a little more complicated because they have delegating spenders and approvedForAll.
        for (final var allowance : op.nftAllowances()) {
            // Only the owner can grant approvedForAll, so if approvedForAll is true, then the owner must sign.
            // If approvedForAll is false, and if there is a delegating spender, then they must sign. Otherwise,
            // the owner must sign.
            final var ownerId = allowance.owner();
            final boolean approvedForAll = allowance.approvedForAllOrElse(false);
            final var operatorId = approvedForAll ? ownerId : allowance.delegatingSpenderOrElse(ownerId);
            // Now that we know who should sign, if that account is not the payer, then we need to require that
            // key. If there is an error, then we need to use the appropriate error code depending on whether
            // the operator is the owner or the delegating spender.
            if (operatorId != null && !operatorId.equals(payerId)) {
                final var error = ownerId == operatorId ? INVALID_ALLOWANCE_OWNER_ID : INVALID_DELEGATING_SPENDER;
                context.requireKeyOrThrow(operatorId, error);
            }
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var payer = context.payer();
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);

        // Validate payer account exists
        final var payerAccount = TokenHandlerHelper.getIfUsable(
                payer, accountStore, context.expiryValidator(), INVALID_PAYER_ACCOUNT_ID);

        // Validate the transaction body fields that include state or configuration.
        validateSemantics(context, payerAccount, accountStore);

        // Apply all changes to the state modifications. We need to look up payer for each modification, since payer
        // would have been modified by a previous allowance change
        approveAllowance(context, payer, accountStore);
    }
    /**
     * Validate the transaction body fields that include state or configuration.
     * @param context the handle context
     * @param payerAccount the payer account
     * @param accountStore the account store
     */
    private void validateSemantics(
            @NonNull final HandleContext context,
            @NonNull final Account payerAccount,
            @NonNull final ReadableAccountStore accountStore) {
        requireNonNull(context);
        requireNonNull(payerAccount);
        requireNonNull(accountStore);

        allowanceValidator.validate(context, payerAccount, accountStore);
    }

    /**
     * Apply all changes to the state modifications for crypto, token, and nft allowances.
     * @param context the handle context
     * @param payerId the payer account id
     * @param accountStore the account store
     * @throws HandleException if there is an error applying the changes
     */
    private void approveAllowance(
            @NonNull final HandleContext context,
            @NonNull final AccountID payerId,
            @NonNull final WritableAccountStore accountStore)
            throws HandleException {
        requireNonNull(context);
        requireNonNull(payerId);
        requireNonNull(accountStore);

        final var op = context.body().cryptoApproveAllowanceOrThrow();
        final var cryptoAllowances = op.cryptoAllowances();
        final var tokenAllowances = op.tokenAllowances();
        final var nftAllowances = op.nftAllowances();

        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        final var storeFactory = context.storeFactory();
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var uniqueTokenStore = storeFactory.writableStore(WritableNftStore.class);

        /* --- Apply changes to state --- */
        final var allowanceMaxAccountLimit = hederaConfig.allowancesMaxAccountLimit();
        final var expiryValidator = context.expiryValidator();
        applyCryptoAllowances(cryptoAllowances, payerId, accountStore, allowanceMaxAccountLimit, expiryValidator);
        applyFungibleTokenAllowances(tokenAllowances, payerId, accountStore, allowanceMaxAccountLimit, expiryValidator);
        applyNftAllowances(
                nftAllowances,
                payerId,
                accountStore,
                tokenStore,
                uniqueTokenStore,
                allowanceMaxAccountLimit,
                expiryValidator);
    }

    /**
     * Applies all changes needed for Crypto allowances from the transaction.
     * If the spender already has an allowance, the allowance value will be replaced with values
     * from transaction. If the amount specified is 0, the allowance will be removed.
     *
     * @param cryptoAllowances         the list of crypto allowances
     * @param payerId                  the payer account id
     * @param accountStore             the account store
     * @param allowanceMaxAccountLimit the {@link HederaConfig}
     * @param expiryValidator          the expiry validator
     */
    private void applyCryptoAllowances(
            @NonNull final List<CryptoAllowance> cryptoAllowances,
            @NonNull final AccountID payerId,
            @NonNull final WritableAccountStore accountStore,
            final int allowanceMaxAccountLimit,
            @NonNull final ExpiryValidator expiryValidator) {
        requireNonNull(cryptoAllowances);
        requireNonNull(payerId);
        requireNonNull(accountStore);

        for (final var allowance : cryptoAllowances) {
            final var owner = allowance.owner();
            final var spender = allowance.spenderOrThrow();
            final var effectiveOwner = getEffectiveOwnerAccount(owner, payerId, accountStore, expiryValidator);
            final var mutableAllowances = new ArrayList<>(effectiveOwner.cryptoAllowances());

            final var amount = allowance.amount();

            updateCryptoAllowance(mutableAllowances, amount, spender);
            final var copy = effectiveOwner
                    .copyBuilder()
                    .cryptoAllowances(mutableAllowances)
                    .build();
            // Only when amount is greater than 0 we add or modify existing allowances.
            // When removing existing allowances no need to check this.
            if (amount > 0) {
                validateAllowanceLimit(copy, allowanceMaxAccountLimit);
            }
            accountStore.put(copy);
        }
    }

    /**
     * Updates the crypto allowance amount if the allowance exists, otherwise adds a new allowance.
     * If the amount is zero removes the allowance if it exists in the list.
     * @param mutableAllowances the list of mutable allowances of owner
     * @param amount the amount
     * @param spenderId the spender id
     */
    private void updateCryptoAllowance(
            final List<AccountCryptoAllowance> mutableAllowances, final long amount, final AccountID spenderId) {
        final var newAllowanceBuilder = AccountCryptoAllowance.newBuilder().spenderId(spenderId);
        // get the index of the allowance with same spender in existing list
        final var index = lookupSpender(mutableAllowances, spenderId);
        // If given amount is zero, if the element exists remove it, otherwise do nothing
        if (amount == 0) {
            if (index != -1) {
                // If amount is 0, remove the allowance
                mutableAllowances.remove(index);
            }
            return;
        }
        if (index != -1) {
            mutableAllowances.set(index, newAllowanceBuilder.amount(amount).build());
        } else {
            mutableAllowances.add(newAllowanceBuilder.amount(amount).build());
        }
    }

    /**
     * Applies all changes needed for fungible token allowances from the transaction. If the key
     * {token, spender} already has an allowance, the allowance value will be replaced with values
     * from transaction.
     * @param tokenAllowances          the list of token allowances
     * @param payerId                  the payer account id
     * @param accountStore             the account store
     * @param allowanceMaxAccountLimit the {@link HederaConfig} allowance max account limit
     * @param expiryValidator          the expiry validator
     */
    private void applyFungibleTokenAllowances(
            @NonNull final List<TokenAllowance> tokenAllowances,
            @NonNull final AccountID payerId,
            @NonNull final WritableAccountStore accountStore,
            final int allowanceMaxAccountLimit,
            @NonNull final ExpiryValidator expiryValidator) {
        for (final var allowance : tokenAllowances) {
            final var owner = allowance.owner();
            final var amount = allowance.amount();
            final var tokenId = allowance.tokenIdOrThrow();
            final var spender = allowance.spenderOrThrow();

            final var effectiveOwner = getEffectiveOwnerAccount(owner, payerId, accountStore, expiryValidator);
            final var mutableTokenAllowances = new ArrayList<>(effectiveOwner.tokenAllowances());

            updateTokenAllowance(mutableTokenAllowances, amount, spender, tokenId);
            final var copy = effectiveOwner
                    .copyBuilder()
                    .tokenAllowances(mutableTokenAllowances)
                    .build();
            // Only when amount is greater than 0 we add or modify existing allowances.
            // When removing existing allowances no need to check this.
            if (amount > 0) {
                validateAllowanceLimit(copy, allowanceMaxAccountLimit);
            }
            accountStore.put(copy);
        }
    }

    /**
     * Updates the token allowance amount if the allowance for given tokenNuma dn spenderNum exists,
     * otherwise adds a new allowance.
     * If the amount is zero removes the allowance if it exists in the list
     * @param mutableAllowances the list of mutable allowances of owner
     * @param amount the amount
     * @param spenderId the spender number
     * @param tokenId the token number
     */
    private void updateTokenAllowance(
            final List<AccountFungibleTokenAllowance> mutableAllowances,
            final long amount,
            final AccountID spenderId,
            final TokenID tokenId) {
        final var newAllowanceBuilder =
                AccountFungibleTokenAllowance.newBuilder().spenderId(spenderId).tokenId(tokenId);
        final var index = lookupSpenderAndToken(mutableAllowances, spenderId, tokenId);
        // If given amount is zero, if the element exists remove it
        if (amount == 0) {
            if (index != -1) {
                mutableAllowances.remove(index);
            }
            return;
        }
        if (index != -1) {
            mutableAllowances.set(index, newAllowanceBuilder.amount(amount).build());
        } else {
            mutableAllowances.add(newAllowanceBuilder.amount(amount).build());
        }
    }

    /**
     * Applies all changes needed for NFT allowances from the transaction. If the key{tokenNum,
     * spenderNum} doesn't exist in the map the allowance will be inserted. If the key exists,
     * existing allowance values will be replaced with new allowances given in operation
     *
     * @param nftAllowances            the list of nft allowances
     * @param payerId                  the payer account id
     * @param accountStore             the account store
     * @param tokenStore               the token store
     * @param uniqueTokenStore         the unique token store
     * @param allowanceMaxAccountLimit the {@link HederaConfig} config allowance max account limit
     * @param expiryValidator          the expiry validator
     */
    protected void applyNftAllowances(
            final List<NftAllowance> nftAllowances,
            @NonNull final AccountID payerId,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final WritableNftStore uniqueTokenStore,
            final int allowanceMaxAccountLimit,
            @NonNull final ExpiryValidator expiryValidator) {
        for (final var allowance : nftAllowances) {
            final var owner = allowance.owner();
            final var tokenId = allowance.tokenIdOrThrow();
            final var spender = allowance.spenderOrThrow();

            final var effectiveOwner = getEffectiveOwnerAccount(owner, payerId, accountStore, expiryValidator);
            final var mutableNftAllowances = new ArrayList<>(effectiveOwner.approveForAllNftAllowances());

            if (allowance.hasApprovedForAll()) {
                final var approveForAllAllowance = AccountApprovalForAllAllowance.newBuilder()
                        .tokenId(tokenId)
                        .spenderId(spender)
                        .build();

                if (Boolean.TRUE.equals(allowance.approvedForAll())) {
                    if (!mutableNftAllowances.contains(approveForAllAllowance)) {
                        mutableNftAllowances.add(approveForAllAllowance);
                    }
                } else {
                    mutableNftAllowances.remove(approveForAllAllowance);
                }
                final var copy = effectiveOwner
                        .copyBuilder()
                        .approveForAllNftAllowances(mutableNftAllowances)
                        .build();
                validateAllowanceLimit(copy, allowanceMaxAccountLimit);
                accountStore.put(copy);
            }
            // Update spender on all these Nfts to the new spender
            updateSpender(tokenStore, uniqueTokenStore, effectiveOwner, spender, tokenId, allowance.serialNumbers());
        }
    }

    /**
     * Updates the spender of the given NFTs to the new spender.
     * @param tokenStore the token store
     * @param uniqueTokenStore the unique token store
     * @param owner the owner account
     * @param spenderId the spender account id
     * @param tokenId the token id
     * @param serialNums the serial numbers
     */
    public void updateSpender(
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final WritableNftStore uniqueTokenStore,
            @NonNull final Account owner,
            @NonNull final AccountID spenderId,
            @NonNull final TokenID tokenId,
            @NonNull final List<Long> serialNums) {
        if (serialNums.isEmpty()) {
            return;
        }

        final var serialsSet = new HashSet<>(serialNums);
        for (final var serialNum : serialsSet) {
            final var nftId =
                    NftID.newBuilder().serialNumber(serialNum).tokenId(tokenId).build();
            final var nft = TokenHandlerHelper.getIfUsable(nftId, uniqueTokenStore);
            final var token = TokenHandlerHelper.getIfUsable(
                    tokenId, tokenStore, TokenHandlerHelper.TokenValidations.PERMIT_PAUSED);

            final AccountID accountOwner = owner.accountId();
            validateTrue(isValidOwner(nft, accountOwner, token), SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
            final var copy = nft.copyBuilder().spenderId(spenderId).build();
            uniqueTokenStore.put(copy);
        }
    }

    /**
     * Returns the index of the allowance with the given spender in the list if it exists,
     * otherwise returns -1.
     * @param ownerAllowances list of allowances
     * @param spenderNum spender account number
     * @return index of the allowance if it exists, otherwise -1
     */
    private int lookupSpender(final List<AccountCryptoAllowance> ownerAllowances, final AccountID spenderNum) {
        for (int i = 0; i < ownerAllowances.size(); i++) {
            final var allowance = ownerAllowances.get(i);
            if (allowance.spenderId().equals(spenderNum)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the index of the allowance  with the given spender and token in the list if it exists,
     * otherwise returns -1.
     * @param ownerAllowances list of allowances
     * @param spenderId spender account number
     * @param tokenId token number
     * @return index of the allowance if it exists, otherwise -1
     */
    private int lookupSpenderAndToken(
            final List<AccountFungibleTokenAllowance> ownerAllowances,
            final AccountID spenderId,
            final TokenID tokenId) {
        for (int i = 0; i < ownerAllowances.size(); i++) {
            final var allowance = ownerAllowances.get(i);
            if (allowance.spenderId().equals(spenderId) && allowance.tokenId().equals(tokenId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the effective owner account. If the owner is not present or owner is same as payer.
     * Since we are modifying the payer account in the same transaction for each allowance if owner is not specified,
     * we need to get the payer account each time from the modifications map.
     *
     * @param owner           owner of the allowance
     * @param payerId         payer of the transaction
     * @param accountStore    account store
     * @param expiryValidator the expiry validator
     * @return effective owner account
     */
    private static Account getEffectiveOwnerAccount(
            @Nullable final AccountID owner,
            @NonNull final AccountID payerId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator) {
        final var ownerNum = owner != null ? owner.accountNumOrElse(0L) : 0L;
        if (ownerNum == 0 || ownerNum == payerId.accountNumOrThrow()) {
            // The payer would have been modified in the same transaction for previous allowances
            // So, get it from modifications map.
            return accountStore.getAccountById(payerId);
        } else {
            // If owner is in modifications get the modified account from state
            return TokenHandlerHelper.getIfUsable(owner, accountStore, expiryValidator, INVALID_ALLOWANCE_OWNER_ID);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.cryptoApproveAllowanceOrThrow();
        final var accountStore = feeContext.readableStore(ReadableAccountStore.class);

        final var currentSecond =
                body.transactionIDOrThrow().transactionValidStartOrThrow().seconds();
        final var account = accountStore.getAccountById(feeContext.payer());

        final var currentExpiry = account == null ? currentSecond : account.expirationSecond();
        final long lifeTime = ESTIMATOR_UTILS.relativeLifetime(currentSecond, currentExpiry);
        // If the value is being adjusted instead of inserting a new entry , the fee charged will be
        // slightly less than the base price
        final var adjustedBytes = getNewBytes(body.cryptoApproveAllowanceOrThrow(), account);
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction(bytesUsedInTxn(op))
                .addRamByteSeconds(adjustedBytes > 0 ? (adjustedBytes * lifeTime) : 0)
                .calculate();
    }

    /**
     * Gets total bytes used in transaction.
     * @param op the crypto approve allowance transaction body
     * @return the total bytes used in transaction
     */
    private int bytesUsedInTxn(final CryptoApproveAllowanceTransactionBody op) {
        return op.cryptoAllowances().size() * CRYPTO_ALLOWANCE_SIZE
                + op.tokenAllowances().size() * TOKEN_ALLOWANCE_SIZE
                + op.nftAllowances().size() * NFT_ALLOWANCE_SIZE
                + countSerials(op.nftAllowances()) * LONG_SIZE;
    }

    /**
     * Gets the new bytes that will be added to state from the transaction, if it is successful compared to
     * what is already present in state.
     * @param op the crypto approve allowance transaction body
     * @param account the account existing in state
     * @return the new bytes that will be added to state
     */
    private long getNewBytes(final CryptoApproveAllowanceTransactionBody op, final Account account) {
        final long newCryptoKeys =
                getChangedCryptoKeys(op.cryptoAllowances(), account == null ? emptyList() : account.cryptoAllowances());
        final long newTokenKeys =
                getChangedTokenKeys(op.tokenAllowances(), account == null ? emptyList() : account.tokenAllowances());
        final long newApproveForAllNfts = getChangedNftKeys(
                op.nftAllowances(), account == null ? emptyList() : account.approveForAllNftAllowances());

        return newCryptoKeys * CRYPTO_ALLOWANCE_SIZE
                + newTokenKeys * TOKEN_ALLOWANCE_SIZE
                + newApproveForAllNfts * NFT_ALLOWANCE_SIZE;
    }

    /**
     * Gets the number of new crypto allowances that will be added to state from the transaction.
     * @param newAllowances the list of new crypto allowances
     * @param existingAllowances the list of existing crypto allowances
     * @return the number of new crypto allowances that will be added to state from the transaction
     */
    private int getChangedCryptoKeys(
            final List<CryptoAllowance> newAllowances, final List<AccountCryptoAllowance> existingAllowances) {
        int counter = 0;
        final var existingSpenders = existingAllowances.stream()
                .map(AccountCryptoAllowance::spenderId)
                .collect(Collectors.toSet());
        final var newSpenders = new HashSet<AccountID>();
        for (var key : newAllowances) {
            if (!existingSpenders.contains(key.spender()) && !newSpenders.contains(key.spender())) {
                newSpenders.add(key.spender());
                counter++;
            }
        }
        return counter;
    }

    /**
     * Gets the number of new token allowances that will be added to state from the transaction.
     * @param newAllowances the list of new token allowances
     * @param existingAllowances the list of existing token allowances
     * @return the number of new token allowances that will be added to state from the transaction
     */
    private int getChangedTokenKeys(
            final List<TokenAllowance> newAllowances, final List<AccountFungibleTokenAllowance> existingAllowances) {
        int counter = 0;
        final var existingKeys = existingAllowances.stream()
                .map(key -> Pair.of(key.tokenId(), key.spenderId()))
                .collect(Collectors.toSet());
        final var newKeys = new HashSet<Pair<TokenID, AccountID>>();
        for (final var key : newAllowances) {
            final var newKey = Pair.of(key.tokenId(), key.spender());
            if (!existingKeys.contains(newKey) && !newKeys.contains(newKey)) {
                newKeys.add(newKey);
                counter++;
            }
        }
        return counter;
    }

    /**
     * Gets the number of new approveForAllNftAllowances that will be added to state from the transaction.
     * @param newAllowances the list of new nft allowances
     * @param existingAllowances the list of existing nft allowances
     * @return the number of new approveForAllNftAllowances that will be added to state from the transaction
     */
    private int getChangedNftKeys(
            final List<NftAllowance> newAllowances, final List<AccountApprovalForAllAllowance> existingAllowances) {
        int counter = 0;
        final var existingKeys = existingAllowances.stream()
                .map(key -> Pair.of(key.tokenId(), key.spenderId()))
                .collect(Collectors.toSet());
        final var newKeys = new HashSet<Pair<TokenID, AccountID>>();
        for (final var key : newAllowances) {
            final var newKey = Pair.of(key.tokenId(), key.spender());
            if (!existingKeys.contains(newKey) && !newKeys.contains(newKey)) {
                newKeys.add(newKey);
                counter++;
            }
        }
        return counter;
    }

    /**
     * Counts the number of serials in the list of nft allowances. It doesn't consider duplicates.
     * @param nftAllowancesList the list of nft allowances
     * @return the number of serials
     */
    private int countSerials(final List<NftAllowance> nftAllowancesList) {
        int totalSerials = 0;
        for (var allowance : nftAllowancesList) {
            totalSerials += allowance.serialNumbers().size();
        }
        return totalSerials;
    }
}
