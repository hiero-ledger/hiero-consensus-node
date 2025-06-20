// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.token.TokenTxnUsage.tokenEntitySizes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenAssociateUsageTest {
    private final long now = 1_234_567L;
    private final long expiry = now + 1_000_000L;
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final TokenID firstId = IdUtils.asToken("0.0.75231");
    private final TokenID secondId = IdUtils.asToken("0.0.75232");
    private final AccountID id = IdUtils.asAccount("1.2.3");

    private TokenAssociateTransactionBody op;
    private TransactionBody txn;

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private TokenAssociateUsage subject;

    @BeforeEach
    void setup() {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
    }

    @Test
    void assessesEverything() {
        givenOpWithTwoAssociations();
        // and:
        subject = TokenAssociateUsage.newEstimate(txn, base);

        // when:
        final var usage = subject.givenCurrentExpiry(expiry).get();

        // then:
        assertEquals(A_USAGES_MATRIX, usage);
        // and:
        verify(base, times(3)).addBpt(FeeBuilder.BASIC_ENTITY_ID_SIZE);
        // and:
        verify(base).addRbs(2 * tokenEntitySizes.bytesUsedPerAccountRelationship() * (expiry - now));
    }

    private void givenOpWithTwoAssociations() {
        op = TokenAssociateTransactionBody.newBuilder()
                .setAccount(id)
                .addTokens(firstId)
                .addTokens(secondId)
                .build();
        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setTokenAssociate(op)
                .build();
    }
}
