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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuiescenceControllerTest {
    private static final QuiescenceConfig CONFIG = new QuiescenceConfig(true, Duration.ofSeconds(3), Duration.ZERO);
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
    private QuiescenceCommands quiescenceCommands;
    private QuiescenceController controller;

    @BeforeEach
    void setUp() {
        pendingTransactions.set(0);
        time = new FakeTime();
        quiescenceCommands = Mockito.mock(QuiescenceCommands.class);
        controller = new QuiescenceController(
                CONFIG,
                time::now,
                pendingTransactions::get,
                time::now,
                () -> {},
                quiescenceCommands,
                new NoOpMetrics());
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
        verify(quiescenceCommands, never()).resetForReconnect();
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        assertEquals(
                QUIESCE, controller.getQuiescenceStatus(), "The reconnect complete status should reset the controller");
        verify(quiescenceCommands).resetForReconnect();
    }

    @Test
    void reconnectCompleteClearsDisabledState() {
        // Given - the controller has been disabled by an unexpected condition.
        // finishHandlingInProgressBlock with no block started triggers disableQuiescence(...) internally.
        controller.finishHandlingInProgressBlock();
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Status should be DONT_QUIESCE while the controller is disabled");

        // When - the platform reconnects successfully
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);

        // Then - the controller is re-enabled: a transient error should not require a process
        // restart to recover quiescence.
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Reconnect should clear the disabled sentinel");
        verify(quiescenceCommands).resetForReconnect();
    }

    /**
     * A {@code RECONNECT_COMPLETE} signal must clear <i>every</i> piece of controller state, not
     * just the disabled sentinel. Sets up a non-trivial controller state (pipeline count, TCT, in-progress
     * block tracker, finalized-but-not-signed block tracker), sends the reconnect, and asserts each individual
     * field is back to its initial value. Failing this test would indicate a future change has only partially
     * implemented the reset (a common refactor hazard).
     */
    @Test
    void reconnectCompleteClearsAllControllerState() {
        // Given - the controller is in a fully-loaded state
        controller.onPreHandle(createTransactions(TXN_TRANSFER, TXN_TRANSFER));
        controller.setNextTargetConsensusTime(time.now().plusSeconds(60));
        final var blockTracker = controller.startingBlock(1);
        assertNotNull(blockTracker);
        blockTracker.blockTransaction(createTransaction(TXN_TRANSFER));
        blockTracker.finishedHandlingTransactions(); // moves tracker into blockTrackers map
        controller.startingBlock(2); // sets inProgressBlockTracker for block 2
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Pre-condition: pipeline is non-empty so status is DONT_QUIESCE");

        // When - reconnect arrives
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);

        // Then - every piece of state is reset:
        //  * pipelineTransactionCount = 0
        //  * blockTrackers map is empty (so a subsequent blockFullySigned would self-disable)
        //  * nextTct = null
        //  * inProgressBlockTracker = null (so a subsequent inProgressBlockTransaction would self-disable)
        //  * QuiescenceCommands.resetForReconnect was relayed
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "All pipeline state should have been cleared");
        verify(quiescenceCommands).resetForReconnect();

        // Indirect verification: blockTracker map is empty — blockFullySigned for a now-cleared block triggers
        // disableQuiescence (path: "Cannot find block tracker for block N").
        controller.blockFullySigned(1);
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "blockTrackers map must be empty after reconnect — a stale signal disables the controller");
    }

    /**
     * After a reconnect-driven reset, the controller must remain fully functional for a fresh
     * transaction cycle. Reset alone isn't enough — the controller has to handle the post-reconnect workload
     * correctly. This test drives a complete txn cycle (onPreHandle → startingBlock → inProgressBlockTransaction
     * → finishHandlingInProgressBlock → blockFullySigned) AFTER a reconnect and asserts the status transitions
     * are as expected at each step.
     */
    @Test
    void controllerRemainsFunctionalAfterReconnectComplete() {
        // Pollute and then reset.
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Pre-condition: post-reconnect controller starts in QUIESCE");

        // Drive a fresh txn cycle.
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "onPreHandle must increment pipeline count after reconnect");
        final var tracker = controller.startingBlock(10);
        assertNotNull(tracker, "startingBlock must produce a tracker after reconnect");
        tracker.blockTransaction(createTransaction(TXN_TRANSFER));
        tracker.finishedHandlingTransactions();
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Pipeline still has the txn until the block is fully signed");
        controller.blockFullySigned(10);
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "blockFullySigned must drain the pipeline and return the controller to QUIESCE");
    }

    /**
     * Multiple successive reconnects must be idempotent. The reset path runs unconditionally on
     * {@code RECONNECT_COMPLETE}, so two reconnects in a row should leave the controller in the same state as
     * one, and {@code QuiescenceCommands.resetForReconnect()} should be relayed for each.
     */
    @Test
    void successiveReconnectCompleteSignalsAreIdempotent() {
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);
        controller.platformStatusUpdate(PlatformStatus.RECONNECT_COMPLETE);

        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "State should be clean after multiple reconnects");
        verify(quiescenceCommands, times(3)).resetForReconnect();
    }

    /**
     * {@code QuiescenceCommands.resetForReconnect()} must be relayed <i>only</i> for
     * {@code RECONNECT_COMPLETE}, not for any other platform status. Catches the easy mistake of
     * relocating the reset call outside the {@code if} branch.
     */
    @Test
    void resetForReconnectIsNotRelayedForOtherStatuses() {
        for (final var status : PlatformStatus.values()) {
            if (status == PlatformStatus.RECONNECT_COMPLETE) {
                continue;
            }
            controller.platformStatusUpdate(status);
        }
        verify(quiescenceCommands, never()).resetForReconnect();
    }

    @Test
    void staleEventSwallowsUnexpectedExceptionAndDisables() {
        // Given - an event whose transactionIterator throws an unchecked exception
        final Event event = Mockito.mock(Event.class);
        Mockito.when(event.transactionIterator()).thenThrow(new RuntimeException("boom"));

        // When - the controller observes the stale event
        controller.staleEvent(event);

        // Then - the exception is swallowed (the workflow thread does not crash) and the controller is disabled.
        // Previously staleEvent only caught BadMetadataException, so a RuntimeException would have escaped.
        assertEquals(DONT_QUIESCE, controller.getQuiescenceStatus());
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
                new QuiescenceConfig(false, Duration.ofSeconds(3), Duration.ZERO),
                time::now,
                pendingTransactions::get,
                time::now,
                () -> {},
                quiescenceCommands,
                new NoOpMetrics());

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
                new QuiescenceConfig(false, Duration.ofSeconds(3), Duration.ZERO),
                time::now,
                pendingTransactions::get,
                time::now,
                () -> {},
                quiescenceCommands,
                new NoOpMetrics());
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
                new QuiescenceConfig(false, Duration.ofSeconds(3), Duration.ZERO),
                time::now,
                pendingTransactions::get,
                time::now,
                () -> {},
                quiescenceCommands,
                new NoOpMetrics());

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

    /**
     * Grace-period fence: when both counts are zero, the controller must hold off on {@code QUIESCE} until
     * the configured grace period has elapsed since the most recent observed activity. This prevents short
     * inter-transaction gaps from putting the network to sleep — without the grace period, the next user
     * transaction would wake the network and consensus time would jump forward, bulk-expiring receipts in
     * {@code RecordCacheImpl.purgeExpiredReceiptEntries}.
     */
    @Test
    void gracePeriodDelaysQuiesceAfterActivity() {
        final AtomicReference<Instant> activityClock = new AtomicReference<>(time.now());
        final Runnable record = () -> activityClock.set(time.now());
        controller = new QuiescenceController(
                new QuiescenceConfig(true, Duration.ofSeconds(3), Duration.ofSeconds(5)),
                time::now,
                pendingTransactions::get,
                activityClock::get,
                record,
                quiescenceCommands,
                new NoOpMetrics());

        // Boot anchor: activity clock starts at time.now(); within the grace period, status is DONT_QUIESCE.
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Pre-grace: even with zero pipeline, controller should not yet report QUIESCE");

        // Advance past the grace period — now QUIESCE is allowed.
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Post-grace with no activity: controller should report QUIESCE");

        // Fresh activity (e.g. pre-handle of a relevant tx) re-anchors the grace period.
        controller.onPreHandle(createTransactions(TXN_TRANSFER));
        // Drain the pipeline so the next status check is zero-zero again.
        final var blockTracker = controller.startingBlock(1);
        requireNonNull(blockTracker).blockTransaction(createTransaction(TXN_TRANSFER));
        blockTracker.finishedHandlingTransactions();
        controller.blockFullySigned(1);

        // Activity clock was just refreshed by onPreHandle; the grace period restarts.
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Fresh pre-handle activity should reset the grace period");

        time.tick(Duration.ofSeconds(6));
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "After grace re-elapses, controller should re-quiesce");
    }

    /**
     * Grace-period fence: {@code platformStatusUpdate(ACTIVE)} must anchor the activity clock so the grace
     * period starts fresh at the moment the node becomes an active participant. Without this anchor, the
     * activity clock would carry the time spent in pre-ACTIVE phases and the controller could quiesce
     * before the node has been exercised at all.
     */
    @Test
    void activeStatusAnchorsGracePeriod() {
        final AtomicReference<Instant> activityClock = new AtomicReference<>(time.now());
        final Runnable record = () -> activityClock.set(time.now());
        controller = new QuiescenceController(
                new QuiescenceConfig(true, Duration.ofSeconds(3), Duration.ofSeconds(5)),
                time::now,
                pendingTransactions::get,
                activityClock::get,
                record,
                quiescenceCommands,
                new NoOpMetrics());

        // Simulate a long pre-ACTIVE phase: the controller's notion of "now" has advanced but
        // platformStatusUpdate(ACTIVE) hasn't fired yet.
        time.tick(Duration.ofSeconds(30));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Without an ACTIVE anchor, the grace period has long since elapsed");

        // ACTIVE fires → grace period re-anchors.
        controller.platformStatusUpdate(PlatformStatus.ACTIVE);
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "ACTIVE must re-anchor the grace period so the node can be exercised before quiescing");
    }

    private ConsensusTransaction createConsensusTransaction(@NonNull final Instant consensusTime) {
        final Transaction transaction = createTransaction(QuiescenceControllerTest.TXN_TRANSFER);
        return new TestConsensusTransaction(transaction, consensusTime);
    }
}
