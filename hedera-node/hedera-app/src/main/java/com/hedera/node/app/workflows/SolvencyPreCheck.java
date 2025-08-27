// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.FEE_DIVISOR_FACTOR;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.OfferedFeeCheck;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.OfferedFeeCheck.CHECK_OFFERED_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.INGEST;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.InsufficientNetworkFeeException;
import com.hedera.node.app.spi.workflows.InsufficientNonFeeDebitsException;
import com.hedera.node.app.spi.workflows.InsufficientServiceFeeException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.validation.ExpiryValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Determines if the payer account set in the {@code TransactionID} is expected to be both willing and able to pay the
 * transaction fees.
 *
 * <p>For more details, please see
 * <a href="https://github.com/hashgraph/hedera-services/blob/master/docs/transaction-prechecks.md">...</a>
 */
@Singleton
public class SolvencyPreCheck {

    private final ExchangeRateManager exchangeRateManager;
    private final FeeManager feeManager;
    private final ExpiryValidation expiryValidation;
    private final Authorizer authorizer;

    @Inject
    public SolvencyPreCheck(
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final ExpiryValidation expiryValidation,
            @NonNull final Authorizer authorizer) {
        this.exchangeRateManager = requireNonNull(exchangeRateManager, "exchangeRateManager must not be null");
        this.feeManager = requireNonNull(feeManager, "feeManager must not be null");
        this.expiryValidation = requireNonNull(expiryValidation, "expiryValidation must not be null");
        this.authorizer = requireNonNull(authorizer, "authorizer must not be null");
    }

    /**
     * Reads the payer account from state and validates it.
     *
     * @param storeFactory the {@link ReadableStoreFactory} used to access readable state
     * @param accountID the {@link AccountID} of the payer
     * @throws PreCheckException if the payer account is invalid
     */
    @NonNull
    public Account getPayerAccount(@NonNull final ReadableStoreFactory storeFactory, @NonNull final AccountID accountID)
            throws PreCheckException {
        final var accountStore = storeFactory.getStore(ReadableAccountStore.class);
        final var account = accountStore.getAccountById(accountID);

        if (account == null) {
            throw new PreCheckException(ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND);
        }

        if (account.deleted()) {
            throw new PreCheckException(ResponseCodeEnum.PAYER_ACCOUNT_DELETED);
        }

        if (account.smartContract()) {
            throw new PreCheckException(ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND);
        }

        return account;
    }

    /**
     * Checks if the verified payer account of the given transaction can afford to cover its fees excluding service fees.
     *
     * @param txInfo the {@link TransactionInfo} to use during the check
     * @param account the {@link Account} with the balance to check
     * @param fees the fees to use for the check
     * @param workflowCheck if INGEST, the check will be performed during an ingest workflow.
     * @throws InsufficientBalanceException if the payer account cannot afford the fees. The exception will have a
     * status of {@code INSUFFICIENT_TX_FEE} and the fee amount that would have satisfied the check.
     */
    public void checkSolvency(
            @NonNull final TransactionInfo txInfo,
            @NonNull final Account account,
            @NonNull final Fees fees,
            // This is to match mono and pass HapiTest. Should reconsider later.
            // FUTURE ('#9550')
            @NonNull final WorkflowCheck workflowCheck)
            throws PreCheckException {
        checkSolvency(
                txInfo.txBody(),
                txInfo.payerID(),
                txInfo.functionality(),
                account,
                fees,
                workflowCheck,
                CHECK_OFFERED_FEE);
    }

    /**
     * Checks if the verified payer account of the given transaction can afford to cover its fees.
     * It also checks if the offered fee is sufficient. If the payer account cannot afford the fees, an
     * {@link InsufficientBalanceException} is thrown with the appropriate status and fee amount. If the offered fee
     * is insufficient, an {@link InsufficientServiceFeeException} is thrown with the appropriate status and fee amount.
     * @param txBody the {@link TransactionBody} to use during the check
     * @param payerID the {@link AccountID} of the payer
     * @param functionality the {@link HederaFunctionality} of the transaction
     * @param account the {@link Account} with the balance to check
     * @param fees the fees to use for the check
     * @param workflowCheck if IS_INGEST, the check is being performed during an ingest workflow.
     * @param offeredFeeCheck if CHECK_OFFERED_FEE, the offered fee is checked against the total fee.
     * @throws PreCheckException if the payer account cannot afford the fees. The exception will have a status of
     * {@code INSUFFICIENT_PAYER_BALANCE} and the fee amount that would have satisfied the check.
     */
    public void checkSolvency(
            @NonNull final TransactionBody txBody,
            @NonNull final AccountID payerID,
            @NonNull final HederaFunctionality functionality,
            @NonNull final Account account,
            @NonNull final Fees fees,
            // This is to match mono and pass HapiTest. Should reconsider later.
            // FUTURE ('#9550')
            @NonNull final WorkflowCheck workflowCheck,
            @NonNull OfferedFeeCheck offeredFeeCheck)
            throws PreCheckException {
        final var isIngest = workflowCheck.equals(INGEST);
        final var checkOfferedFee = offeredFeeCheck.equals(CHECK_OFFERED_FEE);
        // Skip solvency check for privileged transactions or superusers
        if (authorizer.hasWaivedFees(payerID, functionality, txBody)) {
            return;
        }
        final var totalFee = isIngest ? fees.totalWithoutServiceFee() : fees.totalFee();
        final var availableBalance = account.tinybarBalance();
        final var offeredFee = txBody.transactionFee();

        if (checkOfferedFee && offeredFee < fees.networkFee()) {
            throw new InsufficientNetworkFeeException(INSUFFICIENT_TX_FEE, totalFee);
        }
        if (availableBalance < fees.networkFee()) {
            throw new InsufficientNetworkFeeException(INSUFFICIENT_PAYER_BALANCE, totalFee);
        }
        if (checkOfferedFee && offeredFee < totalFee) {
            throw new InsufficientServiceFeeException(INSUFFICIENT_TX_FEE, totalFee);
        }

        if (availableBalance < totalFee) {
            throw new InsufficientServiceFeeException(INSUFFICIENT_PAYER_BALANCE, totalFee);
        }

        long additionalCosts = 0L;
        try {
            if (isIngest) {
                final var now = txBody.transactionIDOrThrow().transactionValidStartOrThrow();
                additionalCosts = Math.max(0, estimateAdditionalCosts(txBody, functionality, HapiUtils.asInstant(now)));
            }
        } catch (final NullPointerException ex) {
            // One of the required fields was not present
            throw new InsufficientBalanceException(INVALID_TRANSACTION_BODY, totalFee);
        }

        if (availableBalance < totalFee + additionalCosts) {
            // FUTURE: This should be checked earlier
            expiryValidation.checkAccountExpiry(account);
            throw new InsufficientNonFeeDebitsException(INSUFFICIENT_PAYER_BALANCE, totalFee);
        }
    }

    // FUTURE: This should be provided by the TransactionHandler:
    // https://github.com/hashgraph/hedera-services/issues/8354
    public long estimateAdditionalCosts(
            @NonNull final TransactionBody txBody,
            @NonNull final HederaFunctionality functionality,
            @NonNull final Instant consensusTime) {
        return switch (functionality) {
            case CRYPTO_CREATE -> txBody.cryptoCreateAccountOrThrow().initialBalance();
            case CRYPTO_TRANSFER -> {
                if (!txBody.cryptoTransferOrThrow().hasTransfers()) {
                    yield 0L;
                }
                final var payerID = txBody.transactionIDOrThrow().accountIDOrThrow();
                yield -txBody.cryptoTransferOrThrow().transfersOrThrow().accountAmounts().stream()
                        .filter(aa -> Objects.equals(aa.accountID(), payerID))
                        .mapToLong(AccountAmount::amount)
                        .sum();
            }
            case CONTRACT_CREATE -> {
                final var contractCreate = txBody.contractCreateInstanceOrThrow();
                yield contractCreate.initialBalance()
                        + contractCreate.gas() * estimatedGasPriceInTinybars(CONTRACT_CREATE, consensusTime);
            }
            case CONTRACT_CALL -> {
                final var contractCall = txBody.contractCallOrThrow();
                yield contractCall.amount()
                        + contractCall.gas() * estimatedGasPriceInTinybars(CONTRACT_CALL, consensusTime);
            }
            case ETHEREUM_TRANSACTION -> {
                final var ethTxn = txBody.ethereumTransactionOrThrow();
                yield ethTxn.maxGasAllowance();
            }
            default -> 0L;
        };
    }

    private long estimatedGasPriceInTinybars(
            @NonNull final HederaFunctionality functionality, @NonNull final Instant consensusTime) {
        final var feeData = feeManager.getFeeData(functionality, consensusTime, SubType.DEFAULT);
        final long priceInTinyCents = feeData.servicedataOrThrow().gas() / FEE_DIVISOR_FACTOR;
        final long priceInTinyBars = exchangeRateManager.getTinybarsFromTinycents(priceInTinyCents, consensusTime);
        return Math.max(priceInTinyBars, 1L);
    }
}
