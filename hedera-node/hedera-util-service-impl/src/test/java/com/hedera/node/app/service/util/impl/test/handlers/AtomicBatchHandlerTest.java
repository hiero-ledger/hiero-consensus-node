// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_BATCH_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_BATCH_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.AtomicBatchTransactionBody;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.util.impl.cache.TransactionParser;
import com.hedera.node.app.service.util.impl.handlers.AtomicBatchHandler;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AtomicBatchHandlerTest {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private PreHandleContext preHandleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private ReplayableFeeStreamBuilder recordBuilder;

    @Mock
    private AppContext appContext;

    @Mock
    private TransactionParser transactionParser;

    @Mock
    private FeeCharging feeCharging;

    private AtomicBatchHandler subject;

    private final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    private static final Key SIMPLE_KEY_A = Key.newBuilder()
            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
            .build();
    private static final Key SIMPLE_KEY_B = Key.newBuilder()
            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
            .build();
    private final AccountID payerId1 = AccountID.newBuilder().accountNum(1001).build();
    private final AccountID payerId2 = AccountID.newBuilder().accountNum(1002).build();
    private final AccountID payerId3 = AccountID.newBuilder().accountNum(1003).build();

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("atomicBatch.maxNumberOfTransactions", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(appContext.feeChargingSupplier()).willReturn(() -> feeCharging);
        given(appContext.configSupplier()).willReturn(() -> config);
        given(preHandleContext.configuration()).willReturn(config);

        subject = new AtomicBatchHandler(appContext, transactionParser);
    }

    @Test
    void pureChecksSucceeds() throws PreCheckException {
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        given(pureChecksContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void failsOnEmptyBatchList() {
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, List.of());
        given(pureChecksContext.body()).willReturn(txnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_EMPTY, msg.responseCode());
    }

    @Test
    void testInnerTxnCache() throws PreCheckException {
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT)
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(pureChecksContext.body()).willReturn(txnBody);
        given(preHandleContext.body()).willReturn(txnBody);
        given(handleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        given(handleContext.dispatch(argThat(options -> options.payerId().equals(payerId2)
                        && options.body().equals(innerTxnBody)
                        && options.streamBuilderType().equals(ReplayableFeeStreamBuilder.class))))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        assertDoesNotThrow(() -> subject.handle(handleContext));

        verify(transactionParser, times(1)).parse(eq(bytes.getFirst()), any());
    }

    @Test
    void failsOnInnerFreezeTx() throws PreCheckException {
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .freeze(FreezeTransactionBody.DEFAULT)
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(BATCH_TRANSACTION_IN_BLACKLIST, msg.responseCode());
    }

    @Test
    void failsOnInnerBatchTx() throws PreCheckException {
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .atomicBatch(AtomicBatchTransactionBody.DEFAULT)
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(BATCH_TRANSACTION_IN_BLACKLIST, msg.responseCode());
    }

    @Test
    void failsIfInnerTxDuplicate() throws PreCheckException {
        final var innerTxn1 = innerTxnFrom("123");
        final var innerTxn2 = innerTxnFrom("456");
        final var bytes = transactionsToBytes(innerTxn1, innerTxn2);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(pureChecksContext.body()).willReturn(txnBody);
        TransactionID transactionId = TransactionID.newBuilder()
                .accountID(payerId2)
                .transactionValidStart(consensusTimestamp)
                .build();
        final var innerTxnBody1 = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .transactionID(transactionId)
                .build();
        final var innerTxnBody2 = newTxnBodyBuilder(payerId3, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_B)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .transactionID(transactionId)
                .build();
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        given(transactionParser.parse(eq(bytes.getLast()), any())).willReturn(innerTxnBody2);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_CONTAINS_DUPLICATES, msg.responseCode());
    }

    @Test
    void failsIfInnerTxNodeIdSetToOtherThanZero() throws PreCheckException {
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(1).build())
                .build();
        given(pureChecksContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_NODE_ACCOUNT_ID, msg.responseCode());
    }

    @Test
    void failsIfInnerTxMissingBatchKey() throws PreCheckException {
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .nodeAccountID(AccountID.newBuilder().accountNum(1).build())
                .build();
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(pureChecksContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(MISSING_BATCH_KEY, msg.responseCode());
    }

    @Test
    void preHandleBatchWithBatchKeyIsNull() throws PreCheckException {
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        final var innerTxn = innerTxnFrom("123");
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        given(preHandleContext.requireKeyOrThrow(innerTxnBody1.batchKey(), INVALID_BATCH_KEY))
                .willThrow(new PreCheckException(INVALID_BATCH_KEY));
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(INVALID_BATCH_KEY, msg.responseCode());
    }

    @Test
    void preHandleFailFreezeTransaction() throws PreCheckException {
        final var innerTxn1 = innerTxnFrom("123");
        final var innerTxn2 = innerTxnFrom("456");
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp, SIMPLE_KEY_A)
                .freeze(FreezeTransactionBody.newBuilder().build())
                .build();
        final var bytes = transactionsToBytes(innerTxn1, innerTxn2);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(BATCH_TRANSACTION_IN_BLACKLIST, msg.responseCode());
    }

    @Test
    void preHandleFailAtomicBatchTransaction() throws PreCheckException {
        final var innerTxn1 = innerTxnFrom("123");
        final var innerTxn2 = innerTxnFrom("456");
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp, SIMPLE_KEY_A)
                .atomicBatch(AtomicBatchTransactionBody.newBuilder().build())
                .build();
        final var bytes = transactionsToBytes(innerTxn1, innerTxn2);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(BATCH_TRANSACTION_IN_BLACKLIST, msg.responseCode());
    }

    @Test
    void preHandleValidScenario() throws PreCheckException {
        final var transaction1 = innerTxnFrom("123");
        final var transaction2 = innerTxnFrom("456");
        final var batchKey = SIMPLE_KEY_A;
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp, batchKey)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        final var innerTxnBody2 = newTxnBodyBuilder(payerId2, consensusTimestamp, batchKey)
                .consensusDeleteTopic(
                        ConsensusDeleteTopicTransactionBody.newBuilder().build())
                .build();
        final var bytes = transactionsToBytes(transaction1, transaction2);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(preHandleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        given(transactionParser.parse(eq(bytes.getLast()), any())).willReturn(innerTxnBody2);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void innerTransactionDispatchFailed() throws PreCheckException {
        final var transaction = innerTxnFrom("123");
        final var innerTxnBody = newTxnBodyBuilder(payerId1, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.DEFAULT)
                .build();
        final var bytes = transactionsToBytes(transaction);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        final var tokenServiceMock = mock(TokenServiceApi.class);
        final var storeFactoryMock = mock(StoreFactory.class);

        given(handleContext.body()).willReturn(txnBody);
        given(handleContext.storeFactory()).willReturn(storeFactoryMock);
        given(storeFactoryMock.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceMock);
        given(handleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.dispatch(any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(UNKNOWN);
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INNER_TRANSACTION_FAILED, msg.getStatus());
    }

    @Test
    void handleBatchSizeExceedsMaxBatchSize() {
        final var innerTxn1 = innerTxnFrom("123");
        final var innerTxn2 = innerTxnFrom("456");
        final var innerTxn3 = innerTxnFrom("789");
        final var bytes = transactionsToBytes(innerTxn1, innerTxn2, innerTxn3);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(handleContext.body()).willReturn(txnBody);
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED, msg.getStatus());
    }

    @Test
    void handleDispatched() throws PreCheckException {
        final var innerTxn = innerTxnFrom("123");
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.DEFAULT)
                .build();
        final var bytes = transactionsToBytes(innerTxn);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(handleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody);
        given(handleContext.dispatch(argThat(options -> options.payerId().equals(payerId2)
                        && options.body().equals(innerTxnBody)
                        && options.streamBuilderType().equals(ReplayableFeeStreamBuilder.class))))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void handleMultipleDispatched() throws PreCheckException {
        final var innerTxn1 = innerTxnFrom("123");
        final var innerTxn2 = innerTxnFrom("456");
        final var innerTxnBody1 = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.DEFAULT)
                .build();
        final var innerTxnBody2 = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.DEFAULT)
                .build();
        final var bytes = transactionsToBytes(innerTxn1, innerTxn2);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, bytes);
        given(handleContext.body()).willReturn(txnBody);
        given(transactionParser.parse(eq(bytes.getFirst()), any())).willReturn(innerTxnBody1);
        given(transactionParser.parse(eq(bytes.getLast()), any())).willReturn(innerTxnBody2);
        given(handleContext.dispatch(any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        subject.handle(handleContext);
        verify(handleContext, times(2)).dispatch(any());
    }

    @Test
    void calculateFeesReturnsExpectedFees() {
        var feeContext = mock(FeeContext.class);
        var calculator = mock(FeeCalculator.class);
        var expectedFees = mock(Fees.class);

        when(feeContext.feeCalculatorFactory()).thenReturn(type -> calculator);
        when(calculator.resetUsage()).thenReturn(calculator);
        // Use doReturn/when pattern to avoid strict stubbing issues
        doReturn(calculator).when(calculator).addVerificationsPerTransaction(anyLong());
        when(calculator.calculate()).thenReturn(expectedFees);
        when(feeContext.numTxnSignatures()).thenReturn(1);

        var result = subject.calculateFees(feeContext);

        assertSame(expectedFees, result);
    }

    @Test
    void recordedFeeChargingCustomizedContextCapturesAndSnapshotsAllObservedCharges() {
        var delegate = mock(FeeCharging.class);
        var ctx = mock(FeeCharging.Context.class);
        var firstFees = new Fees(1, 2, 3);
        var secondFees = new Fees(4, 5, 6);
        var thirdFees = new Fees(7, 8, 9);
        var firstBuilder = mock(ReplayableFeeStreamBuilder.class);
        var secondBuilder = mock(ReplayableFeeStreamBuilder.class);

        var rfc = new AtomicBatchHandler.RecordedFeeCharging(delegate);
        var firstCustomized = rfc.customized(ctx);
        var secondCustomized = rfc.customized(ctx);

        when(ctx.charge(payerId1, firstFees, null)).thenReturn(firstFees);
        when(ctx.charge(payerId2, secondFees, payerId3, null)).thenReturn(secondFees);
        when(ctx.charge(payerId3, thirdFees, null)).thenReturn(thirdFees);

        assertNotSame(ctx, firstCustomized);
        assertNotSame(ctx, secondCustomized);
        assertSame(firstFees, firstCustomized.charge(payerId1, firstFees, null));
        assertSame(secondFees, secondCustomized.charge(payerId2, secondFees, payerId3, null));
        rfc.finishRecordingTo(firstBuilder);

        rfc.rollback();
        assertSame(thirdFees, firstCustomized.charge(payerId3, thirdFees, null));
        rfc.finishRecordingTo(secondBuilder);

        final var recordedBuilders = new ArrayList<ReplayableFeeStreamBuilder>();
        final var recordedCharges = new ArrayList<List<AtomicBatchHandler.RecordedFeeCharging.Charge>>();
        rfc.forEachRecorded((builder, charges) -> {
            recordedBuilders.add(builder);
            recordedCharges.add(charges);
        });

        assertEquals(List.of(firstBuilder, secondBuilder), recordedBuilders);
        assertEquals(
                List.of(
                        List.of(
                                new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId1, firstFees, null),
                                new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId2, secondFees, payerId3)),
                        List.of(new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId3, thirdFees, null))),
                recordedCharges);
        assertThrows(UnsupportedOperationException.class, () -> recordedCharges
                .getFirst()
                .add(new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId1, firstFees, null)));
    }

    @Test
    void recordedFeeChargingReplayAndRefund() {
        var delegate = mock(FeeCharging.class);
        var ctx = mock(FeeCharging.Context.class);
        var replayCtx = mock(FeeCharging.Context.class);
        var validation = mock(FeeCharging.Validation.class);
        var outerFees = new Fees(1, 1, 1);
        var firstReplayFees = new Fees(2, 2, 2);
        var secondReplayFees = new Fees(3, 3, 3);
        var streamBuilder = mock(ReplayableFeeStreamBuilder.class);

        when(ctx.charge(payerId1, firstReplayFees, null)).thenReturn(firstReplayFees);
        when(ctx.charge(payerId2, secondReplayFees, payerId3, null)).thenReturn(secondReplayFees);
        when(delegate.charge(eq(payerId1), any(FeeCharging.Context.class), eq(validation), eq(outerFees)))
                .thenAnswer(invocation -> {
                    final FeeCharging.Context recordingContext = invocation.getArgument(1);
                    recordingContext.charge(payerId1, firstReplayFees, null);
                    recordingContext.charge(payerId2, secondReplayFees, payerId3, null);
                    return outerFees;
                });

        var rfc = new AtomicBatchHandler.RecordedFeeCharging(delegate);
        assertSame(outerFees, rfc.charge(payerId1, ctx, validation, outerFees));
        rfc.finishRecordingTo(streamBuilder);

        final var recordedBuilders = new ArrayList<ReplayableFeeStreamBuilder>();
        final var recordedCharges = new ArrayList<List<AtomicBatchHandler.RecordedFeeCharging.Charge>>();
        rfc.forEachRecorded((builder, charges) -> {
            recordedBuilders.add(builder);
            recordedCharges.add(charges);
        });

        assertEquals(List.of(streamBuilder), recordedBuilders);
        assertEquals(
                List.of(List.of(
                        new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId1, firstReplayFees, null),
                        new AtomicBatchHandler.RecordedFeeCharging.Charge(payerId2, secondReplayFees, payerId3))),
                recordedCharges);

        recordedCharges.getFirst().forEach(charge -> charge.replay(replayCtx, (id, amount) -> {}));
        verify(replayCtx).charge(eq(payerId1), eq(firstReplayFees), any());
        verify(replayCtx).charge(eq(payerId2), eq(secondReplayFees), eq(payerId3), any());

        rfc.refund(payerId1, ctx, outerFees);
        verify(delegate).refund(payerId1, ctx, outerFees);
    }

    private TransactionBody newAtomicBatch(AccountID payerId, Timestamp consensusTimestamp, List<Bytes> transactions) {
        final var atomicBatchBuilder = AtomicBatchTransactionBody.newBuilder().transactions(transactions);
        return newTxnBodyBuilder(payerId, consensusTimestamp)
                .atomicBatch(atomicBatchBuilder)
                .build();
    }

    private TransactionBody.Builder newTxnBodyBuilder(
            AccountID payerId, Timestamp consensusTimestamp, Key... batchKey) {
        final var txnId = TransactionID.newBuilder()
                .accountID(payerId)
                .transactionValidStart(consensusTimestamp)
                .build();
        return batchKey.length == 0
                ? TransactionBody.newBuilder().transactionID(txnId)
                : TransactionBody.newBuilder().transactionID(txnId).batchKey(batchKey[0]);
    }

    private List<Bytes> transactionsToBytes(Transaction... transactions) {
        return Arrays.stream(transactions)
                .map(Transaction::signedTransactionBytes)
                .toList();
    }

    private Transaction innerTxnFrom(String s) {
        return Transaction.newBuilder().signedTransactionBytes(toBytes(s)).build();
    }

    private Bytes toBytes(String s) {
        return Bytes.wrap(s.getBytes());
    }
}
