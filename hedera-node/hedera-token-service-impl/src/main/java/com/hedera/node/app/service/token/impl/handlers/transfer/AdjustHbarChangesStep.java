// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adjusts the hbar balances and token allowances for the accounts involved in the transfer.
 */
public class AdjustHbarChangesStep extends BaseTokenHandler implements TransferStep {
    private final CryptoTransferTransactionBody op;
    private final AccountID topLevelPayer;

    /**
     * Constructs the step with the operation and the top level payer account.
     * @param op - the operation
     * @param topLevelPayer - the top level payer account
     */
    public AdjustHbarChangesStep(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final AccountID topLevelPayer) {
        requireNonNull(op);
        requireNonNull(topLevelPayer);
        this.op = op;
        this.topLevelPayer = topLevelPayer;
    }

    @Override
    public void doIn(@NonNull final TransferContext transferContext) {
        requireNonNull(transferContext);

        final var accountStore =
                transferContext.getHandleContext().storeFactory().writableStore(WritableAccountStore.class);
        // Aggregate all the hbar balances from the changes. It also includes allowance transfer amounts
        final Map<AccountID, Long> netHbarTransfers = new LinkedHashMap<>();
        // Allowance transfers is only for negative amounts, it is used to reduce allowance for the spender
        final Map<AccountID, Long> allowanceTransfers = new LinkedHashMap<>();
        for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            netHbarTransfers.merge(aa.accountID(), aa.amount(), Long::sum);
            if (aa.isApproval() && aa.amount() < 0) {
                allowanceTransfers.merge(aa.accountID(), aa.amount(), Long::sum);
            }
        }

        modifyAggregatedTransfers(netHbarTransfers, accountStore, transferContext);
        modifyAggregatedAllowances(allowanceTransfers, accountStore, transferContext);
    }

    /**
     * Puts all the aggregated token allowances changes into the accountStore.
     * For isApproval flag to work the spender account who was granted allowance
     * should be the payer for the transaction.
     * @param allowanceTransfers - map of aggregated token allowances to be put into state
     * @param accountStore  - account store
     * @param transferContext - transfer context
     */
    private void modifyAggregatedAllowances(
            @NonNull final Map<AccountID, Long> allowanceTransfers,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final TransferContext transferContext) {
        for (final var entry : allowanceTransfers.entrySet()) {
            final var accountId = entry.getKey();
            final var amount = entry.getValue();

            final var ownerAccount = getIfUsable(
                    accountId, accountStore, transferContext.getHandleContext().expiryValidator(), INVALID_ACCOUNT_ID);
            final var accountCopy = ownerAccount.copyBuilder();

            final var cryptoAllowances = new ArrayList<>(ownerAccount.cryptoAllowances());
            var haveSpenderAllowance = false;

            for (int i = 0; i < cryptoAllowances.size(); i++) {
                final var allowance = cryptoAllowances.get(i);
                final var allowanceCopy = allowance.copyBuilder();
                // If isApproval flag is set then the spender account must have paid for the transaction.
                // The transfer list specifies the owner who granted allowance as sender
                // check if the allowances from the sender account has the payer account as spender
                if (topLevelPayer.equals(allowance.spenderId())) {
                    haveSpenderAllowance = true;
                    final var newAllowanceAmount = allowance.amount() + amount;
                    validateTrue(newAllowanceAmount >= 0, AMOUNT_EXCEEDS_ALLOWANCE);

                    allowanceCopy.amount(newAllowanceAmount);
                    if (newAllowanceAmount != 0) {
                        cryptoAllowances.set(i, allowanceCopy.build());
                    } else {
                        cryptoAllowances.remove(i);
                    }
                    break;
                }
            }
            validateTrue(haveSpenderAllowance, SPENDER_DOES_NOT_HAVE_ALLOWANCE);
            accountCopy.cryptoAllowances(cryptoAllowances);
            accountStore.put(accountCopy.build());
        }
    }

    /**
     * Puts all the aggregated hbar balances changes into the accountStore.
     * @param netHbarTransfers - map of aggregated hbar balances to be put into state
     * @param accountStore - account store
     * @param transferContext - transfer context
     */
    private void modifyAggregatedTransfers(
            @NonNull final Map<AccountID, Long> netHbarTransfers,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final TransferContext transferContext) {
        for (final var entry : netHbarTransfers.entrySet()) {
            final var accountId = entry.getKey();
            final var amount = entry.getValue();
            final var account = getIfUsable(
                    accountId, accountStore, transferContext.getHandleContext().expiryValidator(), INVALID_ACCOUNT_ID);
            final var currentBalance = account.tinybarBalance();
            final var newBalance = currentBalance + amount;
            if (newBalance < 0) {
                final var assessedCustomFees = transferContext.getAssessedCustomFees();
                // Whenever mono-service assessed a fixed fee to an account, it would
                // update the "metadata" of that pending balance change to use
                // INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE instead of
                // INSUFFICIENT_ACCOUNT_BALANCE in the case of an insufficient balance.
                // We don't have an equivalent place to store such "metadata" in the
                // mod-service implementation; so instead if INSUFFICIENT_ACCOUNT_BALANCE
                // happens, we check if there were any custom fee payments that could
                // have contributed to the insufficient balance, and translate the
                // error to INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE if so.
                if (effectivePaymentWasMade(accountId, assessedCustomFees)) {
                    throw new HandleException(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
                }
            }
            validateTrue(newBalance >= 0, INSUFFICIENT_ACCOUNT_BALANCE);
            final var copy = account.copyBuilder();
            accountStore.put(copy.tinybarBalance(newBalance).build());
        }
    }

    /**
     * Checks if the effective payment was made by the payer already by checking the assessed custom fees.
     * @param payer - the payer accountId
     * @param assessedCustomFees - the assessed custom fees
     * @return true if the effective payment was made, false otherwise
     */
    private boolean effectivePaymentWasMade(
            @NonNull final AccountID payer, @NonNull final List<AssessedCustomFee> assessedCustomFees) {
        for (final var fee : assessedCustomFees) {
            if (fee.tokenId() == null && fee.effectivePayerAccountId().contains(payer)) {
                return true;
            }
        }
        return false;
    }
}
