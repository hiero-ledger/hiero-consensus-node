// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.times;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenRevokeKycTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenRevokeKycUsageTest {
    private final long now = 1_234_567L;
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final TokenID id = IdUtils.asToken("0.0.75231");

    private TokenRevokeKycTransactionBody op;
    private TransactionBody txn;

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private TokenRevokeKycUsage subject;

    @BeforeEach
    void setUp() throws Exception {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
    }

    @Test
    void createsExpectedDelta() {
        givenOp();
        // and:
        subject = TokenRevokeKycUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base, times(2)).addBpt(FeeBuilder.BASIC_ENTITY_ID_SIZE);
    }

    @Test
    void assertSelf() {
        subject = TokenRevokeKycUsage.newEstimate(txn, base);
        assertEquals(subject, subject.self());
    }

    private void givenOp() {
        op = TokenRevokeKycTransactionBody.newBuilder().setToken(id).build();
        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setTokenRevokeKyc(op)
                .build();
    }
}
