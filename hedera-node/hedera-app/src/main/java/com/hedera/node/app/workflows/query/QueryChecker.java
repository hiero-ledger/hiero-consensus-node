// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.NOT_INGEST;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.FeeContextImpl;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.validation.ExpiryValidation;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.purechecks.PureChecksContextImpl;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/** This class contains all checks related to instances of {@link Query} */
@Singleton
public class QueryChecker {

    private final Authorizer authorizer;
    private final CryptoTransferHandler cryptoTransferHandler;
    private final SolvencyPreCheck solvencyPreCheck;
    private final ExpiryValidation expiryValidation;
    private final FeeManager feeManager;
    private final TransactionDispatcher dispatcher;
    private final TransactionChecker transactionChecker;

    /**
     * Constructor of {@code QueryChecker}
     *
     * @param authorizer            the {@link Authorizer} that checks, if the caller is authorized
     * @param cryptoTransferHandler the {@link CryptoTransferHandler} that validates a contained
     *                              {@link HederaFunctionality#CRYPTO_TRANSFER}.
     * @param solvencyPreCheck      the {@link SolvencyPreCheck} that checks if the payer has enough
     * @param expiryValidation      the {@link ExpiryValidation} that checks if an account is expired
     * @param feeManager            the {@link FeeManager} that calculates the fees
     * @param dispatcher
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Inject
    public QueryChecker(
            @NonNull final Authorizer authorizer,
            @NonNull final CryptoTransferHandler cryptoTransferHandler,
            @NonNull final SolvencyPreCheck solvencyPreCheck,
            @NonNull final ExpiryValidation expiryValidation,
            @NonNull final FeeManager feeManager,
            final TransactionDispatcher dispatcher,
            @NonNull final TransactionChecker transactionChecker) {
        this.authorizer = requireNonNull(authorizer);
        this.cryptoTransferHandler = requireNonNull(cryptoTransferHandler);
        this.solvencyPreCheck = requireNonNull(solvencyPreCheck);
        this.expiryValidation = requireNonNull(expiryValidation);
        this.feeManager = requireNonNull(feeManager);
        this.dispatcher = requireNonNull(dispatcher);
        this.transactionChecker = requireNonNull(transactionChecker);
    }

    /**
     * Validates the {@link HederaFunctionality#CRYPTO_TRANSFER} that is contained in a query
     *
     * @param transactionInfo the {@link TransactionInfo} that contains all data about the transaction
     * @throws PreCheckException if validation fails
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public void validateCryptoTransfer(@NonNull final TransactionInfo transactionInfo) throws PreCheckException {
        requireNonNull(transactionInfo);
        if (transactionInfo.functionality() != CRYPTO_TRANSFER) {
            throw new PreCheckException(INSUFFICIENT_TX_FEE);
        }
        final var txBody = transactionInfo.txBody();
        final var pureChecksContext = new PureChecksContextImpl(txBody, dispatcher);
        cryptoTransferHandler.pureChecks(pureChecksContext);
    }

    /**
     * Validates the account balances needed in a query
     *
     * @param accountStore the {@link ReadableAccountStore} used to access accounts
     * @param txInfo the {@link TransactionInfo} of the {@link HederaFunctionality#CRYPTO_TRANSFER}
     * @param nodePayment node payment amount
     * @param transferTxnFee crypto transfer transaction fee
     * @throws PreCheckException if validation fails
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public void validateAccountBalances(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TransactionInfo txInfo,
            @NonNull final Account payer,
            final long nodePayment,
            final long transferTxnFee)
            throws PreCheckException {
        requireNonNull(accountStore);
        requireNonNull(txInfo);
        requireNonNull(payer);

        final var payerID = txInfo.payerID();
        final var nodeAccountID = txInfo.txBody().nodeAccountIDOrThrow();
        final var transfers =
                txInfo.txBody().cryptoTransferOrThrow().transfersOrThrow().accountAmounts();

        // FUTURE: Currently we check the solvency twice: once with and once without service fees (in IngestChecker)
        // https://github.com/hashgraph/hedera-services/issues/8356
        solvencyPreCheck.checkSolvency(txInfo, payer, new Fees(transferTxnFee, 0, 0), NOT_INGEST);

        if (transfers.isEmpty()) {
            throw new PreCheckException(INVALID_ACCOUNT_AMOUNTS);
        }

        boolean nodeReceivesSome = false;
        for (final var transfer : transfers) {
            final var accountID = transfer.accountIDOrThrow();
            final var amount = transfer.amount();
            // Need to figure out, what is special about this and replace with a constant
            if (amount == Long.MIN_VALUE) {
                throw new PreCheckException(INVALID_ACCOUNT_AMOUNTS);
            }

            // Only check non-payer accounts
            if (!Objects.equals(accountID, payerID)) {

                final var account = accountStore.getAccountById(accountID);
                if (account == null) {
                    throw new PreCheckException(ACCOUNT_ID_DOES_NOT_EXIST);
                }

                // The balance only needs to be checked for sent amounts (= negative values)
                if (amount < 0 && (account.tinybarBalance()) < -amount) {
                    // FUTURE: Expiry should probably be checked earlier
                    expiryValidation.checkAccountExpiry(account);
                    throw new InsufficientBalanceException(INSUFFICIENT_PAYER_BALANCE, transferTxnFee);
                }

                // Make sure the node receives enough
                if (amount >= 0 && nodeAccountID.equals(accountID)) {
                    nodeReceivesSome = true;
                    if (amount < nodePayment) {
                        throw new InsufficientBalanceException(INSUFFICIENT_TX_FEE, nodePayment);
                    }
                }
            }
            // this will happen just if it is a payer
            else if (amount < 0 && (payer.tinybarBalance() - transferTxnFee) < -amount) {
                throw new InsufficientBalanceException(INSUFFICIENT_PAYER_BALANCE, transferTxnFee);
            }
        }

        if (!nodeReceivesSome) {
            throw new PreCheckException(INVALID_RECEIVING_NODE_ACCOUNT);
        }
    }

    /**
     * Checks the permission required for a query
     *
     * @param payer the {@link AccountID} of the payer and whose permissions are checked
     * @param functionality the {@link HederaFunctionality} of the query
     * @throws PreCheckException if permissions are not sufficient
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public void checkPermissions(@NonNull final AccountID payer, @NonNull final HederaFunctionality functionality)
            throws PreCheckException {
        requireNonNull(payer);
        requireNonNull(functionality);

        if (!authorizer.isAuthorized(payer, functionality)) {
            throw new PreCheckException(NOT_SUPPORTED);
        }
    }

    /**
     * Estimates the fees for a payment (CryptoTransfer) in a query
     *
     * @param storeFactory the {@link ReadableStoreFactory} used to access stores
     * @param transactionInfo the {@link TransactionInfo} of the {@link HederaFunctionality#CRYPTO_TRANSFER}
     * @param payerKey the {@link Key} of the payer
     * @param configuration the current {@link Configuration}
     * @return the estimated fees
     */
    public long estimateTxFees(
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final Instant consensusTime,
            @NonNull final TransactionInfo transactionInfo,
            @NonNull final Key payerKey,
            @NonNull final Configuration configuration) {
        final var feeContext = new FeeContextImpl(
                consensusTime,
                transactionInfo,
                payerKey,
                transactionInfo.payerID(),
                feeManager,
                storeFactory,
                configuration,
                authorizer,
                // Signatures aren't applicable to queries
                -1,
                dispatcher);
        return cryptoTransferHandler.calculateFees(feeContext).totalFee();
    }
}
