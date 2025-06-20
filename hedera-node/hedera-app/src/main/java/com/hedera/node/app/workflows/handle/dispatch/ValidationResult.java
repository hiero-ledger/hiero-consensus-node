// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus.DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus.NO_DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus.CAN_PAY_SERVICE_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus.UNABLE_TO_PAY_SERVICE_FEE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.spi.fees.FeeCharging.Validation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A report of errors that occurred during the processing of a transaction. This records the errors including the
 * creator error, the payer error, and whether the payer was unable to pay the service fee. It also records whether
 * the transaction is a duplicate.
 * @param creatorId the creator account ID, should be non-null
 * @param creatorError the creator error, can be null if there is no creator error
 * @param payer the payer account, can be null if there is no payer account for contract operations. It can be a token address.
 * @param payerError the final determined payer error, if any
 * @param serviceFeeStatus whether the payer was unable to pay the service fee
 * @param duplicateStatus whether the transaction is a duplicate
 */
public record ValidationResult(
        @NonNull AccountID creatorId,
        @Nullable ResponseCodeEnum creatorError,
        @Nullable Account payer,
        @Nullable ResponseCodeEnum payerError,
        @NonNull ServiceFeeStatus serviceFeeStatus,
        @NonNull DuplicateStatus duplicateStatus)
        implements Validation {

    @Override
    public boolean creatorDidDueDiligence() {
        return creatorError == null;
    }

    @Override
    public @Nullable ResponseCodeEnum maybeErrorStatus() {
        return creatorError != null ? creatorError : payerError;
    }

    /**
     * Creates an error report with a creator error.
     * @param creatorId the creator account ID
     * @param creatorError the creator error
     * @return the error report
     */
    @NonNull
    public static ValidationResult newCreatorError(
            @NonNull AccountID creatorId, @NonNull ResponseCodeEnum creatorError) {
        return new ValidationResult(creatorId, creatorError, null, null, CAN_PAY_SERVICE_FEE, NO_DUPLICATE);
    }

    /**
     * Creates an error report with a payer error due to a duplicate transaction.
     *
     * @param creatorId the creator account ID
     * @param payer the payer account
     * @return the error report
     */
    @NonNull
    public static ValidationResult newPayerDuplicateError(
            @NonNull final AccountID creatorId, @NonNull final Account payer) {
        requireNonNull(payer);
        requireNonNull(creatorId);
        return new ValidationResult(creatorId, null, payer, DUPLICATE_TRANSACTION, CAN_PAY_SERVICE_FEE, DUPLICATE);
    }

    /**
     * Creates an error report with a payer error due to a reason other than a duplicate transaction
     * or a solvency failure.
     *
     * @param creatorId the creator account ID
     * @param payer the payer account
     * @return the error report
     */
    @NonNull
    public static ValidationResult newPayerUniqueError(
            @NonNull final AccountID creatorId,
            @NonNull final Account payer,
            @NonNull final ResponseCodeEnum payerError) {
        requireNonNull(payer);
        requireNonNull(creatorId);
        requireNonNull(payerError);
        return new ValidationResult(creatorId, null, payer, payerError, CAN_PAY_SERVICE_FEE, NO_DUPLICATE);
    }

    public static ValidationResult newPayerError(
            @NonNull final AccountID creatorId,
            @NonNull final Account payer,
            @NonNull final ResponseCodeEnum payerError,
            @NonNull final ServiceFeeStatus serviceFeeStatus,
            final boolean isDuplicate) {
        return new ValidationResult(
                creatorId, null, payer, payerError, serviceFeeStatus, isDuplicate ? DUPLICATE : NO_DUPLICATE);
    }

    /**
     * Creates an error report with a payer error.
     * @param creatorId the creator account ID
     * @param payer the payer account
     * @param payerError the payer error
     * @param serviceFeeStatus whether the payer was unable to pay the service fee
     * @param duplicateStatus whether the transaction is a duplicate
     * @return the error report
     */
    @NonNull
    public static ValidationResult newPayerError(
            @NonNull final AccountID creatorId,
            @NonNull final Account payer,
            @NonNull final ResponseCodeEnum payerError,
            @NonNull final ServiceFeeStatus serviceFeeStatus,
            @NonNull final DuplicateStatus duplicateStatus) {
        return new ValidationResult(creatorId, null, payer, payerError, serviceFeeStatus, duplicateStatus);
    }

    /**
     * Creates an error report with no error.
     * @param creatorId the creator account ID
     * @param payer the payer account
     * @return the error report
     */
    @NonNull
    public static ValidationResult newSuccess(@NonNull final AccountID creatorId, @NonNull final Account payer) {
        requireNonNull(creatorId);
        requireNonNull(payer);
        return new ValidationResult(creatorId, null, payer, null, CAN_PAY_SERVICE_FEE, NO_DUPLICATE);
    }

    /**
     * Creates an error report with no error.
     * @param creatorId the creator account ID
     * @return the error report
     */
    @NonNull
    public static ValidationResult newGenesisWaiver(@NonNull final AccountID creatorId) {
        requireNonNull(creatorId);
        return new ValidationResult(creatorId, null, null, null, CAN_PAY_SERVICE_FEE, NO_DUPLICATE);
    }

    /**
     * Returns true if no errors were found.
     */
    public boolean isSuccess() {
        return creatorError == null && payerError == null;
    }

    /**
     * Checks if there is a creator error.
     * @return true if there is a creator error. Otherwise, false.
     */
    public boolean isCreatorError() {
        return creatorError != null;
    }

    /**
     * Checks if there is a payer error.
     * @return true if there is a payer error. Otherwise, false.
     */
    public boolean isPayerError() {
        return payerError != null;
    }

    /**
     * Checks if there is payer error. If not, throws an exception.
     * @return the payer error
     */
    @NonNull
    public ResponseCodeEnum payerErrorOrThrow() {
        return requireNonNull(payerError);
    }

    /**
     * Checks if there is a creator error. If not, throws an exception.
     * @return the creator error
     */
    @NonNull
    public ResponseCodeEnum creatorErrorOrThrow() {
        return requireNonNull(creatorError);
    }

    /**
     * Checks if there is a payer.
     * @return payer account if there is a payer. Otherwise, throws an exception.
     */
    @NonNull
    public Account payerOrThrow() {
        return requireNonNull(payer);
    }

    /**
     * Returns the error report with all fees except service fee.
     * @return the error report
     */
    @NonNull
    public ValidationResult withoutServiceFee() {
        return new ValidationResult(
                creatorId, creatorError, payer, payerError, UNABLE_TO_PAY_SERVICE_FEE, duplicateStatus);
    }
}
