// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_DELETE_ALLOWANCE_SIZE;
import static com.hedera.node.app.service.token.impl.validators.AllowanceValidator.isValidOwner;
import static com.hedera.node.app.service.token.impl.validators.ApproveAllowanceValidator.getEffectiveOwner;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.DeleteAllowanceValidator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FeesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_DELETE_ALLOWANCE}. Crypto delete allowance is used to
 * delete an existing allowance for a given NFT serial number.
 */
@Singleton
public class CryptoDeleteAllowanceHandler implements TransactionHandler {
    private final DeleteAllowanceValidator deleteAllowanceValidator;

    /**
     * Default constructor for injection.
     * @param validator the validator for validating a delete allowance transaction
     */
    @Inject
    public CryptoDeleteAllowanceHandler(@NonNull final DeleteAllowanceValidator validator) {
        this.deleteAllowanceValidator = validator;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.cryptoDeleteAllowanceOrThrow();
        final var allowances = op.nftAllowances();
        validateTruePreCheck(!allowances.isEmpty(), EMPTY_ALLOWANCES);
        for (final var allowance : allowances) {
            mustExist(allowance.tokenId(), INVALID_TOKEN_ID);
            validateTruePreCheck(!allowance.serialNumbers().isEmpty(), EMPTY_ALLOWANCES);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoDeleteAllowanceOrThrow();
        // Every owner whose allowances are being removed should sign (or the payer, if there is no owner)
        for (final var allowance : op.nftAllowances()) {
            if (allowance.hasOwner()) {
                final var store = context.createStore(ReadableAccountStore.class);
                final var ownerId = allowance.ownerOrThrow();
                final var owner = store.getAccountById(ownerId);
                final var approvedForAll = owner.approveForAllNftAllowances().stream()
                        .anyMatch(approveForAll -> approveForAll.tokenId().equals(allowance.tokenId())
                                && approveForAll.spenderId().equals(context.payer()));
                if (!context.payer().equals(ownerId) && !approvedForAll) {
                    context.requireKeyOrThrow(ownerId, INVALID_ALLOWANCE_OWNER_ID);
                }
            }
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var payer = context.payer();

        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        // validate payer account exists
        final var payerAccount = TokenHandlerHelper.getIfUsable(
                payer, accountStore, context.expiryValidator(), INVALID_PAYER_ACCOUNT_ID);

        // validate the transaction body fields that include state or configuration
        // We can use payerAccount for validations since it's not mutated in validateSemantics
        validateSemantics(context, payerAccount, accountStore);

        // Apply all changes to the state modifications
        deleteAllowance(context, payerAccount, accountStore);
    }

    /**
     * Deletes allowance for the given nft serials.
     * Clears spender on the provided nft serials if the owner owns the serials.
     * If owner is not specified payer is considered as the owner.
     * If the owner doesn't own these serials throws an exception.
     * @param context handle context
     * @param payer payer for the transaction
     * @param accountStore account store
     * @throws HandleException if any of the nft serials are not owned by owner
     */
    private void deleteAllowance(
            @NonNull final HandleContext context,
            @NonNull final Account payer,
            @NonNull final WritableAccountStore accountStore)
            throws HandleException {
        requireNonNull(context);
        requireNonNull(payer);
        requireNonNull(accountStore);

        final var op = context.body().cryptoDeleteAllowanceOrThrow();
        final var nftAllowances = op.nftAllowances();

        final var storeFactory = context.storeFactory();
        final var nftStore = storeFactory.writableStore(WritableNftStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);

        deleteNftSerials(nftAllowances, payer, accountStore, tokenStore, nftStore, context.expiryValidator());
    }

    /**
     * Clear spender on the provided nft serials. If the owner is not provided in any allowance,
     * considers payer of the transaction as owner while checking if nft is owned by owner.
     * @param nftAllowances given nftAllowances
     * @param payerAccount payer for the transaction
     * @param accountStore account Store
     * @param tokenStore token Store
     * @param nftStore nft Store
     */
    private void deleteNftSerials(
            final List<NftRemoveAllowance> nftAllowances,
            final Account payerAccount,
            final WritableAccountStore accountStore,
            final WritableTokenStore tokenStore,
            final WritableNftStore nftStore,
            @NonNull final ExpiryValidator expiryValidator)
            throws HandleException {
        if (nftAllowances.isEmpty()) {
            return;
        }
        for (final var allowance : nftAllowances) {
            final var serialNums = allowance.serialNumbers();
            final var tokenId = allowance.tokenIdOrElse(TokenID.DEFAULT);
            // If owner is not provided in allowance, consider payer as owner
            final var owner = getEffectiveOwner(allowance.owner(), payerAccount, accountStore, expiryValidator);
            final var token = tokenStore.get(tokenId);
            for (final var serial : serialNums) {
                final var nftId =
                        NftID.newBuilder().serialNumber(serial).tokenId(tokenId).build();
                final var nft = TokenHandlerHelper.getIfUsable(nftId, nftStore);

                final AccountID accountOwner = owner.accountId();
                validateTrue(isValidOwner(nft, accountOwner, token), SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);

                // Null out spender on the nft
                nftStore.put(nft.copyBuilder().spenderId((AccountID) null).build());
            }
        }
    }

    /**
     * Validate the transaction body fields that include state or configuration.
     * We can use payerAccount for validations since it's not mutated in validateSemantics.
     * @param context the context of the transaction
     * @param payerAccount the account of the payer
     * @param accountStore the account store
     */
    private void validateSemantics(
            @NonNull final HandleContext context, final Account payerAccount, final ReadableAccountStore accountStore) {
        requireNonNull(context);
        requireNonNull(payerAccount);
        requireNonNull(accountStore);

        final var op = context.body().cryptoDeleteAllowanceOrThrow();
        final var nftAllowances = op.nftAllowances();

        deleteAllowanceValidator.validate(context, nftAllowances, payerAccount, accountStore);
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.cryptoDeleteAllowanceOrThrow();
        final var feesConfig= feeContext.configuration().getConfigData(FeesConfig.class);
        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction((long) op.nftAllowances().size() * NFT_DELETE_ALLOWANCE_SIZE
                        + (long) countNftDeleteSerials(op.nftAllowances()) * LONG_SIZE)
                .calculate();
        if(feesConfig.simpleFeesEnabled()) {
            return new Fees(0,0,0);
        } else {
            return oldFees;
        }
    }

    private int countNftDeleteSerials(final List<NftRemoveAllowance> nftAllowancesList) {
        int totalSerials = 0;
        for (var allowance : nftAllowancesList) {
            totalSerials += allowance.serialNumbers().size();
        }
        return totalSerials;
    }
}
