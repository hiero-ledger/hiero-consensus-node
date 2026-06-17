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
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
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
        tracker = new TxPipelineTracker(InstantSource.system(), new NoOpMetrics());
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        @Test
        @DisplayName("Constructor initializes with zero counts")
        void constructorInitializesWithZeroCounts() {
            final var newTracker = new TxPipelineTracker(InstantSource.system(), new NoOpMetrics());
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

    @Nested
    @DisplayName("Activity clock and underflow counter tests")
    class ActivityClockTests {
        /**
         * The {@link TxPipelineTracker#lastActivityAt()} supplier is read by the
         * {@link QuiescenceController} grace-period guard. The constructor must anchor the activity clock
         * to {@code time.instant()} so the grace period starts running from process start, not from
         * {@link Instant#EPOCH}.
         */
        @Test
        @DisplayName("Constructor anchors lastActivityAt to time.instant()")
        void constructorAnchorsLastActivityAtToTimeNow() {
            final var initial = Instant.ofEpochSecond(1_700_000_000L);
            final var clock = new MutableInstantSource(initial);
            final var tracker = new TxPipelineTracker(clock, new NoOpMetrics());

            assertEquals(initial, tracker.lastActivityAt());
        }

        /**
         * {@link TxPipelineTracker#recordActivity()} is invoked by paths outside ingest (e.g. the controller
         * on a pre-handled relevant cross-node tx, or on the {@code ACTIVE} status anchor) — verifying it
         * refreshes the activity clock to {@code time.instant()} is what makes the grace period robust to
         * those wake-ups.
         */
        @Test
        @DisplayName("recordActivity refreshes lastActivityAt to the current instant")
        void recordActivityRefreshesLastActivityAt() {
            final var initial = Instant.ofEpochSecond(1_700_000_000L);
            final var clock = new MutableInstantSource(initial);
            final var tracker = new TxPipelineTracker(clock, new NoOpMetrics());

            clock.advance(Duration.ofSeconds(42));
            tracker.recordActivity();

            assertEquals(initial.plusSeconds(42), tracker.lastActivityAt());
        }

        @Test
        @DisplayName("incrementPreFlight refreshes lastActivityAt")
        void incrementPreFlightRefreshesLastActivityAt() {
            final var initial = Instant.ofEpochSecond(1_700_000_000L);
            final var clock = new MutableInstantSource(initial);
            final var tracker = new TxPipelineTracker(clock, new NoOpMetrics());

            clock.advance(Duration.ofMillis(500));
            tracker.incrementPreFlight();

            assertEquals(initial.plusMillis(500), tracker.lastActivityAt());
        }

        @Test
        @DisplayName("incrementInFlight refreshes lastActivityAt")
        void incrementInFlightRefreshesLastActivityAt() {
            final var initial = Instant.ofEpochSecond(1_700_000_000L);
            final var clock = new MutableInstantSource(initial);
            final var tracker = new TxPipelineTracker(clock, new NoOpMetrics());

            clock.advance(Duration.ofMillis(750));
            tracker.incrementInFlight();

            assertEquals(initial.plusMillis(750), tracker.lastActivityAt());
        }

        /**
         * {@code decrementPreFlight} is a hot-path ingest-finalize hook. It must not refresh the activity
         * clock — only ingest <i>arrival</i> counts as activity for the grace period.
         */
        @Test
        @DisplayName("decrementPreFlight does NOT refresh lastActivityAt")
        void decrementPreFlightDoesNotRefreshLastActivityAt() {
            final var initial = Instant.ofEpochSecond(1_700_000_000L);
            final var clock = new MutableInstantSource(initial);
            final var tracker = new TxPipelineTracker(clock, new NoOpMetrics());
            tracker.incrementPreFlight();
            final var afterIncrement = tracker.lastActivityAt();

            clock.advance(Duration.ofSeconds(10));
            tracker.decrementPreFlight();

            assertEquals(afterIncrement, tracker.lastActivityAt(), "decrement must not bump the activity clock");
        }

        /**
         * The underflow path inside {@code countLanded} routes through {@code decrementInFlightOrClamp},
         * which increments the {@code quiescence.inflightUnderflow} counter. The counter is the operator
         * signal for cross-node drift; this test asserts it's wired up correctly. A regression that
         * silenced this counter would hide a useful diagnostic.
         */
        @Test
        @DisplayName("countLanded increments inflightUnderflow counter when inFlight is zero")
        void countLandedIncrementsUnderflowCounterWhenInFlightIsZero() {
            final var counter = mock(Counter.class);
            final var metrics = mock(Metrics.class);
            // Argument here is MetricConfig — return our mock for any config the constructor passes.
            when(metrics.getOrCreate(any())).thenReturn((Metric) counter);

            final var tracker = new TxPipelineTracker(InstantSource.system(), metrics);

            // Build a relevant tx and pass it to countLanded with inFlight==0 — the clamp branch fires.
            final var txBody = createRelevantTransactionBody();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);
            tracker.countLanded(List.of(transaction).iterator());

            verify(counter).increment();
            assertEquals(0, tracker.estimateTxPipelineCount());
        }

        @Test
        @DisplayName("countLanded does NOT increment underflow counter when inFlight is positive")
        void countLandedDoesNotIncrementUnderflowCounterWhenInFlightPositive() {
            final var counter = mock(Counter.class);
            final var metrics = mock(Metrics.class);
            when(metrics.getOrCreate(any())).thenReturn((Metric) counter);

            final var tracker = new TxPipelineTracker(InstantSource.system(), metrics);
            tracker.incrementInFlight();

            final var txBody = createRelevantTransactionBody();
            final var txInfo = createTransactionInfo(txBody);
            final var preHandleResult = createPreHandleResult(txInfo);
            final var transaction = createTransaction(preHandleResult);
            tracker.countLanded(List.of(transaction).iterator());

            verify(counter, never()).increment();
            assertEquals(0, tracker.estimateTxPipelineCount());
        }
    }

    /**
     * Minimal in-memory {@link InstantSource} that can be advanced by a {@link Duration}. Lets us drive
     * {@link TxPipelineTracker#lastActivityAt()} assertions without relying on wall-clock progression.
     */
    private static final class MutableInstantSource implements InstantSource {
        private Instant now;

        MutableInstantSource(@NonNull final Instant initial) {
            this.now = initial;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(@NonNull final Duration delta) {
            now = now.plus(delta);
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
