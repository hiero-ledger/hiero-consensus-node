// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TxPipelineTrackerTest {

    private TxPipelineTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TxPipelineTracker();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        @Test
        @DisplayName("Constructor initializes with zero counts")
        void constructorInitializesWithZeroCounts() {
            final var newTracker = new TxPipelineTracker();
            assertEquals(0, newTracker.estimateTxPipelineCount());
        }
    }

    @Nested
    @DisplayName("estimateTxPipelineCount Tests")
    class EstimateTxPipelineCountTests {
        @Test
        @DisplayName("Returns zero when no transactions in pipeline")
        void returnsZeroWhenEmpty() {
            assertEquals(0, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Returns preFlight count only")
        void returnsPreFlightCountOnly() {
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Returns inFlight count only")
        void returnsInFlightCountOnly() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            assertEquals(3, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Returns sum of preFlight and inFlight counts")
        void returnsSumOfBothCounts() {
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            assertEquals(5, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Returns correct count after decrements")
        void returnsCorrectCountAfterDecrements() {
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.decrementPreFlight();
            tracker.decrementPreFlight();
            assertEquals(3, tracker.estimateTxPipelineCount());
        }
    }

    @Nested
    @DisplayName("incrementPreFlight Tests")
    class IncrementPreFlightTests {
        @Test
        @DisplayName("Increments preFlight count by one")
        void incrementsPreFlightByOne() {
            tracker.incrementPreFlight();
            assertEquals(1, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Multiple increments accumulate correctly")
        void multipleIncrementsAccumulate() {
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            assertEquals(3, tracker.estimateTxPipelineCount());
        }
    }

    @Nested
    @DisplayName("decrementPreFlight Tests")
    class DecrementPreFlightTests {
        @Test
        @DisplayName("Decrements preFlight count by one")
        void decrementsPreFlightByOne() {
            tracker.incrementPreFlight();
            tracker.incrementPreFlight();
            tracker.decrementPreFlight();
            assertEquals(1, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Can decrement to zero")
        void canDecrementToZero() {
            tracker.incrementPreFlight();
            tracker.decrementPreFlight();
            assertEquals(0, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Can decrement below zero (negative count)")
        void canDecrementBelowZero() {
            tracker.decrementPreFlight();
            assertEquals(-1, tracker.estimateTxPipelineCount());
        }
    }

    @Nested
    @DisplayName("incrementInFlight Tests")
    class IncrementInFlightTests {
        @Test
        @DisplayName("Increments inFlight count by one")
        void incrementsInFlightByOne() {
            tracker.incrementInFlight();
            assertEquals(1, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Multiple increments accumulate correctly")
        void multipleIncrementsAccumulate() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            assertEquals(4, tracker.estimateTxPipelineCount());
        }
    }

    @Nested
    @DisplayName("countLanded Tests")
    class CountLandedTests {
        @Test
        @DisplayName("Throws NullPointerException when iterator is null")
        void throwsNullPointerExceptionWhenIteratorIsNull() {
            assertThrows(NullPointerException.class, () -> tracker.countLanded(null));
        }

        @Test
        @DisplayName("Does nothing with empty iterator")
        void doesNothingWithEmptyIterator() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            final List<Transaction> transactions = new ArrayList<>();
            tracker.countLanded(transactions.iterator());
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Decrements inFlight count for relevant transaction")
        void decrementsInFlightCountForRelevantTransaction() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var txBody = createRelevantTransactionBody();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Decrements inFlight count for multiple relevant transactions")
        void decrementsInFlightCountForMultipleRelevantTransactions() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var txBody1 = createRelevantTransactionBody();
            final var txInfo1 = createTransactionInfo(txBody1);
            final var preHandleResult1 = createPreHandleResult(txInfo1);
            final var transaction1 = createTransaction(preHandleResult1);

            final var txBody2 = createRelevantTransactionBody();
            final var txInfo2 = createTransactionInfo(txBody2);
            final var preHandleResult2 = createPreHandleResult(txInfo2);
            final var transaction2 = createTransaction(preHandleResult2);

            final List<Transaction> transactions = List.of(transaction1, transaction2);
            tracker.countLanded(transactions.iterator());

            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Does not decrement below zero")
        void doesNotDecrementBelowZero() {
            // Start with zero inFlight count
            final var txBody = createRelevantTransactionBody();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            // Should stay at 0, not go negative
            assertEquals(0, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Does not decrement for irrelevant transaction (StateSignatureTransaction)")
        void doesNotDecrementForStateSignatureTransaction() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1001).build())
                            .build())
                    .stateSignatureTransaction(StateSignatureTransaction.DEFAULT)
                    .build();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            // Should not decrement for irrelevant transaction
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Does not decrement for irrelevant transaction (HintsPartialSignature)")
        void doesNotDecrementForHintsPartialSignature() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1001).build())
                            .build())
                    .hintsPartialSignature(HintsPartialSignatureTransactionBody.DEFAULT)
                    .build();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            // Should not decrement for irrelevant transaction
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Ignores transaction with non-PreHandleResult metadata")
        void ignoresTransactionWithNonPreHandleResultMetadata() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var transaction = new TransactionWrapper(Bytes.EMPTY);
            transaction.setMetadata("not a PreHandleResult");

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            // Should not decrement for transaction with wrong metadata type
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Ignores transaction with null txInfo in PreHandleResult")
        void ignoresTransactionWithNullTxInfo() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            final var preHandleResult = createPreHandleResult(null);
            final var transaction = createTransaction(preHandleResult);

            final List<Transaction> transactions = List.of(transaction);
            tracker.countLanded(transactions.iterator());

            // Should not decrement for transaction with null txInfo
            assertEquals(2, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("Handles mixed relevant and irrelevant transactions")
        void handlesMixedRelevantAndIrrelevantTransactions() {
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();
            tracker.incrementInFlight();

            // Relevant transaction
            final var txBody1 = createRelevantTransactionBody();
            final var txInfo1 = createTransactionInfo(txBody1);
            final var preHandleResult1 = createPreHandleResult(txInfo1);
            final var transaction1 = createTransaction(preHandleResult1);

            // Irrelevant transaction (StateSignatureTransaction)
            final var txBody2 = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .accountID(AccountID.newBuilder().accountNum(1002).build())
                            .build())
                    .stateSignatureTransaction(StateSignatureTransaction.DEFAULT)
                    .build();
            final var txInfo2 = createTransactionInfo(txBody2);
            final var preHandleResult2 = createPreHandleResult(txInfo2);
            final var transaction2 = createTransaction(preHandleResult2);

            // Another relevant transaction
            final var txBody3 = createRelevantTransactionBody();
            final var txInfo3 = createTransactionInfo(txBody3);
            final var preHandleResult3 = createPreHandleResult(txInfo3);
            final var transaction3 = createTransaction(preHandleResult3);

            final List<Transaction> transactions = List.of(transaction1, transaction2, transaction3);
            tracker.countLanded(transactions.iterator());

            // Should decrement only for the 2 relevant transactions
            assertEquals(2, tracker.estimateTxPipelineCount());
        }
    }

    // Helper methods to create test objects
    private TransactionBody createRelevantTransactionBody() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(1001).build())
                        .build())
                .build();
    }

    private TransactionInfo createTransactionInfo(TransactionBody txBody) {
        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(txBody))
                .build();
        return new TransactionInfo(
                signedTx,
                txBody,
                SignatureMap.newBuilder().build(),
                Bytes.wrap(new byte[100]),
                HederaFunctionality.CONSENSUS_CREATE_TOPIC,
                SignedTransaction.PROTOBUF.toBytes(signedTx));
    }

    private PreHandleResult createPreHandleResult(TransactionInfo txInfo) {
        final var preHandleResult = mock(PreHandleResult.class);
        when(preHandleResult.txInfo()).thenReturn(txInfo);
        return preHandleResult;
    }

    private Transaction createTransaction(PreHandleResult preHandleResult) {
        final var transaction = new TransactionWrapper(Bytes.EMPTY);
        transaction.setMetadata(preHandleResult);
        return transaction;
    }
}
