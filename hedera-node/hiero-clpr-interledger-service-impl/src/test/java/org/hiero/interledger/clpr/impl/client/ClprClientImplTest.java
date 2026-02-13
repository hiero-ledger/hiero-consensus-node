// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.hapi.interledger.clpr.ClprServiceInterface;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprClientImplTest {

    @Mock
    private ClprServiceInterface.ClprServiceClient serviceClient;

    private ClprClientImpl subject;

    private ClprLedgerConfiguration sampleConfiguration;

    @BeforeEach
    void setUp() {
        subject = new ClprClientImpl(serviceClient, ClprClientImpl.TransactionSigner.NO_OP);
        sampleConfiguration = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("ledger-123".getBytes()))
                        .build())
                .timestamp(Timestamp.newBuilder().seconds(100).nanos(0).build())
                .build();
    }

    @Test
    void setConfigurationSubmitsProvidedProof() throws Exception {
        when(serviceClient.setLedgerConfiguration(any(Transaction.class)))
                .thenReturn(TransactionResponse.newBuilder()
                        .nodeTransactionPrecheckCode(ResponseCodeEnum.SUCCESS)
                        .build());

        final var payerAccountId = AccountID.newBuilder().accountNum(42).build();
        final var nodeAccountId = AccountID.newBuilder().accountNum(7).build();
        final var proof = ClprStateProofUtils.buildLocalClprStateProofWrapper(sampleConfiguration);
        final var status = subject.setConfiguration(payerAccountId, nodeAccountId, proof);

        assertEquals(ResponseCodeEnum.SUCCESS, status);
        final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(serviceClient).setLedgerConfiguration(captor.capture());
        final var tx = captor.getValue();
        assertNotNull(tx);
        final var signed = SignedTransaction.PROTOBUF.parse(tx.signedTransactionBytes());
        final var body = TransactionBody.PROTOBUF.parse(signed.bodyBytes());
        assertEquals(120, body.transactionValidDurationOrThrow().seconds());
        assertEquals(payerAccountId, body.transactionIDOrThrow().accountIDOrThrow());
        assertEquals(nodeAccountId, body.nodeAccountIDOrThrow());
        assertEquals(proof, body.clprSetLedgerConfigurationOrThrow().ledgerConfigurationProofOrThrow());
    }

    @Test
    void setConfigurationReturnsFailOnException() {
        when(serviceClient.setLedgerConfiguration(any(Transaction.class)))
                .thenThrow(new RuntimeException("network failure"));

        final var payerAccountId = AccountID.newBuilder().accountNum(42).build();
        final var nodeAccountId = AccountID.newBuilder().accountNum(7).build();
        final var proof = ClprStateProofUtils.buildLocalClprStateProofWrapper(sampleConfiguration);
        final var status = subject.setConfiguration(payerAccountId, nodeAccountId, proof);

        assertEquals(ResponseCodeEnum.FAIL_INVALID, status);
    }
}
