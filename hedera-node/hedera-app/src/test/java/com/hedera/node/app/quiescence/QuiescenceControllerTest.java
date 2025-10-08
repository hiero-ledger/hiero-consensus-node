// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.hiero.consensus.model.quiescence.QuiescenceCommand.BREAK_QUIESCENCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.status.PlatformStatus;
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
        final QuiescenceBlockTracker blockTracker = controller.startingBlock(1);
        blockTracker.blockTransaction(createTransaction(TXN_TRANSFER));
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
        final QuiescenceBlockTracker blockTracker = controller.startingBlock(1);
        blockTracker.blockTransaction(createTransaction(TXN_STATE_SIG));
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
        final QuiescenceBlockTracker blockTracker1 = controller.startingBlock(1);
        blockTracker1.consensusTimeAdvanced(time.now());
        blockTracker1.finishedHandlingTransactions();
        controller.blockFullySigned(1);
        time.tick(CONFIG.tctDuration().multipliedBy(2));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Wall-clock time has advanced past the TCT, but consensus time has not, so the status should remain not quiescent");
        final QuiescenceBlockTracker blockTracker2 = controller.startingBlock(2);
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
}
