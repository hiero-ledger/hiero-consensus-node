// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_UNDELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
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
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.DispatchValidator;
import com.hedera.node.app.workflows.handle.dispatch.RecordFinalizer;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.PlatformStateUpdates;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager;
import com.hedera.node.app.workflows.handle.throttle.ThrottleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
            Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);
    private static final TransactionInfo SYS_DEL_TXN_INFO =
            new TransactionInfo(Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, SYSTEM_DELETE, null);
    private static final TransactionInfo SYS_UNDEL_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, SYSTEM_UNDELETE, null);
    private static final TransactionInfo CONTRACT_TXN_INFO =
            new TransactionInfo(Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CONTRACT_CALL, null);
    private static final TransactionInfo ETH_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, ETHEREUM_TRANSACTION, null);
    private static final TransactionInfo NODE_CREATE_TXN_INFO =
            new TransactionInfo(Transaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, NODE_CREATE, null);

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

    private DispatchProcessor subject;

    @BeforeEach
    void setUp() {
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
        verify(feeAccumulator, times(2)).chargeFees(PAYER_ACCOUNT_ID, CREATOR_ACCOUNT_ID, FEES, null);
        verify(opWorkflowMetrics, never()).incrementThrottled(any());

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
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(CRYPTO_TRANSFER_TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
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
}
