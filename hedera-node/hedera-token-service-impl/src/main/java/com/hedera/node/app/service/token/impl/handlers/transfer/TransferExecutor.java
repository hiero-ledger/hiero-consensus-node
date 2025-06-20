// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.isStakingAccount;
import static com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor.OptionalKeyCheck.RECEIVER_KEY_IS_OPTIONAL;
import static com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor.OptionalKeyCheck.RECEIVER_KEY_IS_REQUIRED;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferValidationHelper.checkReceiver;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferValidationHelper.checkSender;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Service class that provides static methods for execution of Crypto transfer transaction. The main purpose of this
 * class is reusing the crypto transfer steps logic in to
 * {@link com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler} It also adds the possibility to separate
 * custom fee assessment steps from other steps (to prepay fees in case of pending airdrops)
 */
@Singleton
public class TransferExecutor extends BaseTokenHandler {
    private final CryptoTransferValidator validator;

    /**
     * Default constructor for injection.
     */
    @Inject
    public TransferExecutor(final CryptoTransferValidator validator) {
        // For Dagger injection
        this.validator = validator;
    }

    /**
     * Pre-handle for crypto transfer transaction.
     * @param context handle context
     * @param op transaction body
     * @throws PreCheckException if any error occurs during the process
     */
    protected void preHandle(PreHandleContext context, CryptoTransferTransactionBody op) throws PreCheckException {
        preHandle(context, op, OptionalKeyCheck.RECEIVER_KEY_IS_REQUIRED);
    }

    private void preHandle(
            @NonNull final PreHandleContext context,
            @NonNull final CryptoTransferTransactionBody op,
            @NonNull final OptionalKeyCheck receiverKeyCheck)
            throws PreCheckException {
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenTransfers = op.tokenTransfers();
        final var hbarTransfers = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();

        for (final var transfers : tokenTransfers) {
            final var tokenMeta = tokenStore.getTokenMeta(transfers.tokenOrElse(TokenID.DEFAULT));
            if (tokenMeta == null) throw new PreCheckException(INVALID_TOKEN_ID);
            checkFungibleTokenTransfers(transfers.transfers(), context, accountStore, false, receiverKeyCheck);
            checkNftTransfers(transfers.nftTransfers(), context, tokenMeta, op, accountStore, receiverKeyCheck);
        }

        checkFungibleTokenTransfers(hbarTransfers, context, accountStore, true);
    }

    /**
     * Pre-handle for airdrop transaction, that ignore receiver sign required check. Because airdrops to
     * receiver with signature required, should result in a pending airdrop or crypto transfer transaction depending
     * on association and signature.
     * @param context handle context
     * @param op transaction body
     * @throws PreCheckException if any error occurs during the process
     */
    protected void preHandleWithOptionalReceiverSignature(PreHandleContext context, CryptoTransferTransactionBody op)
            throws PreCheckException {
        preHandle(context, op, OptionalKeyCheck.RECEIVER_KEY_IS_OPTIONAL);
    }

    /**
     * Executes all crypto transfer steps.
     *
     * @param txn transaction body
     * @param transferContext transfer context
     * @param context handle context
     * @param recordBuilder record builder
     */
    protected void executeCryptoTransfer(
            TransactionBody txn,
            TransferContextImpl transferContext,
            HandleContext context,
            CryptoTransferStreamBuilder recordBuilder) {
        executeCryptoTransfer(txn, transferContext, context, recordBuilder, false);
    }

    /**
     * Execute crypto transfer transaction.
     *
     * @param txn transaction body
     * @param transferContext transfer context
     * @param context handle context
     * @param recordBuilder crypto transfer record builder
     * @param skipCustomFee should execute custom fee steps
     */
    protected void executeCryptoTransfer(
            TransactionBody txn,
            TransferContextImpl transferContext,
            HandleContext context,
            CryptoTransferStreamBuilder recordBuilder,
            boolean skipCustomFee) {
        final var topLevelPayer = context.payer();
        // Use the op with replaced aliases in further steps
        transferContext.validateHbarAllowances();

        // Replace all aliases in the transaction body with its account ids
        final var replacedOp = ensureAndReplaceAliasesInOp(txn, transferContext, context, validator);
        // Use the op with replaced aliases in further steps
        final var steps = decomposeIntoSteps(replacedOp, topLevelPayer, transferContext, skipCustomFee);
        for (final var step : steps) {
            // Apply all changes to the handleContext's States
            step.doIn(transferContext);
        }
        if (!transferContext.getAutomaticAssociations().isEmpty()) {
            transferContext.getAutomaticAssociations().forEach(recordBuilder::addAutomaticTokenAssociation);
        }
        if (!transferContext.getAssessedCustomFees().isEmpty()) {
            recordBuilder.assessedCustomFees(transferContext.getAssessedCustomFees());
        }
    }

    protected void executeAirdropCryptoTransfer(
            @NonNull final HandleContext context,
            @NonNull final List<TokenTransferList> tokenTransferList,
            @NonNull final CryptoTransferStreamBuilder recordBuilder) {
        var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(tokenTransferList)
                .build();

        final var syntheticCryptoTransferTxn =
                TransactionBody.newBuilder().cryptoTransfer(cryptoTransferBody).build();

        final var transferContext = new TransferContextImpl(context, cryptoTransferBody, true);

        // We should skip custom fee steps here, because they must be already prepaid
        executeCryptoTransferWithoutCustomFee(syntheticCryptoTransferTxn, transferContext, context, recordBuilder);
    }

    /**
     * <p>
     * Charges only the custom fees if any. Used when custom fees should be prepaid in
     * {@link com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler}
     * </p>
     *
     *
     * @param txn             transaction body
     * @param transferContext transfer context
     * @return transfer transaction body after custom fees assessment
     * <p>
     * Note : In case of fractional fee with {@code netOfTransfers = false}, the original transfer
     * body is updated. A new account-amount is added to represent fractional part of the original
     * value that need to be transferred to the fee collector.
     * </p>
     */
    protected CryptoTransferTransactionBody chargeCustomFeeForAirdrops(
            TransactionBody txn, TransferContextImpl transferContext) {
        final var customFeeStep = new CustomFeeAssessmentStep(txn.cryptoTransferOrThrow());
        var transferBodies = customFeeStep.assessCustomFees(transferContext);
        var topLevelPayer = transferContext.getHandleContext().payer();
        // we skip the origin (first) txn body,
        // so we can adjust balance changes, that are related ONLY to the custom fees
        for (int i = 1, n = transferBodies.size(); i < n; i++) {
            // adjust balances
            var adjustHbarChangesStep = new AdjustHbarChangesStep(transferBodies.get(i), topLevelPayer);
            adjustHbarChangesStep.doIn(transferContext);
            var adjustFungibleChangesStep =
                    new AdjustFungibleTokenChangesStep(transferBodies.get(i).tokenTransfers(), topLevelPayer);
            adjustFungibleChangesStep.doIn(transferContext);
        }
        return transferBodies.getFirst();
    }

    /**
     * Executes crypto transfer, but skip custom fee steps. Used when custom fees should be prepaid in
     * {@link com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler}
     *
     * @param txn             transaction body
     * @param transferContext transfer context
     * @param context         handle context
     * @param recordBuilder   record builder
     */
    protected void executeCryptoTransferWithoutCustomFee(
            TransactionBody txn,
            TransferContextImpl transferContext,
            HandleContext context,
            CryptoTransferStreamBuilder recordBuilder) {
        executeCryptoTransfer(txn, transferContext, context, recordBuilder, true);
    }

    /**
     * Ensures all aliases specified in the transfer exist. If the aliases are in receiver section, and don't exist
     * they will be auto-created. This step populates resolved aliases and number of auto creations in the
     * transferContext, which is used by subsequent steps and throttling.
     * It will also replace all aliases in the {@link CryptoTransferTransactionBody} with its account ids, so it will
     * be easier to process in next steps.
     * @param txn the given transaction body
     * @param transferContext the given transfer context
     * @param context the given handle context
     * @param validator crypto transfer validator
     * @return the replaced transaction body with all aliases replaced with its account ids
     * @throws HandleException if any error occurs during the process
     */
    private CryptoTransferTransactionBody ensureAndReplaceAliasesInOp(
            @NonNull final TransactionBody txn,
            @NonNull final TransferContextImpl transferContext,
            @NonNull final HandleContext context,
            @NonNull final CryptoTransferValidator validator)
            throws HandleException {
        final var op = txn.cryptoTransferOrThrow();

        // ensure all aliases exist, if not create then if receivers
        ensureExistenceOfAliasesOrCreate(op, transferContext);

        // replace all aliases with its account ids, so it will be easier to process in next steps
        final var replacedOp = new ReplaceAliasesWithIDsInOp().replaceAliasesWithIds(op, transferContext);
        // re-run pure checks on this op to see if there are no duplicates
        try {
            validator.pureChecks(replacedOp);
        } catch (PreCheckException e) {
            throw new HandleException(e.responseCode());
        }
        return replacedOp;
    }

    private void ensureExistenceOfAliasesOrCreate(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final TransferContextImpl transferContext) {
        final var ensureAliasExistence = new EnsureAliasesStep(op);
        ensureAliasExistence.doIn(transferContext);
    }

    /**
     * Decomposes a crypto transfer into a sequence of steps that can be executed in order.
     * Each step validates the preconditions needed from TransferContextImpl in order to perform its action.
     * Steps are as follows:
     * <ol>
     *     <li>(c,o)Ensure existence of alias-referenced accounts</li>
     *     <li>(+,c)Charge custom fees for token transfers</li>
     *     <li>(o)Ensure associations of token recipients</li>
     *     <li>(+)Do zero-sum hbar balance changes</li>
     *     <li>(+)Do zero-sum fungible token transfers</li>
     *     <li>(+)Change NFT owners</li>
     *     <li>(+,c)Pay staking rewards, possibly to previously unmentioned stakee accounts</li>
     * </ol>
     * LEGEND: '+' = creates new BalanceChange(s) from either the transaction body, custom fee schedule,
     * or staking reward situation
     *        'c' = updates an existing BalanceChange
     *        'o' = causes a side effect not represented as BalanceChange
     *
     * @param op              The crypto transfer transaction body
     * @param topLevelPayer   The payer of the transaction
     * @param transferContext The transfer context
     * @return A list of steps to execute
     */
    private List<TransferStep> decomposeIntoSteps(
            final CryptoTransferTransactionBody op,
            final AccountID topLevelPayer,
            final TransferContextImpl transferContext,
            boolean skipCustomFees) {
        final List<TransferStep> steps = new ArrayList<>();
        // Step 1: associate any token recipients that are not already associated and have
        // auto association slots open
        steps.add(new AssociateTokenRecipientsStep(op));
        // Step 2: Charge custom fees for token transfers
        final var customFeeStep = new CustomFeeAssessmentStep(op);

        List<CryptoTransferTransactionBody> txns = List.of(op);
        if (!skipCustomFees) {
            txns = customFeeStep.assessCustomFees(transferContext);
        }

        // The below steps should be doe for both custom fee assessed transaction in addition to
        // original transaction
        for (final var txn : txns) {
            steps.add(new AssociateTokenRecipientsStep(txn));
            // Step 3: Charge hbar transfers and also ones with isApproval. Modify the allowances map on account
            final var assessHbarTransfers = new AdjustHbarChangesStep(txn, topLevelPayer);
            steps.add(assessHbarTransfers);

            // Step 4: Charge token transfers with an approval. Modify the allowances map on account
            final var assessFungibleTokenTransfers =
                    new AdjustFungibleTokenChangesStep(txn.tokenTransfers(), topLevelPayer);
            steps.add(assessFungibleTokenTransfers);

            // Step 5: Change NFT owners and also ones with isApproval. Clear the spender on NFT.
            // Will be a no-op for every txn except possibly the first (i.e., the top-level txn).
            // This is because assessed custom fees never change NFT owners
            final var changeNftOwners = new NFTOwnersChangeStep(txn.tokenTransfers(), topLevelPayer);
            steps.add(changeNftOwners);
        }

        return steps;
    }

    private void checkFungibleTokenTransfers(
            @NonNull final List<AccountAmount> transfers,
            @NonNull final PreHandleContext ctx,
            @NonNull final ReadableAccountStore accountStore,
            final boolean hbarTransfer)
            throws PreCheckException {
        checkFungibleTokenTransfers(transfers, ctx, accountStore, hbarTransfer, RECEIVER_KEY_IS_REQUIRED);
    }

    /**
     * As part of pre-handle, checks that HBAR or fungible token transfers in the transfer list are plausible.
     *
     * @param transfers                The transfers to check
     * @param ctx                      The context we gather signing keys into
     * @param accountStore             The account store to use to look up accounts
     * @param hbarTransfer             Whether this is a hbar transfer. When HIP-583 is implemented, we can remove
     *                                 this argument.
     * @param receiverKeyCheck Since in airdrops receiver key is optional to sign the transaction, add it to
     *                         optional keys
     * @throws PreCheckException If the transaction is invalid
     */
    private void checkFungibleTokenTransfers(
            @NonNull final List<AccountAmount> transfers,
            @NonNull final PreHandleContext ctx,
            @NonNull final ReadableAccountStore accountStore,
            final boolean hbarTransfer,
            @NonNull final OptionalKeyCheck receiverKeyCheck)
            throws PreCheckException {
        // We're going to iterate over all the transfers in the transfer list. Each transfer is known as an
        // "account amount". Each of these represents the transfer of hbar INTO a single account or OUT of a
        // single account.
        for (final var accountAmount : transfers) {
            // Given an accountId, we need to look up the associated account.
            final var accountId = validateAccountID(accountAmount.accountIDOrElse(AccountID.DEFAULT), null);
            final var account = accountStore.getAliasedAccountById(accountId);
            final var isCredit = accountAmount.amount() > 0;
            final var isDebit = accountAmount.amount() < 0;
            if (account != null) {
                // This next code is not right, but we have it for compatibility until after we migrate
                // off the mono-service. Then we can fix this. In this logic, if the receiver account (the
                // one with the credit) doesn't have a key AND the value being sent is non-hbar fungible tokens,
                // then we fail with ACCOUNT_IS_IMMUTABLE. And if the account is being debited and has no key,
                // then we also fail with the same error. It should be that being credited value DOES NOT require
                // a key, unless `receiverSigRequired` is true.
                if (isStakingAccount(ctx.configuration(), account.accountId())
                        && (isDebit || (isCredit && !hbarTransfer))) {
                    // NOTE: should change to ACCOUNT_IS_IMMUTABLE after modularization
                    throw new PreCheckException(INVALID_ACCOUNT_ID);
                }

                // We only need signing keys for accounts that are being debited OR those being credited
                // but with receiverSigRequired set to true. If the account is being debited but "isApproval"
                // is set on the transaction, then we defer to the token transfer logic to determine if all
                // signing requirements were met ("isApproval" is a way for the client to say "I don't need a key
                // because I'm approved which you will see when you handle this transaction").
                if (isDebit && !accountAmount.isApproval()) {
                    // If the account is a hollow account, then we require a signature for it.
                    // It is possible that the hollow account has signed this transaction, in which case
                    // we need to finalize the hollow account by setting its key.
                    if (isHollow(account)) {
                        ctx.requireSignatureForHollowAccount(account);
                    } else {
                        ctx.requireKeyOrThrow(account.key(), INVALID_ACCOUNT_ID);
                    }

                } else if (isCredit && account.receiverSigRequired()) {
                    // Add receiver key as an optional key to sign for airdrops.
                    // If the receiver has not signed, we don't fail the transaction. Instead, it becomes a
                    // pending airdrops
                    if (receiverKeyCheck == RECEIVER_KEY_IS_OPTIONAL) {
                        ctx.optionalKey(account.keyOrThrow());
                    } else {
                        ctx.requireKeyOrThrow(account.key(), INVALID_TRANSFER_ACCOUNT_ID);
                    }
                }
            } else if (hbarTransfer) {
                // It is possible for the transfer to be valid even if the account is not found. For example, we
                // allow auto-creation of "hollow accounts" if you transfer value into an account *by alias* that
                // didn't previously exist. If that is not the case, then we fail because we couldn't find the
                // destination account.
                if (!isCredit || !isAlias(accountId)) {
                    // Interestingly, this means that if the transfer amount is exactly 0 and the account has a
                    // non-existent alias, then we fail.
                    throw new PreCheckException(INVALID_ACCOUNT_ID);
                }
            } else if (isDebit) {
                // All debited accounts must be valid
                throw new PreCheckException(INVALID_ACCOUNT_ID);
            }
        }
    }

    private void checkNftTransfers(
            final List<NftTransfer> nftTransfersList,
            final PreHandleContext meta,
            final ReadableTokenStore.TokenMetadata tokenMeta,
            final CryptoTransferTransactionBody op,
            final ReadableAccountStore accountStore,
            final OptionalKeyCheck receiverKeyCheck)
            throws PreCheckException {
        for (final var nftTransfer : nftTransfersList) {
            final var senderId = nftTransfer.senderAccountIDOrElse(AccountID.DEFAULT);
            validateAccountID(senderId, null);
            checkSender(senderId, nftTransfer, meta, accountStore);

            final var receiverId = nftTransfer.receiverAccountIDOrElse(AccountID.DEFAULT);
            validateAccountID(receiverId, null);
            checkReceiver(receiverId, senderId, nftTransfer, meta, tokenMeta, op, accountStore, receiverKeyCheck);
        }
    }

    /**
     * Enum to specify the receiver key check type. For airdrops, receiver key is optional to sign the transaction.
     * If the receiver has not signed, we don't fail the transaction. Instead, it becomes a pending airdrops.
     * For CryptoTransfer, receiver key is required to sign the transaction if receiverSigRequired is set to true.
     */
    public enum OptionalKeyCheck {
        RECEIVER_KEY_IS_OPTIONAL,
        RECEIVER_KEY_IS_REQUIRED
    }
}
