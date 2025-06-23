// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.DispatchValidator;
import com.hedera.node.app.workflows.handle.dispatch.RecordFinalizer;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.PlatformStateUpdates;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager;
import com.hedera.node.app.workflows.handle.throttle.ThrottleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class has the common logic that is executed for a user dispatch and a child dispatch transactions.
 * This will charge the fees to the creator if there is a node due diligence failure. Otherwise, charges the fees to the
 * payer and executes the business logic for the given dispatch, guaranteeing that the changes committed to its stack
 * are exactly reflected in its recordBuilder. At the end, it will finalize the record and commit the stack.
 */
@Singleton
public class DispatchProcessor {
    private static final Logger logger = LogManager.getLogger(DispatchProcessor.class);

    private final Authorizer authorizer;
    private final DispatchValidator validator;
    private final RecordFinalizer recordFinalizer;
    private final SystemFileUpdates systemFileUpdates;
    private final PlatformStateUpdates platformStateUpdates;
    private final DispatchUsageManager dispatchUsageManager;
    private final ExchangeRateManager exchangeRateManager;
    private final TransactionDispatcher dispatcher;
    private final EthereumTransactionHandler ethereumTransactionHandler;
    private final NetworkInfo networkInfo;
    private final OpWorkflowMetrics workflowMetrics;
    private final AppFeeCharging appFeeCharging;

    @Inject
    public DispatchProcessor(
            @NonNull final Authorizer authorizer,
            @NonNull final DispatchValidator validator,
            @NonNull final RecordFinalizer recordFinalizer,
            @NonNull final SystemFileUpdates systemFileUpdates,
            @NonNull final PlatformStateUpdates platformStateUpdates,
            @NonNull final DispatchUsageManager dispatchUsageManager,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final EthereumTransactionHandler ethereumTransactionHandler,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final OpWorkflowMetrics workflowMetrics,
            @NonNull final AppFeeCharging appFeeCharging) {
        this.authorizer = requireNonNull(authorizer);
        this.validator = requireNonNull(validator);
        this.recordFinalizer = requireNonNull(recordFinalizer);
        this.systemFileUpdates = requireNonNull(systemFileUpdates);
        this.platformStateUpdates = requireNonNull(platformStateUpdates);
        this.dispatchUsageManager = requireNonNull(dispatchUsageManager);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.dispatcher = requireNonNull(dispatcher);
        this.ethereumTransactionHandler = requireNonNull(ethereumTransactionHandler);
        this.networkInfo = requireNonNull(networkInfo);
        this.workflowMetrics = requireNonNull(workflowMetrics);
        this.appFeeCharging = requireNonNull(appFeeCharging);
    }

    /**
     * This method is responsible for charging the fees and tries to execute the
     * business logic for the given dispatch, guaranteeing that the changes committed
     * to its stack are exactly reflected in its recordBuilder. At the end, it will
     * finalize the record and commit the stack.
     *
     * @param dispatch the dispatch to be processed
     */
    public void processDispatch(@NonNull final Dispatch dispatch) {
        requireNonNull(dispatch);
        final var validation = validator.validateFeeChargingScenario(dispatch);
        if (!validation.creatorDidDueDiligence()) {
            chargeCreator(dispatch, validation);
        } else {
            final var fees = chargePayer(dispatch, validation, false);
            if (!alreadyFailed(dispatch, validation)) {
                tryHandle(dispatch, validation, fees);
            }
        }
        dispatchUsageManager.finalizeAndSaveUsage(dispatch);
        recordFinalizer.finalizeRecord(dispatch);
        dispatch.stack().commitFullStack();
    }

    /**
     * Tries to the transaction logic for the given dispatch. If the logic fails and
     * throws HandleException, it will rollback the stack and charge the payer for the
     * fees. If it is throttled, it will charge the payer for the fees and return
     * FEE_ONLY as work done. If it catches an unexpected exception, it will charge
     * the payer for the fees and return FEE_ONLY as work done.
     *
     * @param dispatch the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @param fees the fees charged to the payer
     */
    private void tryHandle(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            @NonNull final Fees fees) {
        final var functionality = dispatch.txnInfo().functionality();
        try {
            dispatchUsageManager.screenForCapacity(dispatch);
            dispatcher.dispatchHandle(dispatch.handleContext());
            dispatch.streamBuilder().status(SUCCESS);
            if (functionality == ETHEREUM_TRANSACTION) {
                final boolean refundsEnabled =
                        dispatch.config().getConfigData(ContractsConfig.class).evmEthTransactionZeroHapiFeesEnabled();
                if (refundsEnabled) {
                    dispatch.feeChargingOrElse(appFeeCharging).refund(dispatch, fees);
                }
            }
            handleSystemUpdates(dispatch);
        } catch (HandleException e) {
            rollback(e.getStatus(), dispatch.stack(), dispatch.streamBuilder());
            chargePayer(dispatch, validation, false);
            e.maybeReplayFees(dispatch);
        } catch (final ThrottleException e) {
            workflowMetrics.incrementThrottled(functionality);
            rollbackAndRechargeFee(dispatch, validation, e.getStatus());
            if (functionality == ETHEREUM_TRANSACTION) {
                ethereumTransactionHandler.handleThrottled(dispatch.handleContext());
            }
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling dispatch", ALERT_MESSAGE, e);
            rollbackAndRechargeFee(dispatch, validation, FAIL_INVALID);
        }
    }

    /**
     * Handles the system updates for the dispatch. It will notify the responsible system file update facility if
     * any system file was uploaded. It will also notify if the platform state was updated.
     *
     * @param dispatch the dispatch to be processed
     */
    private void handleSystemUpdates(final Dispatch dispatch) {
        // Notify responsible facility if system-file was uploaded.
        // Returns SUCCESS if no system-file was uploaded
        final var fileUpdateResult = systemFileUpdates.handleTxBody(
                dispatch.stack(), dispatch.txnInfo().txBody());

        // In case we just changed the exchange rates via 0.0.112 update, reset them now
        dispatch.streamBuilder()
                .exchangeRate(exchangeRateManager.exchangeRates())
                .status(fileUpdateResult);

        // Notify if platform state was updated
        platformStateUpdates.handleTxBody(dispatch.stack(), dispatch.txnInfo().txBody(), dispatch.config());

        if (dispatch.txnInfo().functionality() == NODE_UPDATE) {
            networkInfo.updateFrom(dispatch.stack());
        }
    }

    /**
     * Handles the exception for the dispatch. It will rollback the stack, charge
     * the payer for the fees and return FEE_ONLY as work done.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @param status     the status to set
     */
    private void rollbackAndRechargeFee(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            @NonNull final ResponseCodeEnum status) {
        rollback(status, dispatch.stack(), dispatch.streamBuilder());
        chargePayer(dispatch, validation, true);
        dispatchUsageManager.trackFeePayments(dispatch);
    }

    /**
     * Charges the creator for the network fee. This will be called when there is a due diligence failure.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the validation of the charging scenario
     */
    private void chargeCreator(@NonNull final Dispatch dispatch, @NonNull final FeeCharging.Validation validation) {
        dispatch.streamBuilder().status(validation.errorStatusOrThrow());
        // If the transaction is a batch inner transaction, we don't charge the creator
        if (dispatch.category() == BATCH_INNER) {
            return;
        }
        dispatch.feeAccumulator()
                .chargeFee(dispatch.creatorInfo().accountId(), dispatch.fees().networkFee(), null);
    }

    /**
     * Charges the payer for the fees. If the payer is unable to pay the service fee, the service fee
     * will be charged to the creator. If the transaction is a duplicate, the service fee will be waived.
     *
     * @param dispatch the dispatch to be processed
     * @param validation the validation of the charging scenario
     * @param waiveServiceFee whether to waive the service fee from the dispatch
     * @return the fees charged
     */
    private Fees chargePayer(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            final boolean waiveServiceFee) {
        final var fees = dispatch.fees();
        if (fees.nothingToCharge()) {
            return Fees.FREE;
        }
        final var hasWaivedFees = authorizer.hasWaivedFees(
                dispatch.payerId(),
                dispatch.txnInfo().functionality(),
                dispatch.txnInfo().txBody());
        if (hasWaivedFees) {
            return Fees.FREE;
        }
        final var feesToCharge = waiveServiceFee ? fees.withoutServiceComponent() : fees;
        return dispatch.feeChargingOrElse(appFeeCharging).charge(dispatch, validation, feesToCharge);
    }

    /**
     * Rolls back the stack and sets the status of the transaction in case of a failure.
     * @param status        the status to set
     * @param stack         the save point stack to rollback
     */
    private void rollback(
            @NonNull final ResponseCodeEnum status,
            @NonNull final SavepointStackImpl stack,
            @NonNull final StreamBuilder builder) {
        builder.status(status);
        stack.rollbackFullStack();
    }

    /**
     * Checks if the transaction has already failed due to an error that can be identified before even performing
     * the dispatch. If it has, it will set the status of the dispatch's record builder and return true.
     * Otherwise, it will return false.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @return true if the transaction has already failed, false otherwise
     */
    private boolean alreadyFailed(@NonNull final Dispatch dispatch, @NonNull final FeeCharging.Validation validation) {
        if (validation.maybeErrorStatus() != null) {
            dispatch.streamBuilder().status(validation.errorStatusOrThrow());
            return true;
        }
        final var authorizationFailure = maybeAuthorizationFailure(dispatch);
        if (authorizationFailure != null) {
            dispatch.streamBuilder().status(authorizationFailure);
            return true;
        }
        if (failsSignatureVerification(dispatch)) {
            dispatch.streamBuilder().status(INVALID_SIGNATURE);
            return true;
        }
        return false;
    }

    /**
     * Asserts that the dispatch is authorized. If the dispatch is not authorized, it will throw a {@link HandleException}.
     *
     * @param dispatch the dispatch to be processed
     */
    private @Nullable ResponseCodeEnum maybeAuthorizationFailure(@NonNull final Dispatch dispatch) {
        final var function = dispatch.txnInfo().functionality();
        if (!authorizer.isAuthorized(dispatch.payerId(), function)) {
            // Node transactions are judged by a different set of rules; any node account can submit
            // any node transaction as long as it is in the allow list
            if (dispatch.txnCategory() == NODE) {
                final var adminConfig = dispatch.config().getConfigData(NetworkAdminConfig.class);
                final var allowList = adminConfig.nodeTransactionsAllowList().functionalitySet();
                if (allowList.contains(function)) {
                    return null;
                }
            }
            return function == SYSTEM_DELETE ? NOT_SUPPORTED : UNAUTHORIZED;
        }
        final var failure = authorizer.hasPrivilegedAuthorization(
                dispatch.payerId(),
                dispatch.txnInfo().functionality(),
                dispatch.txnInfo().txBody());
        return switch (failure) {
            case UNAUTHORIZED -> AUTHORIZATION_FAILED;
            case IMPERMISSIBLE -> ENTITY_NOT_ALLOWED_TO_DELETE;
            default -> null;
        };
    }

    /**
     * Asserts that the signatures are valid. If the signatures are not valid, it will throw a {@link HandleException}.
     *
     * @param dispatch the dispatch to be processed
     */
    private boolean failsSignatureVerification(@NonNull final Dispatch dispatch) {
        for (final var key : dispatch.requiredKeys()) {
            final var verification = dispatch.keyVerifier().verificationFor(key);
            if (verification.failed()) {
                return true;
            }
        }
        // If there are any hollow accounts whose signatures need to be verified, verify them
        for (final var hollowAccount : dispatch.hollowAccounts()) {
            final var verification = dispatch.keyVerifier().verificationFor(hollowAccount.alias());
            if (verification.failed()) {
                return true;
            }
        }
        return false;
    }
}
