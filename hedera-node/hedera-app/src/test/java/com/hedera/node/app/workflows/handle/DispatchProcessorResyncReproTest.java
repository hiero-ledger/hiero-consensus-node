// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.UNNECESSARY;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.workflows.handle.dispatch.ValidationResult.newSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
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
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Reproduces that one facility whose re-derivation fails stops {@link DispatchProcessor} from re-deriving the other
 * facilities its transaction updated in memory.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchProcessorResyncReproTest {
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
    private static final SystemFileUpdates.ResyncTarget RATES_TARGET = new SystemFileUpdates.ResyncTarget(
            SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES,
            FileID.newBuilder().fileNum(112).build(),
            Bytes.wrap("applied rates"));
    private static final SystemFileUpdates.ResyncTarget FEES_TARGET = new SystemFileUpdates.ResyncTarget(
            SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES,
            FileID.newBuilder().fileNum(113).build(),
            Bytes.wrap("applied fees"));

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
        given(dispatch.fees()).willReturn(FEES);
        given(dispatch.feeAccumulator()).willReturn(feeAccumulator);
        given(dispatchValidator.validateFeeChargingScenario(dispatch))
                .willReturn(newSuccess(CREATOR_ACCOUNT_ID, PAYER));
        given(dispatch.payerId()).willReturn(PAYER_ACCOUNT_ID);
        given(dispatch.txnInfo()).willReturn(TXN_INFO);
        given(dispatch.handleContext()).willReturn(context);
        given(dispatch.txnCategory()).willReturn(USER);
        given(authorizer.isAuthorized(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER)).willReturn(true);
        given(authorizer.hasPrivilegedAuthorization(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER, TXN_BODY))
                .willReturn(UNNECESSARY);
        given(systemFileUpdates.handleTxBody(stack, TXN_BODY)).willReturn(SUCCESS);
        given(exchangeRateManager.exchangeRates()).willReturn(ExchangeRateSet.DEFAULT);
        given(recordBuilder.exchangeRate(ExchangeRateSet.DEFAULT)).willReturn(recordBuilder);
        doCallRealMethod().when(dispatch).charge(any(), any(), any(), any());
        doCallRealMethod().when(dispatch).category();
        doCallRealMethod().when(dispatch).feeChargingOrElse(any());
        given(dispatch.nodeAccountId()).willReturn(CREATOR_ACCOUNT_ID);
    }

    @Test
    void failureRederivingOneFacilityStillRederivesTheOthers() {
        // A dispatch within the transaction updates the rates, then the root dispatch updates the fee schedule
        given(stack.isRoot()).willReturn(false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(RATES_TARGET), Optional.of(FEES_TARGET));
        doThrow(new IllegalStateException("cannot re-derive the rates"))
                .when(systemFileUpdates)
                .resyncIfChanged(stack, RATES_TARGET);

        subject.processDispatch(dispatch);
        // Whether or not the failure propagates, the handle workflow then re-derives from the remaining state
        try {
            subject.processDispatch(dispatch);
        } catch (final IllegalStateException ignore) {
            try {
                subject.resyncAfterAbandonedTransaction(stack);
            } catch (final IllegalStateException alsoIgnore) {
                // The rates still cannot be re-derived
            }
        }

        verify(systemFileUpdates, atLeastOnce()).resyncIfChanged(stack, FEES_TARGET);
    }

    @Test
    void multipleUpdatesOfOneFacilityKeepTheEarliestContextAndTheLatestAppliedContents() {
        final var firstContext = new SystemFileUpdates.ConfigRecoveryContext(
                FileID.newBuilder().fileNum(121).build(),
                FileID.newBuilder().fileNum(122).build(),
                null);
        final var laterContext = new SystemFileUpdates.ConfigRecoveryContext(
                FileID.newBuilder().fileNum(150).build(),
                FileID.newBuilder().fileNum(151).build(),
                null);
        // The two updates target different file numbers (the first update remapped the backing file) and carry
        // different captured baselines, so the merged target's fields are distinguishable
        final var firstFileId = FileID.newBuilder().fileNum(121).build();
        final var laterFileId = FileID.newBuilder().fileNum(150).build();
        final var firstPriorFees = FeeSchedule.DEFAULT;
        final var firstTarget = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                firstFileId,
                Bytes.wrap("first applied properties"),
                firstContext,
                firstPriorFees);
        final var laterTarget = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                laterFileId,
                Bytes.wrap("later applied properties"),
                laterContext,
                null);

        // A non-root dispatch updates network properties, then the root dispatch updates them again in the same txn
        given(stack.isRoot()).willReturn(false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(firstTarget), Optional.of(laterTarget));

        subject.processDispatch(dispatch);
        subject.processDispatch(dispatch);

        final var captor = ArgumentCaptor.forClass(SystemFileUpdates.ResyncTarget.class);
        verify(systemFileUpdates).resyncIfChanged(eq(stack), captor.capture());
        final var resynced = captor.getValue();
        // The recovery baselines must be the first update's (the true pre-update committed state), not the later
        // update's (captured after the first update had already polluted the in-memory state)
        assertThat(resynced.configContext()).isEqualTo(firstContext);
        assertThat(resynced.priorSimpleFees()).isEqualTo(firstPriorFees);
        // The file ID and applied contents must be the latest, so the change check reads the committed file the latest
        // update targeted and compares it against what memory last reflects
        assertThat(resynced.fileId()).isEqualTo(laterFileId);
        assertThat(resynced.appliedContents()).isEqualTo(Bytes.wrap("later applied properties"));
    }

    @Test
    void everyFacilityFailureIsSurfacedWithLaterOnesSuppressed() {
        given(stack.isRoot()).willReturn(false, true);
        given(systemFileUpdates.resyncTarget(stack, TXN_BODY))
                .willReturn(Optional.of(RATES_TARGET), Optional.of(FEES_TARGET));
        final var ratesFailure = new IllegalStateException("cannot re-derive the rates");
        final var feesFailure = new IllegalStateException("cannot re-derive the fees");
        doThrow(ratesFailure).when(systemFileUpdates).resyncIfChanged(stack, RATES_TARGET);
        doThrow(feesFailure).when(systemFileUpdates).resyncIfChanged(stack, FEES_TARGET);

        subject.processDispatch(dispatch);
        // Exchange rates are re-derived first, so that failure is thrown and the fee-schedule failure is suppressed
        assertThatThrownBy(() -> subject.processDispatch(dispatch))
                .isSameAs(ratesFailure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(feesFailure));
    }
}
