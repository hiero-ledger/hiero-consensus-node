// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNEXPECTED_TOKEN_DECIMALS;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts all fungible token changes from CryptoTransfer into state's modifications map.
 */
public class AdjustFungibleTokenChangesStep extends BaseTokenHandler implements TransferStep {

    private final List<TokenTransferList> tokenTransferLists;
    private final AccountID topLevelPayer;

    /**
     * Constructs the step with token transfer lists and the payer account.
     * @param tokenTransferLists the token transfer lists
     * @param topLevelPayer the payer account
     */
    public AdjustFungibleTokenChangesStep(
            @NonNull final List<TokenTransferList> tokenTransferLists, @NonNull final AccountID topLevelPayer) {
        requireNonNull(tokenTransferLists);
        requireNonNull(topLevelPayer);

        this.tokenTransferLists = tokenTransferLists;
        this.topLevelPayer = topLevelPayer;
    }

    @Override
    public void doIn(@NonNull final TransferContext transferContext) {
        requireNonNull(transferContext);

        final var handleContext = transferContext.getHandleContext();
        final var storeFactory = handleContext.storeFactory();
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);

        // two maps for aggregating the changes to the token balances and allowances.
        final Map<EntityIDPair, Long> aggregatedFungibleTokenChanges = new LinkedHashMap<>();
        final Map<EntityIDPair, Long> allowanceTransfers = new LinkedHashMap<>();

        // Look at all fungible token transfers and put into aggregatedFungibleTokenChanges map.
        // Also, put any transfers happening with allowances in allowanceTransfers map.
        for (final var transfers : tokenTransferLists) {
            final var tokenId = transfers.tokenOrThrow();
            final var token = TokenHandlerHelper.getIfUsable(tokenId, tokenStore);

            if (token.tokenType() == TokenType.FUNGIBLE_COMMON && transfers.hasExpectedDecimals()) {
                validateTrue(token.decimals() == transfers.expectedDecimalsOrThrow(), UNEXPECTED_TOKEN_DECIMALS);
            }

            for (final var aa : transfers.transfers()) {
                validateTrue(
                        token.tokenType() == TokenType.FUNGIBLE_COMMON,
                        ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON);

                final var accountId = aa.accountIDOrThrow();
                getIfUsable(accountId, accountStore, handleContext.expiryValidator(), INVALID_ACCOUNT_ID);
                final var pair = new EntityIDPair(accountId, tokenId);

                // Validate freeze status and kyc granted
                final var accountID = aa.accountIDOrThrow();
                final var tokenRel = getIfUsable(accountID, tokenId, tokenRelStore);
                validateNotFrozenAndKycOnRelation(tokenRel);

                // Add the amount to the aggregatedFungibleTokenChanges map.
                // If the (accountId, tokenId) pair doesn't exist in the map, add it.
                // Else, update the aggregated transfer amount
                aggregatedFungibleTokenChanges.merge(pair, aa.amount(), Long::sum);

                // If the transfer is happening with an allowance,
                // add it to the allowanceTransfers map.
                // If the accountId tokenId pair doesn't exist in the map, add it.
                // Else, update the aggregated transfer amount
                if (aa.isApproval() && aa.amount() < 0) {
                    allowanceTransfers.merge(pair, aa.amount(), Long::sum);
                }
            }
        }

        modifyAggregatedAllowances(allowanceTransfers, accountStore, transferContext);
        modifyAggregatedTokenBalances(
                aggregatedFungibleTokenChanges, tokenRelStore, accountStore, transferContext.getAssessedCustomFees());
    }

    /**
     * Puts all the aggregated token allowances changes into the accountStore.
     *
     * @param allowanceTransfers - map of aggregated token allowances to be modified
     * @param accountStore - account store
     * @param transferContext - transfer context
     */
    private void modifyAggregatedAllowances(
            @NonNull final Map<EntityIDPair, Long> allowanceTransfers,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final TransferContext transferContext) {
        // Look at all the allowanceTransfers and adjust the allowances in the accountStore.
        for (final var entry : allowanceTransfers.entrySet()) {
            final var atPair = entry.getKey();
            final var amount = entry.getValue();
            final var accountId = atPair.accountIdOrThrow();
            final var tokenId = atPair.tokenIdOrThrow();

            final var account = getIfUsable(
                    accountId, accountStore, transferContext.getHandleContext().expiryValidator(), INVALID_ACCOUNT_ID);
            final var accountCopy = account.copyBuilder();

            final var tokenAllowances = new ArrayList<>(account.tokenAllowances());
            var haveExistingAllowance = false;
            for (int i = 0; i < tokenAllowances.size(); i++) {
                final var allowance = tokenAllowances.get(i);
                final var allowanceCopy = allowance.copyBuilder();
                // If isApproval flag is set then the spender account must have paid for the transaction.
                // The transfer list specifies the owner who granted allowance as sender
                // check if the allowances from the sender account has the payer account as spender
                if (topLevelPayer.equals(allowance.spenderId()) && tokenId.equals(allowance.tokenId())) {
                    haveExistingAllowance = true;
                    final var newAllowanceAmount = allowance.amount() + amount;
                    validateTrue(newAllowanceAmount >= 0, AMOUNT_EXCEEDS_ALLOWANCE);
                    allowanceCopy.amount(newAllowanceAmount);
                    if (newAllowanceAmount != 0) {
                        tokenAllowances.set(i, allowanceCopy.build());
                    } else {
                        tokenAllowances.remove(i);
                    }
                    break;
                }
            }
            validateTrue(haveExistingAllowance, SPENDER_DOES_NOT_HAVE_ALLOWANCE);
            accountCopy.tokenAllowances(tokenAllowances);
            accountStore.put(accountCopy.build());
        }
    }

    /**
     * Puts all the aggregated token balances changes into the tokenRelStore and accountStore.
     *
     * @param aggregatedFungibleTokenChanges - map of aggregated token balances to be modified
     * @param tokenRelStore - token relation store
     * @param accountStore - account store
     * @param assessedCustomFees - list of assessed custom fees in the transfer context
     */
    private void modifyAggregatedTokenBalances(
            @NonNull final Map<EntityIDPair, Long> aggregatedFungibleTokenChanges,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final List<AssessedCustomFee> assessedCustomFees) {
        // Look at all the aggregatedFungibleTokenChanges and adjust the balances in the tokenRelStore.
        for (final var entry : aggregatedFungibleTokenChanges.entrySet()) {
            final var atPair = entry.getKey();
            final var amount = entry.getValue();
            final var rel = getIfUsable(atPair.accountIdOrThrow(), atPair.tokenIdOrThrow(), tokenRelStore);
            final var account = requireNonNull(accountStore.get(atPair.accountIdOrThrow()));
            try {
                adjustBalance(rel, account, amount, tokenRelStore, accountStore);
            } catch (HandleException e) {
                // Whenever mono-service assessed a fixed fee to an account, it would
                // update the "metadata" of that pending balance change to use
                // INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE instead of
                // INSUFFICIENT_TOKEN_BALANCE in the case of an insufficient balance.
                // We don't have an equivalent place to store such "metadata" in the
                // mod-service implementation; so instead if INSUFFICIENT_TOKEN_BALANCE
                // happens, we check if there were any custom fee payments that could
                // have contributed to the insufficient balance, and translate the
                // error to INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE if so.
                if (e.getStatus() == INSUFFICIENT_TOKEN_BALANCE
                        && effectivePaymentWasMade(rel.accountIdOrThrow(), rel.tokenIdOrThrow(), assessedCustomFees)) {
                    throw new HandleException(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
                }
                throw e;
            }
        }
    }

    private boolean effectivePaymentWasMade(
            @NonNull final AccountID payer,
            @NonNull final TokenID denom,
            @NonNull final List<AssessedCustomFee> assessedCustomFees) {
        for (final var fee : assessedCustomFees) {
            if (denom.equals(fee.tokenId()) && fee.effectivePayerAccountId().contains(payer)) {
                return true;
            }
        }
        return false;
    }
}
