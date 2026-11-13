// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.UNNECESSARY;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.DispatchValidator;
import com.hedera.node.app.workflows.handle.dispatch.RecordFinalizer;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.PlatformStateUpdates;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Uses real savepoint stacks to check that {@link DispatchProcessor} recovers an in-memory facility update at the
 * moment the savepoint holding the update's changes is rolled back by the handler that dispatched it, as when a
 * contract frame reverts around a completed child dispatch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchProcessorFrameRollbackTest {
    private static final Fees FEES = new Fees(1L, 2L, 3L);
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final Account PAYER =
            Account.newBuilder().accountId(PAYER_ACCOUNT_ID).build();
    private static final AccountID CREATOR_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final TransactionBody TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .build();
    private static final TransactionInfo TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT, TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);
    private static final FileID THROTTLES_FILE =
            FileID.newBuilder().fileNum(123).build();
    private static final SystemFileUpdates.ConfigRecoveryContext FIRST_CONTEXT =
            new SystemFileUpdates.ConfigRecoveryContext(
                    FileID.newBuilder().fileNum(121).build(),
                    FileID.newBuilder().fileNum(122).build());

    @Mock
    private EthereumTransactionHandler ethereumTransactionHandler;

    @Mock
    private Authorizer authorizer;

    @Mock
    private DispatchUsageManager dispatchUsageManager;

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
    private State baseState;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private ImmediateStateChangeListener immediateStateChangeListener;

    private SavepointStackImpl rootStack;
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
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER)).willReturn(true);
        given(authorizer.hasPrivilegedAuthorization(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER, TXN_BODY))
                .willReturn(UNNECESSARY);
        given(systemFileUpdates.handleTxBody(any(), eq(TXN_BODY))).willReturn(SUCCESS);
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        given(recordBuilder.exchangeRate(ExchangeRateSet.DEFAULT)).willReturn(recordBuilder);
        rootStack = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                boundaryStateChangeListener,
                immediateStateChangeListener,
                StreamMode.RECORDS,
                TraceDataSizeLimiter.NO_LIMIT);
        initialized(rootStack);
    }

    @Test
    void updateIsRecoveredWhenTheFrameItWasCommittedIntoIsRolledBack() {
        final var target = throttlesTarget("applied definitions");
        // The contract call opens a frame, and a dispatch made within that frame updates the throttle definitions
        rootStack.createSavepoint();
        final var updateStack = childOf(rootStack);
        given(systemFileUpdates.resyncTarget(updateStack, TXN_BODY)).willReturn(Optional.of(target));
        subject.processDispatch(dispatchOn(updateStack));
        verify(systemFileUpdates, never()).resyncFromState(any(), any());

        // The frame reverts
        rootStack.rollback();

        // The update is recovered right then, from the stack that no longer holds it, as a rolled-back update
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(rootStack), captor.capture());
        assertThat(captor.getValue().rolledBack()).isTrue();
        assertThat(captor.getValue().appliedContents()).isEqualTo(Bytes.wrap("applied definitions"));

        // The contract call then completes; the recovered update is no longer pending, so nothing runs again
        given(systemFileUpdates.resyncTarget(rootStack, TXN_BODY)).willReturn(Optional.empty());
        subject.processDispatch(dispatchOn(rootStack));

        verify(systemFileUpdates, times(1)).resyncFromState(any(), any());
        verify(systemFileUpdates, never()).needsResync(any(), any());
    }

    @Test
    void updatesThatCancelOutWithinARolledBackFrameAreStillRecovered() {
        // Two updates in one frame, the second restoring the contents the file had before the first: the committed
        // file ends up unchanged, so only the frame's own outcome shows that memory has to be re-derived
        final var forward = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                FileID.newBuilder().fileNum(121).build(),
                Bytes.wrap("changed properties"),
                FIRST_CONTEXT);
        final var backward = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                FileID.newBuilder().fileNum(121).build(),
                Bytes.wrap("original properties"),
                new SystemFileUpdates.ConfigRecoveryContext(
                        FileID.newBuilder().fileNum(150).build(),
                        FileID.newBuilder().fileNum(151).build()));
        rootStack.createSavepoint();
        final var forwardStack = childOf(rootStack);
        given(systemFileUpdates.resyncTarget(forwardStack, TXN_BODY)).willReturn(Optional.of(forward));
        subject.processDispatch(dispatchOn(forwardStack));
        final var backwardStack = childOf(rootStack);
        given(systemFileUpdates.resyncTarget(backwardStack, TXN_BODY)).willReturn(Optional.of(backward));
        subject.processDispatch(dispatchOn(backwardStack));

        rootStack.rollback();

        // One recovery, forced by the rollback, with the latest contents and the baselines of the first update
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(rootStack), captor.capture());
        assertThat(captor.getValue().rolledBack()).isTrue();
        assertThat(captor.getValue().appliedContents()).isEqualTo(Bytes.wrap("original properties"));
        assertThat(captor.getValue().configContext()).isEqualTo(FIRST_CONTEXT);
        verify(systemFileUpdates, never()).needsResync(any(), any());
    }

    @Test
    void updateCommittedIntoAFrameThatCommitsStaysPendingUntilTheTransactionCompletes() {
        final var target = throttlesTarget("applied definitions");
        rootStack.createSavepoint();
        final var updateStack = childOf(rootStack);
        given(systemFileUpdates.resyncTarget(updateStack, TXN_BODY)).willReturn(Optional.of(target));
        subject.processDispatch(dispatchOn(updateStack));

        // The frame completes normally, and so does the contract call
        rootStack.commit();
        given(systemFileUpdates.resyncTarget(rootStack, TXN_BODY)).willReturn(Optional.empty());
        subject.processDispatch(dispatchOn(rootStack));

        // The update was never rolled back; only the end-of-transaction comparison looked at it
        verify(systemFileUpdates).needsResync(rootStack, target);
        verify(systemFileUpdates, never()).resyncFromState(any(), any());
    }

    @Test
    void updateIsRecoveredWhenTheDispatchEnclosingItsFrameFailsAfterTheFrameCommitted() {
        final var target = throttlesTarget("applied definitions");
        final var callStack = childOf(rootStack);
        callStack.createSavepoint();
        final var updateStack = childOf(callStack);
        given(systemFileUpdates.resyncTarget(updateStack, TXN_BODY)).willReturn(Optional.of(target));
        subject.processDispatch(dispatchOn(updateStack));
        callStack.commit();
        verify(systemFileUpdates, never()).resyncFromState(any(), any());

        // The contract call itself is then rolled back in full
        callStack.rollbackFullStack();

        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncFromState(eq(callStack), captor.capture());
        assertThat(captor.getValue().rolledBack()).isTrue();
    }

    @Test
    void updateWhoseRecoveryFailedIsRecoveredTogetherWithTheEnclosingRollbackFromTheEarliestBaseline() {
        final var earlierBaseline = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(1L, null), null, null);
        final var laterBaseline = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(2L, null), new ThrottleUsageSnapshot(2L, null), null, null);
        final var earlier = throttlesTarget("first definitions", earlierBaseline);
        final var later = throttlesTarget("second definitions", laterBaseline);
        final var callStack = childOf(rootStack);
        final var earlierStack = childOf(callStack);
        given(systemFileUpdates.resyncTarget(earlierStack, TXN_BODY)).willReturn(Optional.of(earlier));
        subject.processDispatch(dispatchOn(earlierStack));
        callStack.createSavepoint();
        final var laterStack = childOf(callStack);
        given(systemFileUpdates.resyncTarget(laterStack, TXN_BODY)).willReturn(Optional.of(later));
        subject.processDispatch(dispatchOn(laterStack));
        final var failure = new IllegalStateException("cannot re-derive the throttles");
        doThrow(failure).doNothing().when(systemFileUpdates).resyncFromState(any(), any());

        // The frame holding the later update reverts, and its recovery fails
        assertThatThrownBy(callStack::rollback).isSameAs(failure);
        // The failure then rolls back the whole call, which also held the earlier update
        callStack.rollbackFullStack();

        // Both updates are recovered as one: the later contents, with the usage captured before the earlier update
        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates, times(2)).resyncFromState(eq(callStack), captor.capture());
        final var recovered = captor.getAllValues().get(1);
        assertThat(recovered.appliedContents()).isEqualTo(Bytes.wrap("second definitions"));
        assertThat(recovered.priorThrottleUsage()).isSameAs(earlierBaseline);
        assertThat(recovered.rolledBack()).isTrue();

        // Nothing is left pending for the end of the transaction
        given(systemFileUpdates.resyncTarget(rootStack, TXN_BODY)).willReturn(Optional.empty());
        subject.processDispatch(dispatchOn(rootStack));
        verify(systemFileUpdates, times(2)).resyncFromState(any(), any());
        verify(systemFileUpdates, never()).needsResync(any(), any());
    }

    private static SystemFileUpdates.ResyncTarget throttlesTarget(
            final String appliedContents, final ThrottleServiceManager.PreservedThrottleUsage priorUsage) {
        return new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                THROTTLES_FILE,
                Bytes.wrap(appliedContents),
                null,
                null,
                priorUsage,
                null,
                false);
    }

    private static SystemFileUpdates.ResyncTarget throttlesTarget(final String appliedContents) {
        return new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                THROTTLES_FILE,
                Bytes.wrap(appliedContents));
    }

    private static SavepointStackImpl childOf(final SavepointStackImpl parentStack) {
        return initialized(SavepointStackImpl.newChildStack(
                parentStack, REVERSIBLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.RECORDS));
    }

    private static SavepointStackImpl initialized(final SavepointStackImpl stack) {
        stack.getBaseBuilder(StreamBuilder.class)
                .signedTx(SignedTransaction.DEFAULT)
                .status(ResponseCodeEnum.SUCCESS)
                .exchangeRate(ExchangeRateSet.DEFAULT);
        return stack;
    }

    private Dispatch dispatchOn(final SavepointStackImpl stack) {
        final var dispatch = mock(Dispatch.class);
        given(dispatch.stack()).willReturn(stack);
        given(dispatch.streamBuilder()).willReturn(recordBuilder);
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
        lenient().when(dispatch.config()).thenReturn(null);
        return dispatch;
    }
}
