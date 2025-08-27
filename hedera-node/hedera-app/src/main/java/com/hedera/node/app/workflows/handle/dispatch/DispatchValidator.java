// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.state.HederaRecordCache.DuplicateCheckResult.NO_DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newCreatorError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newGenesisWaiver;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newPayerDuplicateError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newPayerUniqueError;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.handle.Dispatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A class that reports errors that occurred during the processing of a transaction.
 * This includes the creator error, the payer error, and whether the payer was unable to pay the service fee.
 * It also records whether the transaction is a duplicate.
 */
@Singleton
public class DispatchValidator {
    private final HederaRecordCache recordCache;
    private final TransactionChecker transactionChecker;
    private final AppFeeCharging feeCharging;

    @Nullable
    private final AtomicBoolean systemEntitiesCreatedFlag;

    /**
     * Creates an error reporter with the given dependencies.
     *
     * @param recordCache the record cache
     * @param transactionChecker the transaction checker
     */
    @Inject
    public DispatchValidator(
            @NonNull final HederaRecordCache recordCache,
            @NonNull final TransactionChecker transactionChecker,
            @NonNull final AppFeeCharging feeCharging,
            @Nullable final AtomicBoolean systemEntitiesCreatedFlag) {
        this.recordCache = requireNonNull(recordCache);
        this.transactionChecker = requireNonNull(transactionChecker);
        this.feeCharging = requireNonNull(feeCharging);
        this.systemEntitiesCreatedFlag = systemEntitiesCreatedFlag;
    }

    /**
     * Reports an error for the given dispatch. This first checks if there is a creator error, and if so, returns it.
     * Otherwise, checks signatures for non-hollow payer. Also checks if the user transaction is duplicate and if payer
     * can pay the fees.
     *
     * @param dispatch the dispatch
     * @return the error report
     */
    public FeeCharging.Validation validateFeeChargingScenario(@NonNull final Dispatch dispatch) {
        if (systemEntitiesCreatedFlag != null && !systemEntitiesCreatedFlag.get()) {
            return newGenesisWaiver(dispatch.creatorInfo().accountId());
        }
        final var creatorError = creatorErrorIfKnown(dispatch);
        if (creatorError != null) {
            return newCreatorError(dispatch.creatorInfo().accountId(), creatorError);
        } else {
            final var payer =
                    getPayerAccount(dispatch.readableStoreFactory(), dispatch.payerId(), dispatch.txnCategory());
            final var category = dispatch.txnCategory();
            // Check payer signature for all batch inner transactions, scheduled, and user transactions
            final var requiresPayerSig = category == SCHEDULED || category == USER || category == BATCH_INNER;
            if (requiresPayerSig && !isHollow(payer)) {
                // Skip payer verification for hollow accounts because ingest only submits valid signatures
                // for hollow payers; and if an account is still hollow here, its alias cannot have changed
                final var verification = dispatch.keyVerifier().verificationFor(payer.keyOrThrow());
                if (verification.failed()) {
                    return newCreatorError(dispatch.creatorInfo().accountId(), INVALID_PAYER_SIGNATURE);
                }
            }
            final var duplicateCheckResult = category != USER && category != NODE && category != BATCH_INNER
                    ? NO_DUPLICATE
                    : recordCache.hasDuplicate(
                            dispatch.txnInfo().txBody().transactionIDOrThrow(),
                            dispatch.creatorInfo().nodeId());
            return switch (duplicateCheckResult) {
                case NO_DUPLICATE -> getFinalPayerValidation(payer, DuplicateStatus.NO_DUPLICATE, dispatch);
                case SAME_NODE -> newCreatorError(dispatch.creatorInfo().accountId(), DUPLICATE_TRANSACTION);
                case OTHER_NODE -> getFinalPayerValidation(payer, DuplicateStatus.DUPLICATE, dispatch);
            };
        }
    }

    /**
     * Checks payer solvency for Schedule and User transactions. If the payer is a super-user, it will not be checked.
     *
     * @param payer the payer account
     * @param duplicateStatus whether the transaction is a duplicate
     * @param dispatch the dispatch
     * @return the error report
     */
    @NonNull
    private FeeCharging.Validation getFinalPayerValidation(
            @NonNull final Account payer,
            @NonNull final DuplicateStatus duplicateStatus,
            @NonNull final Dispatch dispatch) {
        final var creatorId = dispatch.creatorInfo().accountId();
        final boolean isDuplicate = duplicateStatus == DuplicateStatus.DUPLICATE;
        final var validation = dispatch.feeChargingOrElse(feeCharging)
                .validate(
                        payer,
                        creatorId,
                        dispatch.fees(),
                        dispatch.txnInfo().txBody(),
                        isDuplicate,
                        dispatch.txnInfo().functionality(),
                        dispatch.txnCategory());
        if (validation.maybeErrorStatus() != null) {
            return validation;
        }
        return switch (duplicateStatus) {
            case DUPLICATE -> newPayerDuplicateError(creatorId, payer);
            case NO_DUPLICATE ->
                dispatch.preHandleResult().status() == SO_FAR_SO_GOOD
                        ? validation
                        : newPayerUniqueError(
                                creatorId, payer, dispatch.preHandleResult().responseCode());
        };
    }

    /**
     * Returns the response code if there is any error in pre-handle. If there is no error, checks the
     * transaction expiry.
     *
     * @param dispatch the dispatch
     * @return the response code
     */
    @Nullable
    private ResponseCodeEnum creatorErrorIfKnown(@NonNull final Dispatch dispatch) {
        final var preHandleResult = dispatch.preHandleResult();
        return switch (preHandleResult.status()) {
            case NODE_DUE_DILIGENCE_FAILURE -> preHandleResult.responseCode();
            case SO_FAR_SO_GOOD -> getExpiryError(dispatch);
            case UNKNOWN_FAILURE, PAYER_UNWILLING_OR_UNABLE_TO_PAY_SERVICE_FEE, PRE_HANDLE_FAILURE -> null;
        };
    }

    /**
     * Checks the transaction expiry only for user transactions. If the transaction is expired, returns the response code.
     *
     * @param dispatch the dispatch
     * @return the response code
     */
    @Nullable
    private ResponseCodeEnum getExpiryError(final @NonNull Dispatch dispatch) {
        if (dispatch.txnCategory() != USER && dispatch.txnCategory() != NODE && dispatch.txnCategory() != BATCH_INNER) {
            return null;
        }
        try {
            transactionChecker.checkTimeBox(
                    dispatch.txnInfo().txBody(),
                    dispatch.consensusNow(),
                    TransactionChecker.RequireMinValidLifetimeBuffer.NO);
        } catch (PreCheckException e) {
            return e.responseCode();
        }
        return null;
    }

    /**
     * Returns the payer account for the given account ID. For the user transaction, the account should not be null,
     * deleted, or a smart contract. For the scheduled transaction, the account should not be null or a smart contract,
     * can be deleted.
     * For the child and preceding transactions, the payer account can be null. Because the payer for contract
     * operations can be a token address.
     *
     * @param storeFactory the store factory
     * @param accountID the account ID
     * @param category the transaction category
     * @return the payer account
     */
    private Account getPayerAccount(
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final AccountID accountID,
            @NonNull final HandleContext.TransactionCategory category) {
        final var accountStore = storeFactory.getStore(ReadableAccountStore.class);
        final var account = accountStore.getAccountById(accountID);
        return switch (category) {
            case USER, NODE, BATCH_INNER -> {
                if (account == null) {
                    throw new IllegalStateException(
                            String.format("Category %s payer account with id %s does not exists", category, accountID));
                }
                if (account.deleted()) {
                    throw new IllegalStateException(
                            String.format("Category %s payer account with id %s is deleted", category, accountID));
                }
                if (account.smartContract()) {
                    throw new IllegalStateException(String.format(
                            "Category %s payer account with id %s is a smart contract", category, accountID));
                }
                yield account;
            }
            case CHILD, PRECEDING, SCHEDULED -> {
                if (account == null) {
                    throw new IllegalStateException(
                            "Category " + category + " payer account should have been rejected " + account);
                }
                yield account;
            }
        };
    }

    /**
     * Enumerates whether the transaction is a duplicate.
     */
    public enum DuplicateStatus {
        /**
         * The transaction is a duplicate.
         */
        DUPLICATE,
        /**
         * The transaction is not a duplicate.
         */
        NO_DUPLICATE
    }

    /**
     * Enumerates if the offered fee should be checked. The fee should be checked in solvency preCheck only
     * for user and scheduled transaction categories. It will be skipped for child and preceding
     * transactions.
     */
    public enum OfferedFeeCheck {
        /**
         * The offered fee should be checked.
         */
        CHECK_OFFERED_FEE,
        /**
         * The offered fee check should be skipped.
         */
        SKIP_OFFERED_FEE_CHECK
    }

    /**
     * Enumerates whether the payer can pay the service fee. This status is set in the validation report
     * generated by TransactionValidator. If the payer cannot pay service fee.
     */
    public enum ServiceFeeStatus {
        /**
         * The payer can pay the service fee.
         */
        CAN_PAY_SERVICE_FEE,
        /**
         * The payer cannot pay the service fee, we charge all fees except service fees.
         */
        UNABLE_TO_PAY_SERVICE_FEE
    }

    /**
     * Enumerates whether the workflow is an ingest or not ingest workflow.
     */
    public enum WorkflowCheck {
        INGEST,
        NOT_INGEST
    }
}
