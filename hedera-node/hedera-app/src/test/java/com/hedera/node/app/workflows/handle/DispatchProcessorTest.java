// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_UNDELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FEE_FILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.UNNECESSARY;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.DuplicateStatus.NO_DUPLICATE;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.ServiceFeeStatus.UNABLE_TO_PAY_SERVICE_FEE;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newCreatorError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newPayerDuplicateError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newPayerError;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.DispatchValidator;
import com.hedera.node.app.workflows.handle.dispatch.RecordFinalizer;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions.Details;
import com.hedera.node.app.workflows.handle.steps.PlatformStateUpdates;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager;
import com.hedera.node.app.workflows.handle.throttle.ThrottleException;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class DispatchProcessorTest {
    private static final Fees FEES = new Fees(1L, 2L, 3L);
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final Account PAYER =
            Account.newBuilder().accountId(PAYER_ACCOUNT_ID).build();
    private static final AccountID CREATOR_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final Account HOLLOW = Account.newBuilder()
            .alias(Bytes.fromHex("abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd"))
            .build();
    private static final SignatureVerification PASSED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, true);
    private static final SignatureVerification FAILED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, false);
    private static final TransactionBody TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .build();
    private static final TransactionInfo CRYPTO_TRANSFER_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);
    private static final TransactionInfo SYS_DEL_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, SYSTEM_DELETE, null);
    private static final TransactionInfo SYS_UNDEL_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, SYSTEM_UNDELETE, null);
    private static final TransactionInfo CONTRACT_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CONTRACT_CALL, null);
    private static final TransactionInfo ETH_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, ETHEREUM_TRANSACTION, null);
    private static final TransactionInfo NODE_CREATE_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, NODE_CREATE, null);
    private static final TransactionInfo NODE_UPDATE_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, NODE_UPDATE, null);
    private static final SystemFileUpdates.ResyncTarget RATES_TARGET = new SystemFileUpdates.ResyncTarget(
            SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES,
            FileID.newBuilder().fileNum(112).build(),
            Bytes.wrap("applied"));
    private static final TransactionInfo CLPR_SUBMIT_BUNDLE_TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CLPR_SUBMIT_BUNDLE, null);

    @Mock
    private EthereumTransactionHandler ethereumTransactionHandler;

    @Mock
    private Authorizer authorizer;

    @Mock
    private DispatchUsageManager dispatchUsageManager;

    @Mock
    private AppKeyVerifier keyVerifier;

    @Mock
    private HandleContext context;

    @Mock
    private DispatchValidator dispatchValidator;

    @Mock
    private RecordFinalizer recordFinalizer;

    @Mock
    private SystemFileUpdates systemFileUpdates;

    @Mock
    private PlatformStateUpdates platformStateUpdates;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private TransactionDispatcher dispatcher;

    @Mock
    private Dispatch dispatch;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private RecordStreamBuilder recordBuilder;

    @Mock
    private FeeAccumulator feeAccumulator;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private OpWorkflowMetrics opWorkflowMetrics;

    @Mock
    private SolvencyPreCheck solvencyPreCheck;

    @Mock
    private Details hollowAccountCompletionsDetails;

    private DispatchProcessor subject;

    @BeforeEach
    void setUp() {
        lenient().when(systemFileUpdates.needsResync(any(), any())).thenReturn(true);
        subject = new DispatchProcessor(
                authorizer,
                dispatchValidator,
                recordFinalizer,
                systemFileUpdates,
                platformStateUpdates,
                dispatchUsageManager,
                exchangeRateManager,
                dispatcher,
                ethereumTransactionHandler,
                networkInfo,
                opWorkflowMetrics,
                new AppFeeCharging(solvencyPreCheck));
        given(dispatch.stack()).willReturn(stack);
        given(dispatch.streamBuilder()).willReturn(recordBuilder);
    }

    @Test
    void creatorErrorAsExpected() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newCreatorError(CREATOR_ACCOUNT_ID, INVALID_PAYER_SIGNATURE));
        final var creatorInfo = mock(NodeInfo.class);
        given(dispatch.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.accountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFee(CREATOR_ACCOUNT_ID, FEES.networkFee(), null);
        verify(recordBuilder).status(INVALID_PAYER_SIGNATURE);
        assertFinished(IsRootStack.NO);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
    }

    @Test
    void clprSubmitBundleCreatorErrorChargesCreator() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.txnInfo()).willReturn(CLPR_SUBMIT_BUNDLE_TXN_INFO);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newCreatorError(CREATOR_ACCOUNT_ID, INVALID_PAYER_SIGNATURE));
        final var creatorInfo = mock(NodeInfo.class);
        given(dispatch.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.accountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verify(feeAccumulator).chargeFee(CREATOR_ACCOUNT_ID, FEES.networkFee(), null);
        verify(recordBuilder).status(INVALID_PAYER_SIGNATURE);
        assertFinished(IsRootStack.NO);
    }

    @Test
    void clprSubmitBundleHandleExceptionRollsBack() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CLPR_SUBMIT_BUNDLE_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization(CLPR_SUBMIT_BUNDLE_TXN_INFO);
        doThrow(new HandleException(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                .when(dispatcher)
                .dispatchHandle(context);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verify(recordBuilder).status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        verify(stack).rollbackFullStack();
    }

    @Test
    void clprSubmitBundleThrottledIncrementsMetric() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CLPR_SUBMIT_BUNDLE_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.CHILD);
        givenAuthorization(CLPR_SUBMIT_BUNDLE_TXN_INFO);
        doThrow(ThrottleException.newGasThrottleException())
                .when(dispatchUsageManager)
                .screenForCapacity(dispatch);
        doCallRealMethod().when(dispatch).charge(any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        subject.processDispatch(dispatch);

        verify(recordBuilder).status(CONSENSUS_GAS_EXHAUSTED);
        verify(opWorkflowMetrics).incrementThrottled(CLPR_SUBMIT_BUNDLE);
        assertFinished(IsRootStack.NO);
    }

    @Test
    void waivedFeesDoesNotCharge() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.fees()).willReturn(FEES);
        given(authorizer.hasWaivedFees(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER_TXN_INFO.functionality(), TXN_BODY))
                .willReturn(true);
        given(recordBuilder.exchangeRate(any())).willReturn(recordBuilder);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization();

        subject.processDispatch(dispatch);

        verifyNoInteractions(feeAccumulator);
        verify(dispatcher).dispatchHandle(context);
        verify(recordBuilder).status(SUCCESS);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void unauthorizedSystemDeleteIsNotSupported() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(SYS_DEL_TXN_INFO);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, SYS_DEL_TXN_INFO.functionality()))
                .willReturn(false);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(NOT_SUPPORTED);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void unauthorizedOtherIsUnauthorized() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(SYS_UNDEL_TXN_INFO);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, SYS_UNDEL_TXN_INFO.functionality()))
                .willReturn(false);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(UNAUTHORIZED);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void unprivilegedSystemUndeleteIsAuthorizationFailed() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(SYS_DEL_TXN_INFO);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, SYS_DEL_TXN_INFO.functionality()))
                .willReturn(true);
        given(authorizer.hasPrivilegedAuthorization(PAYER_ACCOUNT_ID, SYS_DEL_TXN_INFO.functionality(), TXN_BODY))
                .willReturn(SystemPrivilege.UNAUTHORIZED);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(AUTHORIZATION_FAILED);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void unprivilegedSystemDeleteIsImpermissible() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(SYS_DEL_TXN_INFO);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, SYS_DEL_TXN_INFO.functionality()))
                .willReturn(true);
        given(authorizer.hasPrivilegedAuthorization(PAYER_ACCOUNT_ID, SYS_DEL_TXN_INFO.functionality(), TXN_BODY))
                .willReturn(SystemPrivilege.IMPERMISSIBLE);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(ENTITY_NOT_ALLOWED_TO_DELETE);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void invalidSignatureCryptoTransferFails() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(USER);
        givenAuthorization();
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.keyVerifier()).willReturn(keyVerifier);
        given(dispatch.requiredKeys()).willReturn(Set.of(Key.DEFAULT));
        given(keyVerifier.verificationFor(Key.DEFAULT)).willReturn(FAILED_VERIFICATION);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(INVALID_SIGNATURE);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void invalidHollowAccountCryptoTransferFails() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        givenAuthorization();
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.keyVerifier()).willReturn(keyVerifier);
        given(dispatch.requiredKeys()).willReturn(Set.of(Key.DEFAULT));
        given(keyVerifier.verificationFor(Key.DEFAULT)).willReturn(PASSED_VERIFICATION);
        given(dispatch.hollowAccounts()).willReturn(Set.of(HOLLOW));
        given(keyVerifier.verificationFor(HOLLOW.alias())).willReturn(FAILED_VERIFICATION);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(INVALID_SIGNATURE);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void thrownHandleExceptionRollsBackIfRequested() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization();
        doThrow(new HandleException(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                .when(dispatcher)
                .dispatchHandle(context);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyUtilization();
        verify(dispatcher).dispatchHandle(context);
        verify(recordBuilder).status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        final var inOrder = inOrder(feeAccumulator, stack);
        inOrder.verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        inOrder.verify(stack).rollbackFullStack();
        inOrder.verify(feeAccumulator).reverseAccumulatedNodeFees();
        inOrder.verify(feeAccumulator).resetRefundableFees();
        inOrder.verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        // The rollback must precede the commit, so the failed handler's mutations are discarded before the
        // stack is published. What remains committed is what legitimately accompanies a failed transaction:
        // the fees re-charged after the rollback, the record, and usage tracking.
        inOrder.verify(stack).commitFullStack();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    /**
     * A throw from any recovery or pre-commit step must escape processDispatch so the trailing
     * commitFullStack() is never reached: no state is ever committed alongside the failing receipt.
     * The steps covered run from the start of stack rollback through the last statement before the
     * commit.
     */
    @ParameterizedTest(name = "{0} throwing commits no state")
    @EnumSource(RecoveryStep.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    void noStateIsCommittedWhenAnyRecoveryStepThrows(final RecoveryStep step) {
        // One unchecked type is enough: the recovery path declares no checked exceptions (Mockito refuses
        // to stub one that a method does not declare), and it does not branch on exception subtype -- every
        // type takes the same path out of processDispatch.
        final var thrown = new IllegalStateException("injected");
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization();
        doThrow(new HandleException(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                .when(dispatcher)
                .dispatchHandle(context);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
        switch (step) {
            case ROLLBACK_FULL_STACK -> doThrow(thrown).when(stack).rollbackFullStack();
            case REVERSE_ACCUMULATED_NODE_FEES ->
                doThrow(thrown).when(feeAccumulator).reverseAccumulatedNodeFees();
            case RESET_REFUNDABLE_FEES -> doThrow(thrown).when(feeAccumulator).resetRefundableFees();
            case FINALIZE_AND_SAVE_USAGE ->
                doThrow(thrown).when(dispatchUsageManager).finalizeAndSaveUsage(dispatch);
            case FINALIZE_RECORD -> doThrow(thrown).when(recordFinalizer).finalizeRecord(dispatch);
        }

        assertThatThrownBy(() -> subject.processDispatch(dispatch)).isSameAs(thrown);

        verify(stack, never()).commitFullStack();
    }

    private enum RecoveryStep {
        ROLLBACK_FULL_STACK,
        REVERSE_ACCUMULATED_NODE_FEES,
        RESET_REFUNDABLE_FEES,
        FINALIZE_AND_SAVE_USAGE,
        FINALIZE_RECORD
    }

    @Test
    void replaysHollowFinalizationsAfterHandleExceptionReplay() {
        final List<String> replayOrder = new ArrayList<>();
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization();
        doThrow(new HandleException(
                        TOKEN_NOT_ASSOCIATED_TO_ACCOUNT, (ctx, childDispatch) -> replayOrder.add("exception-replay")))
                .when(dispatcher)
                .dispatchHandle(context);
        doAnswer(invocation -> {
                    replayOrder.add("hollow-replay");
                    return null;
                })
                .when(hollowAccountCompletionsDetails)
                .replay(any());
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch, hollowAccountCompletionsDetails);

        verify(hollowAccountCompletionsDetails).replay(any());
        verify(recordBuilder).status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        assertEquals(List.of("exception-replay", "hollow-replay"), replayOrder);
    }

    @Test
    void blockFullChargesThrottleFeesWithoutExecuting() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CONTRACT_TXN_INFO);
        givenAuthorization(CONTRACT_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch, null, true);

        verifyTrackedFeePayments();
        verify(dispatcher, never()).dispatchHandle(context);
        verify(recordBuilder).status(com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS);
        verify(dispatchUsageManager, never()).screenForCapacity(dispatch);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(feeAccumulator).reverseAccumulatedNodeFees();
        verify(opWorkflowMetrics).incrementThrottled(CONTRACT_CALL);
        assertFinished();
    }

    @Test
    void blockFullEthTxnRecordsEthereumHashWithoutExecuting() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(ETH_TXN_INFO);
        givenAuthorization(ETH_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch, null, true);

        verifyTrackedFeePayments();
        verify(dispatcher, never()).dispatchHandle(context);
        verify(dispatchUsageManager, never()).screenForCapacity(dispatch);
        verify(recordBuilder).status(com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(feeAccumulator).reverseAccumulatedNodeFees();
        verify(ethereumTransactionHandler).handleThrottled(context);
        verify(opWorkflowMetrics).incrementThrottled(ETHEREUM_TRANSACTION);
        assertFinished();
    }

    @Test
    void blockFullStillReplaysHollowFinalizations() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization();
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch, hollowAccountCompletionsDetails, true);

        verify(dispatcher, never()).dispatchHandle(context);
        verify(recordBuilder).status(com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS);
        // Like any other consensus throttling, the rollback must not undo finalizing the hollow payer
        verify(hollowAccountCompletionsDetails).replay(any());
        assertFinished();
    }

    @Test
    void consGasExhaustedWaivesServiceFee() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CONTRACT_TXN_INFO);
        givenAuthorization(CONTRACT_TXN_INFO);
        doThrow(ThrottleException.newGasThrottleException())
                .when(dispatchUsageManager)
                .screenForCapacity(dispatch);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(dispatcher, never()).dispatchHandle(context);
        verify(recordBuilder).status(CONSENSUS_GAS_EXHAUSTED);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(feeAccumulator).reverseAccumulatedNodeFees();
        verify(opWorkflowMetrics).incrementThrottled(CONTRACT_CALL);
        assertFinished();
    }

    @Test
    void throttledTxIncrementsMetric() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.CHILD);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doThrow(ThrottleException.newGasThrottleException())
                .when(dispatchUsageManager)
                .screenForCapacity(dispatch);
        doCallRealMethod().when(dispatch).charge(any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        subject.processDispatch(dispatch);

        verify(platformStateUpdates, never()).handleTxBody(stack, CRYPTO_TRANSFER_TXN_INFO.txBody(), dispatch.config());
        verify(recordBuilder).status(CONSENSUS_GAS_EXHAUSTED);
        verify(feeAccumulator).chargeFee(PAYER_ACCOUNT_ID, FEES.totalFee(), null);
        verify(opWorkflowMetrics).incrementThrottled(CRYPTO_TRANSFER);
        assertFinished(IsRootStack.NO);
    }

    @Test
    void consGasExhaustedForEthTxnDoesExtraWork() throws ThrottleException {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(ETH_TXN_INFO);
        givenAuthorization(ETH_TXN_INFO);
        doThrow(ThrottleException.newGasThrottleException())
                .when(dispatchUsageManager)
                .screenForCapacity(dispatch);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(dispatcher, never()).dispatchHandle(context);
        verify(recordBuilder).status(CONSENSUS_GAS_EXHAUSTED);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(feeAccumulator).reverseAccumulatedNodeFees();
        verify(ethereumTransactionHandler).handleThrottled(context);
        verify(opWorkflowMetrics).incrementThrottled(ETHEREUM_TRANSACTION);
        assertFinished();
    }

    @Test
    void failInvalidWaivesServiceFee() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doThrow(new IllegalStateException()).when(dispatcher).dispatchHandle(context);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(recordBuilder).status(FAIL_INVALID);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(feeAccumulator).reverseAccumulatedNodeFees();
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished();
    }

    @Test
    void happyPathContractCallAsExpected() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CONTRACT_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        given(dispatch.keyVerifier()).willReturn(keyVerifier);
        given(dispatch.requiredKeys()).willReturn(Set.of(Key.DEFAULT));
        given(keyVerifier.verificationFor(Key.DEFAULT)).willReturn(PASSED_VERIFICATION);
        given(dispatch.hollowAccounts()).willReturn(Set.of(HOLLOW));
        given(keyVerifier.verificationFor(HOLLOW.alias())).willReturn(PASSED_VERIFICATION);
        givenAuthorization(CONTRACT_TXN_INFO);
        givenSystemEffectSuccess(CONTRACT_TXN_INFO);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyUtilization();
        verify(platformStateUpdates).handleTxBody(stack, CONTRACT_TXN_INFO.txBody(), dispatch.config());
        verify(recordBuilder, times(2)).status(SUCCESS);
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished();
    }

    @Test
    void happyPathChildCryptoTransferAsExpected() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.CHILD);
        given(dispatch.handleContext()).willReturn(context);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doCallRealMethod().when(dispatch).charge(any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());

        subject.processDispatch(dispatch);

        verify(platformStateUpdates, never()).handleTxBody(stack, CRYPTO_TRANSFER_TXN_INFO.txBody(), dispatch.config());
        verify(recordBuilder).status(SUCCESS);
        verify(feeAccumulator).chargeFee(PAYER_ACCOUNT_ID, FEES.totalFee(), null);
        verify(feeAccumulator)
                .chargeFee(PAYER_ACCOUNT_ID, FEES.withoutServiceComponent().totalFee(), null);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished(IsRootStack.NO);
    }

    @Test
    void happyPathFreeChildCryptoTransferAsExpected() {
        given(dispatch.fees()).willReturn(Fees.FREE);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);

        subject.processDispatch(dispatch);

        verify(platformStateUpdates, never()).handleTxBody(stack, CRYPTO_TRANSFER_TXN_INFO.txBody(), dispatch.config());
        verify(recordBuilder).status(SUCCESS);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished(IsRootStack.NO);
    }

    @Test
    void unableToAffordServiceFeesChargesAccordingly() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newPayerError(
                        CREATOR_ACCOUNT_ID,
                        PAYER,
                        INSUFFICIENT_ACCOUNT_BALANCE,
                        UNABLE_TO_PAY_SERVICE_FEE,
                        NO_DUPLICATE));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(recordBuilder).status(INSUFFICIENT_ACCOUNT_BALANCE);
        verifyNoInteractions(dispatcher);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished();
    }

    @Test
    void duplicateChargesAccordingly() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newPayerDuplicateError(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES.withoutServiceComponent(), null);
        verify(recordBuilder).status(DUPLICATE_TRANSACTION);
        verifyNoInteractions(dispatcher);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());
        assertFinished();
    }

    @Test
    void unauthorizedNodeCreateWhenPayerNotTreasurySysAdminAddressBookAdmin() {
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(NODE_CREATE_TXN_INFO);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, NODE_CREATE_TXN_INFO.functionality()))
                .willReturn(false);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);

        subject.processDispatch(dispatch);

        verifyTrackedFeePayments();
        verify(feeAccumulator).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(recordBuilder).status(UNAUTHORIZED);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

        assertFinished();
    }

    @Test
    void nestedSystemFileUpdateIsResyncedOnceTransactionCommits() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        // First a dispatch within a transaction, then the transaction's root dispatch
        given(stack.isRoot()).willReturn(false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));

        subject.processDispatch(dispatch);
        verify(systemFileUpdates, never()).resyncFromState(any(), any());

        subject.processDispatch(dispatch);
        verify(systemFileUpdates).resyncFromState(stack, RATES_TARGET);
    }

    @Test
    void facilityUpdatedTwiceInATransactionIsCheckedOnceAgainstTheLatestAppliedContents() {
        final var laterRatesTarget = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES,
                RATES_TARGET.fileId(),
                Bytes.wrap("applied later"));
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        // First a dispatch within a transaction, then the transaction's root dispatch, both updating the rates
        given(stack.isRoot()).willReturn(false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(RATES_TARGET), Optional.of(laterRatesTarget));

        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);

        verify(systemFileUpdates).resyncFromState(stack, laterRatesTarget);
        verify(systemFileUpdates, never()).resyncFromState(stack, RATES_TARGET);
    }

    @Test
    void topLevelSystemFileUpdateIsCheckedAgainstCommittedState() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));

        subject.processDispatch(dispatch);

        verify(systemFileUpdates).resyncFromState(stack, RATES_TARGET);
    }

    @Test
    void systemFileUpdateIsResyncedEvenIfApplyingItThrows() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new IllegalStateException("partly applied"));

        subject.processDispatch(dispatch);

        // The dispatch was rolled back, so its target is recovered right then from the rolled-back stack, and the
        // root pass does not consider it again
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue()).isEqualTo(RATES_TARGET);
        assertThat(captor.getValue().rolledBack()).isTrue();
        verify(systemFileUpdates, never()).needsResync(any(), any());
    }

    @Test
    void rolledBackDispatchRecoversItsTargetBeforeUsageIsFinalized() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));

        subject.processDispatch(dispatch);

        final var inOrder = inOrder(systemFileUpdates, stack, dispatchUsageManager);
        inOrder.verify(stack).rollbackFullStack();
        inOrder.verify(systemFileUpdates).resyncFromState(eq(stack), any());
        inOrder.verify(dispatchUsageManager).finalizeAndSaveUsage(dispatch);
        // Recovered at the rollback, the target is no longer pending for the root pass
        verify(systemFileUpdates, never()).needsResync(any(), any());
        verify(recordBuilder).status(INVALID_FEE_FILE);
    }

    @Test
    void rolledBackChildDispatchRecoversOnlyItsOwnTargetAndLeavesTheParentsForTheRoot() {
        final var childStack = mock(SavepointStackImpl.class);
        final var feesTarget = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES,
                FileID.newBuilder().fileNum(113).build(),
                Bytes.wrap("applied fees"));
        // The root dispatch records the rates target, then a child dispatch records the fees target and fails
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        given(dispatch.stack()).willReturn(stack);
        subject.processDispatch(dispatch);
        verify(systemFileUpdates, never()).resyncFromState(eq(stack), eq(feesTarget));

        given(dispatch.stack()).willReturn(childStack);
        given(childStack.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(childStack, TXN_BODY)).willReturn(Optional.of(feesTarget));
        given(systemFileUpdates.handleTxBody(childStack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        subject.processDispatch(dispatch);

        // Only the child's own target is recovered at its rollback, from the child's rolled-back stack
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(childStack), captor.capture());
        assertThat(captor.getValue()).isEqualTo(feesTarget);
        verify(systemFileUpdates, never()).resyncFromState(eq(childStack), eq(RATES_TARGET));
    }

    @Test
    void rolledBackParentRecoversTheTargetsOfItsCommittedChildren() {
        final var childStack = mock(SavepointStackImpl.class);
        given(childStack.parent()).willReturn(stack);
        // A child dispatch records a target and commits into its parent
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.stack()).willReturn(childStack);
        given(childStack.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(childStack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        given(systemFileUpdates.handleTxBody(childStack, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        verify(systemFileUpdates, never()).resyncFromState(any(), any());

        // The parent (root) dispatch then fails: the child's target is recovered from the parent's rolled-back stack
        given(dispatch.stack()).willReturn(stack);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.empty());
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        subject.processDispatch(dispatch);

        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue()).isEqualTo(RATES_TARGET);
        assertThat(captor.getValue().rolledBack()).isTrue();
    }

    @Test
    void laterFailedUpdateOfAFacilityRecoversWithItsOwnBaselineAndLeavesTheEarlierCommittedUpdatePending() {
        final var earlierBaseline = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(1L, null), null, null);
        final var laterBaseline = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(2L, null), new ThrottleUsageSnapshot(2L, null), null, null);
        final var fileId = FileID.newBuilder().fileNum(123).build();
        final var earlierUpdate = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                fileId,
                Bytes.wrap("B"),
                null,
                null,
                earlierBaseline,
                null,
                false);
        final var laterUpdate = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                fileId,
                Bytes.wrap("C"),
                null,
                null,
                laterBaseline,
                null,
                false);
        final var firstChild = mock(SavepointStackImpl.class);
        final var secondChild = mock(SavepointStackImpl.class);
        // A first child dispatch applies A->B and commits
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.stack()).willReturn(firstChild);
        given(firstChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(firstChild, TXN_BODY)).willReturn(Optional.of(earlierUpdate));
        given(systemFileUpdates.handleTxBody(firstChild, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        // A second child dispatch applies B->C and fails
        given(dispatch.stack()).willReturn(secondChild);
        given(secondChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(secondChild, TXN_BODY)).willReturn(Optional.of(laterUpdate));
        given(systemFileUpdates.handleTxBody(secondChild, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        subject.processDispatch(dispatch);

        // Only B->C is recovered at its rollback, restoring the usage captured before it (B's), not A's
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(secondChild), captor.capture());
        assertThat(captor.getValue()).isEqualTo(laterUpdate);
        assertThat(captor.getValue().priorThrottleUsage()).isSameAs(laterBaseline);

        // A->B is still pending for the root pass, where it needs no recovery because it committed
        given(dispatch.stack()).willReturn(stack);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.empty());
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willReturn(SUCCESS);
        given(systemFileUpdates.needsResync(stack, earlierUpdate)).willReturn(false);
        subject.processDispatch(dispatch);
        verify(systemFileUpdates).needsResync(stack, earlierUpdate);
        verify(systemFileUpdates, never()).resyncFromState(eq(stack), any());
    }

    @Test
    void anUpdateWhoseBoundaryRecoveryFailsStaysPendingForTheAbandonmentPass() {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        final var failure = new IllegalStateException("cannot re-derive yet");
        doThrow(failure).doNothing().when(systemFileUpdates).resyncFromState(eq(stack), any());

        // The failed boundary recovery surfaces, which abandons the transaction
        assertThatThrownBy(() -> subject.processDispatch(dispatch)).isSameAs(failure);

        // The abandonment pass still finds the update and recovers it from the state that remains
        subject.resyncAfterAbandonedTransaction(stack);
        verify(systemFileUpdates, times(2)).resyncFromState(eq(stack), any());
    }

    @Test
    void severalRolledBackUpdatesOfOneFacilityAreRecoveredOnceWithTheEarliestBaselines() {
        final var fileId = FileID.newBuilder().fileNum(121).build();
        final var first = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                fileId,
                Bytes.wrap("B"),
                null,
                null,
                null,
                BlockStreamWriterMode.FILE_AND_GRPC,
                false);
        final var second = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                fileId,
                Bytes.wrap("C"),
                null,
                null,
                null,
                BlockStreamWriterMode.FILE,
                false);
        final var firstChild = mock(SavepointStackImpl.class);
        final var secondChild = mock(SavepointStackImpl.class);
        given(firstChild.parent()).willReturn(stack);
        given(secondChild.parent()).willReturn(stack);
        // Two child dispatches update the same facility and commit into the root
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.stack()).willReturn(firstChild);
        given(firstChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(firstChild, TXN_BODY)).willReturn(Optional.of(first));
        given(systemFileUpdates.handleTxBody(firstChild, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        given(dispatch.stack()).willReturn(secondChild);
        given(secondChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(secondChild, TXN_BODY)).willReturn(Optional.of(second));
        given(systemFileUpdates.handleTxBody(secondChild, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        // The root dispatch then fails, rolling both back
        given(dispatch.stack()).willReturn(stack);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.empty());
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        subject.processDispatch(dispatch);

        // One recovery: the latest update's file and contents, the earliest update's baselines
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates, times(1)).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue().appliedContents()).isEqualTo(Bytes.wrap("C"));
        assertThat(captor.getValue().priorWriterMode()).isEqualTo(BlockStreamWriterMode.FILE_AND_GRPC);
        assertThat(captor.getValue().rolledBack()).isTrue();
    }

    @Test
    void aFacilityWhoseBoundaryRecoveryFailsRetriesWithTheSharedBaseline() {
        final var earliest = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(1L, null), null, null);
        final var later = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(0L, null), new ThrottleUsageSnapshot(0L, null), null, null);
        final var definitions = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                FileID.newBuilder().fileNum(123).build(),
                Bytes.wrap("defs"),
                null,
                null,
                earliest,
                null,
                false);
        final var properties = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                FileID.newBuilder().fileNum(121).build(),
                Bytes.wrap("props"),
                null,
                null,
                later,
                null,
                false);
        final var firstChild = mock(SavepointStackImpl.class);
        final var secondChild = mock(SavepointStackImpl.class);
        given(firstChild.parent()).willReturn(stack);
        given(secondChild.parent()).willReturn(stack);
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.stack()).willReturn(firstChild);
        given(firstChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(firstChild, TXN_BODY)).willReturn(Optional.of(definitions));
        given(systemFileUpdates.handleTxBody(firstChild, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        given(dispatch.stack()).willReturn(secondChild);
        given(secondChild.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(secondChild, TXN_BODY)).willReturn(Optional.of(properties));
        given(systemFileUpdates.handleTxBody(secondChild, TXN_BODY)).willReturn(SUCCESS);
        subject.processDispatch(dispatch);
        // The root fails; the definitions recover but the properties recovery throws
        given(dispatch.stack()).willReturn(stack);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.empty());
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willThrow(new HandleException(INVALID_FEE_FILE));
        final var failure = new IllegalStateException("properties not recoverable yet");
        final var failedOnce = new AtomicBoolean();
        doAnswer(inv -> {
                    final SystemFileUpdates.ResyncTarget target = inv.getArgument(1);
                    if (target.facility() == SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES
                            && failedOnce.compareAndSet(false, true)) {
                        throw failure;
                    }
                    return null;
                })
                .when(systemFileUpdates)
                .resyncFromState(eq(stack), any());
        assertThatThrownBy(() -> subject.processDispatch(dispatch)).isSameAs(failure);

        subject.resyncAfterAbandonedTransaction(stack);

        // The retry restores the baseline shared at the boundary (the earliest), not the properties update's own
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates, times(3)).resyncFromState(eq(stack), captor.capture());
        final var retried = captor.getAllValues().getLast();
        assertThat(retried.facility()).isEqualTo(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES);
        assertThat(retried.priorThrottleUsage()).isSameAs(earliest);
    }

    @Test
    void rootPassTakesBaselinesFromTheFirstUpdateAfterTheLastSurvivingOne() {
        final var fileId = FileID.newBuilder().fileNum(123).build();
        final var baselineB = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(1L, null), null, null);
        final var baselineC = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(2L, null), new ThrottleUsageSnapshot(2L, null), null, null);
        final var baselineD = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(3L, null), new ThrottleUsageSnapshot(3L, null), null, null);
        final var updateB = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                fileId,
                Bytes.wrap("B"),
                null,
                null,
                baselineB,
                null,
                false);
        // Distinct contents, so each update is its own stubbing target (equality ignores the baselines)
        final var updateC = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                fileId,
                Bytes.wrap("C"),
                null,
                null,
                baselineC,
                null,
                false);
        final var updateD = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                fileId,
                Bytes.wrap("D"),
                null,
                null,
                baselineD,
                null,
                false);
        // Three child dispatches update the facility in turn: A->B, B->C and C->D
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(false, false, false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(updateB), Optional.of(updateC), Optional.of(updateD), Optional.empty());
        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        // C->D was reverted by a frame the processor did not see: the file holds C, so B and D no longer match
        // the state but C does
        // B is never asked once C is found to survive, so its stub is only needed by the rule under test's negation
        lenient().when(systemFileUpdates.needsResync(stack, updateB)).thenReturn(true);
        given(systemFileUpdates.needsResync(stack, updateC)).willReturn(false);
        given(systemFileUpdates.needsResync(stack, updateD)).willReturn(true);
        subject.processDispatch(dispatch);

        // The baselines are D's (captured when C was active), not B's
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue().appliedContents()).isEqualTo(Bytes.wrap("D"));
        assertThat(captor.getValue().priorThrottleUsage()).isSameAs(baselineD);
    }

    @Test
    void rootPassSharesTheChronologicallyEarliestSelectedBaselineAcrossFacilities() {
        final var baselineN1 = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(1L, null), null, null);
        final var baselineT1 = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(2L, null), new ThrottleUsageSnapshot(2L, null), null, null);
        final var baselineN2 = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(0L, null), new ThrottleUsageSnapshot(0L, null), null, null);
        final var propertiesId = FileID.newBuilder().fileNum(121).build();
        final var definitionsId = FileID.newBuilder().fileNum(123).build();
        final var n1 = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                propertiesId,
                Bytes.wrap("N1"),
                null,
                null,
                baselineN1,
                null,
                false);
        final var t1 = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                definitionsId,
                Bytes.wrap("T1"),
                null,
                null,
                baselineT1,
                null,
                false);
        final var n2 = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                propertiesId,
                Bytes.wrap("N2"),
                null,
                null,
                baselineN2,
                null,
                false);
        // N1 commits; then a frame the processor does not see applies T1 and N2 and reverts
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(false, false, false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(n1), Optional.of(t1), Optional.of(n2), Optional.empty());
        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        given(systemFileUpdates.needsResync(stack, n1)).willReturn(false);
        given(systemFileUpdates.needsResync(stack, t1)).willReturn(true);
        given(systemFileUpdates.needsResync(stack, n2)).willReturn(true);
        subject.processDispatch(dispatch);

        // Both facilities restore T1's baseline, the earliest one captured among the rolled-back updates; N2's was
        // captured after T1's rebuild had already discarded the usage
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates, times(2)).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SystemFileUpdates.ResyncTarget::priorThrottleUsage)
                .containsOnly(baselineT1);
        assertThat(captor.getAllValues().getFirst().facility())
                .isEqualTo(SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS);
    }

    @Test
    void abandonmentAfterTheRootPassStillRecoversPendingFacilities() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        // Nothing to recover at the root pass, but the commit fails afterwards
        given(systemFileUpdates.needsResync(stack, RATES_TARGET)).willReturn(false);
        doThrow(new IllegalStateException("commit failed")).when(stack).commitFullStack();

        assertThatThrownBy(() -> subject.processDispatch(dispatch)).isInstanceOf(IllegalStateException.class);
        verify(systemFileUpdates, never()).resyncFromState(any(), any());

        // The abandoned-transaction path still finds the target and recovers it from the state that remains
        subject.resyncAfterAbandonedTransaction(stack);

        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue()).isEqualTo(RATES_TARGET);
        assertThat(captor.getValue().rolledBack()).isTrue();
    }

    @Test
    void committedDispatchLeavesItsPendingTargetNotRolledBack() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));

        subject.processDispatch(dispatch);

        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(stack), captor.capture());
        assertThat(captor.getValue().rolledBack()).isFalse();
    }

    @Test
    void facilitiesAreResyncedBeforeThrottleUsageIsFinalizedAndSaved() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        final var propertiesTarget = targetOf(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(propertiesTarget));

        subject.processDispatch(dispatch);

        // A restored throttle usage must be what gets saved, so recovery has to run before the usage is finalized;
        // the streaming transition, which needs the committed configuration, is queued only after the commit
        final var inOrder = inOrder(systemFileUpdates, dispatchUsageManager, stack);
        inOrder.verify(systemFileUpdates).resyncFromState(eq(stack), any());
        inOrder.verify(dispatchUsageManager).finalizeAndSaveUsage(dispatch);
        inOrder.verify(stack).commitFullStack();
        inOrder.verify(systemFileUpdates).reconcileBlockNodeStreaming(propertiesTarget);
    }

    @Test
    void streamingTransitionIsNotQueuedForAFacilityThatIsNotAConfigurationFile() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));

        subject.processDispatch(dispatch);

        verify(stack).commitFullStack();
        verify(systemFileUpdates, never()).reconcileBlockNodeStreaming(any());
    }

    @Test
    void streamingConfigurationIsReleasedBeforeTheStreamingTransitionIsQueued() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        final var propertiesTarget = targetOf(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(propertiesTarget));

        subject.processDispatch(dispatch);

        // The transition runs on another thread as soon as it is queued, and must find the committed configuration
        final var inOrder = inOrder(systemFileUpdates, stack);
        inOrder.verify(stack).commitFullStack();
        inOrder.verify(systemFileUpdates).releaseStreamingConfiguration();
        inOrder.verify(systemFileUpdates).reconcileBlockNodeStreaming(propertiesTarget);
    }

    @Test
    void streamingTransitionIsJudgedAgainstTheEarliestConfigurationUpdateOfTheTransaction() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        // A rates update, then a permissions update, then a properties update, then the root dispatch
        given(stack.isRoot()).willReturn(false, false, false, true);
        final var permissionsTarget = targetOf(SystemFileUpdates.ResyncTarget.Facility.HAPI_PERMISSIONS);
        final var propertiesTarget = targetOf(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(
                        Optional.of(RATES_TARGET),
                        Optional.of(permissionsTarget),
                        Optional.of(propertiesTarget),
                        Optional.empty());

        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);
        verify(systemFileUpdates, never()).reconcileBlockNodeStreaming(any());
        subject.processDispatch(dispatch);

        // Only the earliest configuration update saw the writer mode from before the transaction; the later one saw
        // the mode the earlier one proposed
        verify(systemFileUpdates).reconcileBlockNodeStreaming(permissionsTarget);
        verify(systemFileUpdates, never()).reconcileBlockNodeStreaming(propertiesTarget);
        verify(systemFileUpdates, never()).reconcileBlockNodeStreaming(RATES_TARGET);
    }

    private static SystemFileUpdates.ResyncTarget targetOf(final SystemFileUpdates.ResyncTarget.Facility facility) {
        return new SystemFileUpdates.ResyncTarget(facility, RATES_TARGET.fileId(), RATES_TARGET.appliedContents());
    }

    @Test
    void abandonedTransactionResyncsPendingFacilitiesFromRemainingState() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(RATES_TARGET));
        subject.processDispatch(dispatch);

        subject.resyncAfterAbandonedTransaction(stack);
        subject.resyncAfterAbandonedTransaction(stack);

        verify(systemFileUpdates, times(1)).resyncFromState(stack, RATES_TARGET);
    }

    @Test
    void nestedNodeUpdateRefreshesNodeInfoAgainOnceTransactionCommits() {
        givenSuccessfulDispatch(NODE_UPDATE_TXN_INFO);
        // First a dispatch within a transaction, then the transaction's root dispatch
        given(stack.isRoot()).willReturn(false, true);

        subject.processDispatch(dispatch);
        verify(networkInfo).updateFrom(stack);

        subject.processDispatch(dispatch);
        // Once by the root dispatch's own update, and once more from the committed state
        verify(networkInfo, times(3)).updateFrom(stack);
    }

    private void givenSuccessfulDispatch(@NonNull final TransactionInfo txnInfo) {
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(txnInfo);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        givenAuthorization(txnInfo);
        givenSystemEffectSuccess(txnInfo);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
    }

    private void givenSystemEffectSuccess(@NonNull final TransactionInfo txnInfo) {
        given(systemFileUpdates.handleTxBody(stack, txnInfo.txBody())).willReturn(SUCCESS);
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        given(recordBuilder.exchangeRate(ExchangeRateSet.DEFAULT)).willReturn(recordBuilder);
    }

    private void givenAuthorization() {
        givenAuthorization(CRYPTO_TRANSFER_TXN_INFO);
    }

    private void givenAuthorization(@NonNull final TransactionInfo txnInfo) {
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, txnInfo.functionality()))
                .willReturn(true);
        given(authorizer.hasPrivilegedAuthorization(PAYER_ACCOUNT_ID, txnInfo.functionality(), TXN_BODY))
                .willReturn(UNNECESSARY);
    }

    private enum IsRootStack {
        YES,
        NO
    }

    private void assertFinished() {
        assertFinished(IsRootStack.YES);
    }

    private void assertFinished(@NonNull final IsRootStack isRootStack) {
        verify(recordFinalizer).finalizeRecord(dispatch);
        verify(stack).commitFullStack();
    }

    private void verifyTrackedFeePayments() {
        verify(dispatchUsageManager).finalizeAndSaveUsage(dispatch);
    }

    private void verifyUtilization() {
        verify(dispatchUsageManager).finalizeAndSaveUsage(dispatch);
    }

    @ParameterizedTest
    @EnumSource(
            value = SystemFileUpdates.ResyncTarget.Facility.class,
            names = {"NETWORK_PROPERTIES", "HAPI_PERMISSIONS"})
    void streamingConfigurationIsHeldBeforeAConfigurationFileUpdateAppliesAndReleasedOnceTheTransactionCommits(
            final SystemFileUpdates.ResyncTarget.Facility facility) {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        // First a dispatch within a transaction, then the transaction's root dispatch
        given(stack.isRoot()).willReturn(false, true);
        // Both files are sources of the configuration, so an update of either can change streaming settings
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(targetOf(facility)), Optional.empty());

        subject.processDispatch(dispatch);

        // The update is still tentative after the dispatch that applied it, so the configuration stays held
        final var inOrder = inOrder(systemFileUpdates, stack);
        inOrder.verify(systemFileUpdates).holdStreamingConfiguration();
        inOrder.verify(systemFileUpdates).handleTxBody(stack, TXN_BODY);
        verify(systemFileUpdates, never()).releaseStreamingConfiguration();

        subject.processDispatch(dispatch);

        inOrder.verify(stack).commitFullStack();
        inOrder.verify(systemFileUpdates).releaseStreamingConfiguration();
    }

    @ParameterizedTest
    @EnumSource(
            value = SystemFileUpdates.ResyncTarget.Facility.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"NETWORK_PROPERTIES", "HAPI_PERMISSIONS"})
    void streamingConfigurationIsNotHeldForOtherFacilities(final SystemFileUpdates.ResyncTarget.Facility facility) {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY)).willReturn(Optional.of(targetOf(facility)));

        subject.processDispatch(dispatch);

        verify(systemFileUpdates, never()).holdStreamingConfiguration();
    }

    @Test
    void streamingConfigurationIsReleasedWhenATransactionIsAbandoned() {
        givenSuccessfulDispatch(CRYPTO_TRANSFER_TXN_INFO);
        given(stack.isRoot()).willReturn(false);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                        RATES_TARGET.fileId(),
                        RATES_TARGET.appliedContents())));
        subject.processDispatch(dispatch);
        verify(systemFileUpdates).holdStreamingConfiguration();
        verify(systemFileUpdates, never()).releaseStreamingConfiguration();

        // The transaction never reaches its root commit
        subject.resyncAfterAbandonedTransaction(stack);

        verify(systemFileUpdates).releaseStreamingConfiguration();
    }
}
