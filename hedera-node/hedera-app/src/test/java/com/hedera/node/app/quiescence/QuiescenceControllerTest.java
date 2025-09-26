// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        controller = new QuiescenceController(CONFIG, time, pendingTransactions::get);
    }

    @Test
    void basicBehavior() {
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Initially the status should be quiescent");
        controller.onPreHandle(createEvent(TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Since a transaction was received through pre-handle, the status should be not quiescent");
        controller.fullySignedBlock(createBlock(TXN_TRANSFER));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Once that transaction has been included in a block, the status should be quiescent again");
    }

    @Test
    void signaturesAreIgnored() {
        assertEquals(QUIESCE, controller.getQuiescenceStatus(), "Initially the status should be quiescent");
        controller.onPreHandle(createEvent(TXN_STATE_SIG, TXN_HINTS_SIG));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Signature transactions should be ignored, so the status should remain quiescent");
        controller.onPreHandle(createEvent(TXN_STATE_SIG, TXN_HINTS_SIG, TXN_TRANSFER));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "A single non-signature transaction should make the status not quiescent");
        controller.fullySignedBlock(createBlock(TXN_STATE_SIG, TXN_HINTS_SIG));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Signature transactions should be ignored, so the status should remain not quiescent");
    }

    @Test
    void staleEvents() {
        controller.onPreHandle(createEvent(TXN_TRANSFER));
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
        controller.fullySignedBlock(createBlock(time.now()));
        time.tick(CONFIG.tctDuration().multipliedBy(2));
        assertEquals(
                DONT_QUIESCE,
                controller.getQuiescenceStatus(),
                "Wall-clock time has advanced past the TCT, but consensus time has not, so the status should remain not quiescent");
        controller.fullySignedBlock(createBlock(time.now()));
        assertEquals(
                QUIESCE,
                controller.getQuiescenceStatus(),
                "Consensus time has now advanced past the TCT, so the status should be quiescent again");
    }

    @Test
    void platformStatusUpdate() {
        controller.onPreHandle(createEvent(TXN_TRANSFER));
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
                QUIESCE,
                controller.getQuiescenceStatus(),
                "The reconnect complete status should reset the controller");
    }

    @Test
    void quiescenceBreaking(){
        //TODO
    }

    private Block createBlock(final TransactionBody... txns) {
        final List<BlockItem> blockItems = Arrays.stream(txns)
                .map(TransactionBody.PROTOBUF::toBytes)
                .map(b -> SignedTransaction.newBuilder().bodyBytes(b).build())
                .map(SignedTransaction.PROTOBUF::toBytes)
                .map(b -> BlockItem.newBuilder().signedTransaction(b).build())
                .toList();
        return Block.newBuilder().items(blockItems).build();
    }

    private Block createBlock(final Instant consensusTime) {
        final BlockItem blockItem = BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(HapiUtils.asTimestamp(consensusTime))
                        .build())
                .build();
        return Block.newBuilder().items(List.of(blockItem)).build();
    }

    private Event createEvent(final TransactionBody... txns) {
        final List<Bytes> transactions = Arrays.stream(txns)
                .map(TransactionBody.PROTOBUF::toBytes)
                .map(b -> SignedTransaction.newBuilder().bodyBytes(b).build())
                .map(SignedTransaction.PROTOBUF::toBytes)
                .toList();

        return new TestingEventBuilder(new Random())
                .setTransactionBytes(transactions)
                .build();
    }
}
