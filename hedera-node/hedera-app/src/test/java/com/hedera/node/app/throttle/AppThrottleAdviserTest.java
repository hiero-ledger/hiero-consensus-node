// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppThrottleAdviserTest {

    private static final long GAS_LIMIT = 456L;
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final TransactionBody CONTRACT_CALL_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .contractCall(
                    ContractCallTransactionBody.newBuilder().gas(GAS_LIMIT).build())
            .build();
    private static final TransactionInfo CONTRACT_CALL_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, CONTRACT_CALL_TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CONTRACT_CALL, null);
    private static final TransactionBody CRYPTO_TRANSFER_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
            .build();
    private static final TransactionInfo CRYPTO_TRANSFER_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, CRYPTO_TRANSFER_TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);

    @Mock
    private NetworkUtilizationManager networkUtilizationManager;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private RecordStreamBuilder oneChildBuilder;

    @Mock
    private RecordStreamBuilder twoChildBuilder;

    private static final Instant CONSENSUS_NOW = Instant.parse("2007-12-03T10:15:30.00Z");

    private AppThrottleAdviser subject;

    @BeforeEach
    void setup() {
        subject = new AppThrottleAdviser(networkUtilizationManager, CONSENSUS_NOW);
    }

    @Test
    void forwardsShouldThrottleNOfUnscaled() {
        subject.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER);
        verify(networkUtilizationManager).shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER, CONSENSUS_NOW);
    }

    @Test
    void forwardsShouldThrottleByOpsDuration() {
        subject.shouldThrottleByOpsDuration(100);
        verify(networkUtilizationManager).shouldThrottleByOpsDuration(100, CONSENSUS_NOW);
    }
}
