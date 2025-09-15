package com.hedera.node.app.quiescence;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuiescenceControllerTest {
    private static final QuiescenceConfig CONFIG_ENABLED = new QuiescenceConfig(true, Duration.ofSeconds(3));
    private static final QuiescenceConfig CONFIG_DISABLED = new QuiescenceConfig(false, Duration.ofSeconds(3));
    private static final SignedTransaction TXN_TRANSFER = SignedTransaction.newBuilder()
            .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                    .build()))
            .build();
    private static final SignedTransaction TXN_STATE_SIG = SignedTransaction.newBuilder()
            .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                    .stateSignatureTransaction(StateSignatureTransaction.DEFAULT)
                    .build()))
            .build();
    private static final SignedTransaction TXN_HINTS_SIG = SignedTransaction.newBuilder()
            .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                    .hintsPartialSignature(HintsPartialSignatureTransactionBody.DEFAULT)
                    .build()))
            .build();

    private final AtomicLong pendingTransactions = new AtomicLong();
    private FakeTime time;
    private QuiescenceController enabledController;
    private QuiescenceController disabledController;

    @BeforeEach
    void setUp() {
        pendingTransactions.set(0);
        time = new FakeTime();
        enabledController = new QuiescenceController(CONFIG_ENABLED, time, pendingTransactions::get);
        disabledController = new QuiescenceController(CONFIG_DISABLED, time, pendingTransactions::get);
    }

    @Test
    void test() {
        ;

    }
}