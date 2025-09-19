package com.hedera.node.app.quiescence;

import static com.hedera.node.app.quiescence.QuiescenceStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.SignedTransaction.Builder;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuiescenceControllerTest {
    private static final QuiescenceConfig CONFIG_ENABLED = new QuiescenceConfig(true, Duration.ofSeconds(3));
    private static final TransactionBody TXN_TRANSFER = TransactionBody.newBuilder()
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT).build();
    private static final TransactionBody TXN_STATE_SIG = TransactionBody.newBuilder()
            .stateSignatureTransaction(StateSignatureTransaction.DEFAULT).build();
    private static final TransactionBody TXN_HINTS_SIG = TransactionBody.newBuilder()
            .hintsPartialSignature(HintsPartialSignatureTransactionBody.DEFAULT).build();

    private final AtomicLong pendingTransactions = new AtomicLong();
    private FakeTime time;
    private QuiescenceController controller;

    @BeforeEach
    void setUp() {
        pendingTransactions.set(0);
        time = new FakeTime();
        controller = new QuiescenceController(CONFIG_ENABLED, time, pendingTransactions::get);
    }

    @Test
    void basicBehavior() {
        assertEquals(QUIESCENT, controller.getQuiescenceStatus(),
                "Initially the status should be quiescent");
        controller.onPreHandle(createEvent(TXN_TRANSFER));
        assertEquals(NOT_QUIESCENT, controller.getQuiescenceStatus(),
                "Since a transaction was received through pre-handle, the status should be not quiescent");
        controller.fullySignedBlock(createBlock(TXN_TRANSFER));
        assertEquals(QUIESCENT, controller.getQuiescenceStatus(),
                "Once that transaction has been included in a block, the status should be quiescent again");
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

    private Event createEvent(final TransactionBody... txns) {
        final List<Bytes> transactions = Arrays.stream(txns)
                .map(TransactionBody.PROTOBUF::toBytes)
                .map(b -> SignedTransaction.newBuilder().bodyBytes(b).build())
                .map(SignedTransaction.PROTOBUF::toBytes)
                .toList();

        return new TestingEventBuilder(new Random()).setTransactionBytes(transactions).build();
    }
}