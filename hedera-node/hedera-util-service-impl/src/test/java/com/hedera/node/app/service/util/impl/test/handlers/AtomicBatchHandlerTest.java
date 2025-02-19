/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.util.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.AtomicBatchTransactionBody;
import com.hedera.node.app.service.util.impl.handlers.AtomicBatchHandler;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.function.Function;
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
    private FeeCharging feeCharging;

    @Mock
    private Function<Transaction, TransactionBody> bodyParser;

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
                .withValue("atomicBatch.isEnabled", true)
                .withValue("atomicBatch.maxNumberOfTransactions", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(appContext.feeChargingSupplier()).willReturn(() -> feeCharging);
        given(preHandleContext.configuration()).willReturn(config);

        subject = new AtomicBatchHandler(appContext, bodyParser);
    }

    @Test
    void pureChecksSucceeds() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        given(pureChecksContext.body()).willReturn(txnBody);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        given(bodyParser.apply(transaction)).willReturn(innerTxnBody);
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void failsOnEmptyBatchList() {
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp);
        given(pureChecksContext.body()).willReturn(txnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_EMPTY, msg.responseCode());
    }

    @Test
    void failsOnInnerFreezeTx() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        given(pureChecksContext.body()).willReturn(txnBody);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .freeze(FreezeTransactionBody.DEFAULT)
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        given(bodyParser.apply(transaction)).willReturn(innerTxnBody);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_CONTAINS_INVALID_TRANSACTION, msg.responseCode());
    }

    @Test
    void failsOnInnerBatchTx() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        given(pureChecksContext.body()).willReturn(txnBody);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .atomicBatch(AtomicBatchTransactionBody.DEFAULT)
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(0).build())
                .build();
        given(bodyParser.apply(transaction)).willReturn(innerTxnBody);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_CONTAINS_INVALID_TRANSACTION, msg.responseCode());
    }

    @Test
    void failsIfInnerTxDuplicate() {
        final var transaction1 = mock(Transaction.class);
        final var transaction2 = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction1, transaction2);
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
        given(bodyParser.apply(transaction1)).willReturn(innerTxnBody1);
        given(bodyParser.apply(transaction2)).willReturn(innerTxnBody2);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(BATCH_LIST_CONTAINS_DUPLICATES, msg.responseCode());
    }

    @Test
    void failsIfInnerTxNodeIdSetToOtherThanZero() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .batchKey(SIMPLE_KEY_A)
                .nodeAccountID(AccountID.newBuilder().accountNum(1).build())
                .build();
        given(pureChecksContext.body()).willReturn(txnBody);
        given(bodyParser.apply(any())).willReturn(innerTxnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_NODE_ACCOUNT_ID, msg.responseCode());
    }

    @Test
    void failsIfInnerTxMissingBatchKey() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .nodeAccountID(AccountID.newBuilder().accountNum(1).build())
                .build();
        given(bodyParser.apply(any())).willReturn(innerTxnBody);
        given(pureChecksContext.body()).willReturn(txnBody);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(MISSING_BATCH_KEY, msg.responseCode());
    }

    @Test
    void preHandleBatchWithBatchKeyIsNull() throws PreCheckException {
        final var transaction1 = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction1);
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        given(preHandleContext.body()).willReturn(txnBody);
        given(bodyParser.apply(transaction1)).willReturn(innerTxnBody1);
        given(preHandleContext.requireKeyOrThrow(innerTxnBody1.batchKey(), INVALID_BATCH_KEY))
                .willThrow(new PreCheckException(INVALID_BATCH_KEY));
        final var msg = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(ResponseCodeEnum.INVALID_BATCH_KEY, msg.responseCode());
    }

    @Test
    void preHandleValidScenario() {
        final var batchKey = SIMPLE_KEY_A;
        final var transaction1 = mock(Transaction.class);
        final var transaction2 = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction1, transaction2);
        final var innerTxnBody1 = newTxnBodyBuilder(payerId1, consensusTimestamp, batchKey)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        final var innerTxnBody2 = newTxnBodyBuilder(payerId2, consensusTimestamp, batchKey)
                .consensusDeleteTopic(
                        ConsensusDeleteTopicTransactionBody.newBuilder().build())
                .build();
        given(preHandleContext.body()).willReturn(txnBody);
        given(bodyParser.apply(transaction1)).willReturn(innerTxnBody1);
        given(bodyParser.apply(transaction2)).willReturn(innerTxnBody2);
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void innerTransactionDispatchFailed() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        given(handleContext.body()).willReturn(txnBody);
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(bodyParser.apply(any())).willReturn(innerTxnBody);
        given(handleContext.dispatch(any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(UNKNOWN);
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INNER_TRANSACTION_FAILED, msg.getStatus());
    }

    @Test
    void handleBatchSizeExceedsMaxBatchSize() {
        final var transaction1 = mock(Transaction.class);
        final var transaction2 = mock(Transaction.class);
        final var transaction3 = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction1, transaction2, transaction3);
        given(handleContext.body()).willReturn(txnBody);
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED, msg.getStatus());
    }

    @Test
    void handleDispatched() {
        final var transaction = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction);
        final var innerTxnBody = newTxnBodyBuilder(payerId2, consensusTimestamp, SIMPLE_KEY_A)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        given(handleContext.body()).willReturn(txnBody);
        given(handleContext.dispatch(argThat(options -> options.payerId().equals(payerId2)
                        && options.body().equals(innerTxnBody)
                        && options.streamBuilderType().equals(ReplayableFeeStreamBuilder.class))))
                .willReturn(recordBuilder);
        given(bodyParser.apply(any())).willReturn(innerTxnBody);
        given(recordBuilder.status()).willReturn(SUCCESS);
        subject.handle(handleContext);
    }

    @Test
    void handleMultipleDispatched() {
        final var batchKey = SIMPLE_KEY_A;
        final var transaction1 = mock(Transaction.class);
        final var transaction2 = mock(Transaction.class);
        final var txnBody = newAtomicBatch(payerId1, consensusTimestamp, transaction1, transaction2);
        final var innerTxnBody1 = newTxnBodyBuilder(payerId2, consensusTimestamp, batchKey)
                .consensusCreateTopic(
                        ConsensusCreateTopicTransactionBody.newBuilder().build())
                .build();
        final var innerTxnBody2 = newTxnBodyBuilder(payerId3, consensusTimestamp, batchKey)
                .consensusDeleteTopic(
                        ConsensusDeleteTopicTransactionBody.newBuilder().build())
                .build();
        given(handleContext.body()).willReturn(txnBody);
        given(bodyParser.apply(any())).willReturn(innerTxnBody1).willReturn(innerTxnBody2);
        given(handleContext.dispatch(any())).willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        subject.handle(handleContext);
        verify(handleContext, times(2)).dispatch(any());
    }

    private TransactionBody newAtomicBatch(
            AccountID payerId, Timestamp consensusTimestamp, Transaction... transactions) {
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
}
