// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.BREAK_QUIESCENCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuiescenceControllerTest {
    private static final QuiescenceConfig CONFIG = new QuiescenceConfig(true, Duration.ofSeconds(3));
    private static final TransactionBody TXN_TRANSFER = TransactionBody.newBuilder()
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
            .build();
    private static final TransactionBody TXN_STATE_SIG = TransactionBody.newBuilder()
            .stateSignatureTransaction(StateSignatureTransaction.DEFAULT)
            .build();
    private static final TransactionBody TXN_HINTS_SIG = TransactionBody.newBuilder()
            .hintsPartialSignature(HintsPartialSignatureTransactionBody.DEFAULT)
            .build();

    private final AtomicLong pendingTransactions = new AtomicLong();
    private FakeTime time;
    private QuiescenceController controller;

    @BeforeEach
    void setUp() {
        pendingTransactions.set(0);
        time = new FakeTime();
        controller = new QuiescenceController(CONFIG, time::now, pendingTransactions::get);
    }

    @Test
    void basicBehavior() {
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Initially the status should be quiescent");
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Since a transaction was received through pre-handle, the status should be not quiescent");
        final var blockTracker = controller.startingBlock(1);
        requireNonNull(blockTracker).blockTransaction(createTransaction(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "The transaction has been handled, but we should remain not quiescent until the block is signed");
        blockTracker.finishedHandlingTransactions();
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "The block is finalized, but we should remain not quiescent until the block is signed");
        controller.blockFullySigned(1);
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Once that transaction has been included in a block, the status should be quiescent again");
    }

    @Test
    void signaturesAreIgnored() {
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Initially the status should be quiescent");
        controller.onPreHandle(createTransactions(TXN_STATE_SIG, TXN_HINTS_SIG));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Signature transactions should be ignored, so the status should remain quiescent");
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "A single non-signature transaction should make the status not quiescent");
        final var blockTracker = controller.startingBlock(1);
        requireNonNull(blockTracker).blockTransaction(createTransaction(TXN_STATE_SIG));
        blockTracker.blockTransaction(createTransaction(TXN_HINTS_SIG));
        blockTracker.finishedHandlingTransactions();
        controller.blockFullySigned(1);
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Signature transactions should be ignored, so the status should remain not quiescent");
    }

    @Test
    void staleEvents() {
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Since a transaction was received through pre-handle, the status should be not quiescent");
        controller.staleEvent(createEvent(TXN_TRANSFER));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "A stale event should remove the transaction from the pipeline, so the status should be quiescent again");
    }

    @Test
    void tct() {
        controller.setNextTargetConsensusTime(
                time.now().plus(CONFIG.tctDuration().multipliedBy(2)));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "There are no pending transactions, and the TCT is far off, so the status should be quiescent");
        time.tick(CONFIG.tctDuration().plusNanos(1));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Wall-clock time has advanced past the TCT threshold, so the status should be not quiescent");
        final var blockTracker1 = controller.startingBlock(1);
        assertNotNull(blockTracker1);
        blockTracker1.consensusTimeAdvanced(time.now());
        blockTracker1.finishedHandlingTransactions();
        controller.blockFullySigned(1);
        time.tick(CONFIG.tctDuration().multipliedBy(2));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Wall-clock time has advanced past the TCT, but consensus time has not, so the status should remain not quiescent");
        final var blockTracker2 = controller.startingBlock(2);
        assertNotNull(blockTracker2);
        blockTracker2.consensusTimeAdvanced(time.now());
        blockTracker2.finishedHandlingTransactions();
        controller.blockFullySigned(2);
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Consensus time has now advanced past the TCT, so the status should be quiescent again");
    }

    @Test
    void platformStatusUpdate() {
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Since a transaction was received through pre-handle, the status should be not quiescent");
        controller.platformStatusUpdate(PlatformStatus.CHECKING);
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "The checking status should not affect the quiescence status");
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        assertEquals(
                QUIESCE, controller.getQuiescenceStatus(), "The reconnect complete status should reset the controller");
    }

    @Test
    void quiescenceBreaking() {
        pendingTransactions.set(1);
        assertEquals(
                BREAK_QUIESCENCE,
                controller.getQuiescenceStatus(),
                "If there are pending transactions, and no pipeline transactions, we should be break quiescence");
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Once there are pipeline transactions, pending transactions should not matter");
    }

    private Event createEvent(final TransactionBody... txns) {
        final Event event = Mockito.mock(Event.class);
        final List<Transaction> transactions = createTransactions(txns);
        Mockito.when(event.transactionIterator()).thenReturn(transactions.iterator());
        return event;
    }

    private static List<Transaction> createTransactions(final TransactionBody... txns) {
        return Arrays.stream(txns)
                .map(QuiescenceControllerTest::createTransaction)
                .toList();
    }

    private static Transaction createTransaction(final TransactionBody txnBody) {
        final TransactionInfo transactionInfo = Mockito.mock(TransactionInfo.class);
        Mockito.when(transactionInfo.txBody()).thenReturn(txnBody);
        final PreHandleResult preHandleResult = Mockito.mock(PreHandleResult.class);
        Mockito.when(preHandleResult.txInfo()).thenReturn(transactionInfo);
        final Transaction transaction = new TransactionWrapper(Bytes.EMPTY);
        transaction.setMetadata(preHandleResult);
        return transaction;
    }

    @Test
    void finishHandlingInProgressBlockSucceeds() {
        // Given - a block has been started
        controller.startingBlock(1);

        // When - we finish handling the in-progress block
        controller.finishHandlingInProgressBlock();

        // Then - the block should be finalized and quiescence should still be enabled
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Status should be quiescent after finishing block");
    }

    @Test
    void finishHandlingInProgressBlockWhenDisabled() {
        // Given - quiescence is disabled
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(3)), time::now, pendingTransactions::get);

        // When - we try to finish handling in-progress block
        controller.finishHandlingInProgressBlock();

        // Then - nothing should happen (no exception)
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void finishHandlingInProgressBlockWithNoBlockStarted() {
        // When - we try to finish handling without starting a block
        controller.finishHandlingInProgressBlock();

        // Then - quiescence should be disabled due to the error
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus(), "Quiescence should be disabled after error");
    }

    @Test
    void inProgressBlockTransactionSucceeds() {
        // Given - a block has been started and a transaction is pre-handled
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        controller.startingBlock(1);
        final ConsensusTransaction consensusTxn = createConsensusTransaction(time.now());

        // When - we record a transaction in the in-progress block
        controller.inProgressBlockTransaction(consensusTxn);

        // Then - the transaction should be recorded and quiescence should still be enabled
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Status should be not quiescent with pending transaction");
    }

    @Test
    void inProgressBlockTransactionWhenDisabled() {
        // Given - quiescence is disabled
        controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(3)), time::now, pendingTransactions::get);
        final ConsensusTransaction consensusTxn = createConsensusTransaction(time.now());

        // When - we try to record a transaction
        controller.inProgressBlockTransaction(consensusTxn);

        // Then - nothing should happen (no exception)
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void inProgressBlockTransactionWithNoBlockStarted() {
        // Given - no block has been started
        final ConsensusTransaction consensusTxn = createConsensusTransaction(time.now());

        // When - we try to record a transaction without starting a block
        controller.inProgressBlockTransaction(consensusTxn);

        // Then - quiescence should be disabled due to the error
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus(), "Quiescence should be disabled after error");
    }

    @Test
    void inProgressBlockTransactionUpdatesConsensusTime() {
        // Given - a block has been started
        controller.startingBlock(1);
        final Instant consensusTime = time.now().plusSeconds(10);
        final ConsensusTransaction consensusTxn = createConsensusTransaction(consensusTime);

        // When - we record a transaction with a specific consensus time
        controller.inProgressBlockTransaction(consensusTxn);

        // Then - the consensus time should be tracked (verified by no exception)
        assertEquals(QUIESCE, controller.getQuiescenceStatus());
    }

    private static class TestConsensusTransaction extends TransactionWrapper {
        private final Instant consensusTimestamp;

        TestConsensusTransaction(Transaction transaction, Instant consensusTimestamp) {
            super(transaction.getApplicationTransaction());
            setMetadata(transaction.getMetadata());
            this.consensusTimestamp = consensusTimestamp;
        }

        @Override
        public Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }
    }

    @Test
    void switchTrackerWithNoPreviousBlock() {
        final boolean finishedPrevious = controller.switchTracker(1);
        assertFalse(finishedPrevious);
        assertEquals(QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void switchTrackerWithPreviousBlock() {
        // Given - a block has been started
        controller.startingBlock(1);

        // When - we switch to a new block
        final boolean finishedPrevious = controller.switchTracker(2);

        // Then - should return true since there was a previous block
        assertTrue(finishedPrevious, "Should return true when previous block exists");
        assertEquals(QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void switchTrackerWhenDisabled() {
        // Given - quiescence is disabled
        controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(3)), time::now, pendingTransactions::get);

        // When - we try to switch tracker
        final boolean finishedPrevious = controller.switchTracker(1);

        // Then - should return false and nothing should happen
        assertFalse(finishedPrevious, "Should return false when quiescence is disabled");
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void switchTrackerFinalizesAndStartsNewBlock() {
        // Given - a block has been started with a transaction
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        final var blockTracker1 = controller.startingBlock(1);
        assertNotNull(blockTracker1);
        blockTracker1.blockTransaction(createTransaction(TXN_TRANSFER));

        // When - we switch to a new block
        final boolean finishedPrevious = controller.switchTracker(2);

        // Then - the previous block should be finalized and a new one started
        assertTrue(finishedPrevious, "Should return true when previous block exists");
        // The transaction is still in the pipeline until the block is fully signed
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus());
    }

    @Test
    void switchTrackerMultipleTimes() {
        // Given - we start with block 1
        controller.switchTracker(1);

        // When - we switch multiple times
        final boolean finished1 = controller.switchTracker(2);
        final boolean finished2 = controller.switchTracker(3);
        final boolean finished3 = controller.switchTracker(4);

        // Then - each switch should finalize the previous block
        assertTrue(finished1, "Should finalize block 1");
        assertTrue(finished2, "Should finalize block 2");
        assertTrue(finished3, "Should finalize block 3");
        assertEquals(QUIESCE, controller.getQuiescenceStatus());
    }

    private ConsensusTransaction createConsensusTransaction(@NonNull final Instant consensusTime) {
        final Transaction transaction = createTransaction(QuiescenceControllerTest.TXN_TRANSFER);
        return new TestConsensusTransaction(transaction, consensusTime);
    }
}
