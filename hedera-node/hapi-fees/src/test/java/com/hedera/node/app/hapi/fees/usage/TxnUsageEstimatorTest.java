// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.fees.test.SigUtils.A_SIG_MAP;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGE_VECTOR;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.HRS_DIVISOR;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TxnUsageEstimatorTest {
    private final int numPayerKeys = 2;
    private final long networkRbs = 123;
    private final SigUsage sigUsage =
            new SigUsage(A_SIG_MAP.getSigPairCount(), A_SIG_MAP.getSerializedSize(), numPayerKeys);
    private final TransactionBody txn = TransactionBody.newBuilder().build();

    private EstimatorUtils utils;
    private TxnUsageEstimator subject;

    @BeforeEach
    void setUp() throws Exception {
        utils = mock(EstimatorUtils.class);

        subject = new TxnUsageEstimator(sigUsage, txn, utils);
    }

    @Test
    void plusHelpersWork() {
        given(utils.nonDegenerateDiv(anyLong(), anyInt())).willReturn(1L);
        given(utils.baseNetworkRbs()).willReturn(networkRbs);
        given(utils.baseEstimate(txn, sigUsage)).willReturn(baseEstimate());
        given(utils.withDefaultTxnPartitioning(
                        expectedEstimate().build(),
                        SubType.DEFAULT,
                        ESTIMATOR_UTILS.nonDegenerateDiv(2 * networkRbs, HRS_DIVISOR),
                        sigUsage.numPayerKeys()))
                .willReturn(A_USAGES_MATRIX);
        // and:
        subject.addBpt(A_USAGE_VECTOR.getBpt())
                .addVpt(A_USAGE_VECTOR.getVpt())
                .addRbs(A_USAGE_VECTOR.getRbh() * HRS_DIVISOR)
                .addSbs(A_USAGE_VECTOR.getSbh() * HRS_DIVISOR)
                .addGas(A_USAGE_VECTOR.getGas())
                .addTv(A_USAGE_VECTOR.getTv())
                .addNetworkRbs(networkRbs);

        // when:
        final var actual = subject.get();

        // then:
        assertSame(A_USAGES_MATRIX, actual);
    }

    private UsageEstimate expectedEstimate() {
        final var updatedUsageVector = A_USAGE_VECTOR.toBuilder()
                .setBpt(2 * A_USAGE_VECTOR.getBpt())
                .setVpt(2 * A_USAGE_VECTOR.getVpt())
                .setGas(2 * A_USAGE_VECTOR.getGas())
                .setTv(2 * A_USAGE_VECTOR.getTv());
        final var base = new UsageEstimate(updatedUsageVector);
        base.addRbs(2 * A_USAGE_VECTOR.getRbh() * HRS_DIVISOR);
        base.addSbs(2 * A_USAGE_VECTOR.getSbh() * HRS_DIVISOR);
        return base;
    }

    private UsageEstimate baseEstimate() {
        final var updatedUsageVector = A_USAGE_VECTOR.toBuilder()
                .setBpt(A_USAGE_VECTOR.getBpt())
                .setVpt(A_USAGE_VECTOR.getVpt())
                .setGas(A_USAGE_VECTOR.getGas())
                .setTv(A_USAGE_VECTOR.getTv());
        final var base = new UsageEstimate(updatedUsageVector);
        base.addRbs(A_USAGE_VECTOR.getRbh() * HRS_DIVISOR);
        base.addSbs(A_USAGE_VECTOR.getSbh() * HRS_DIVISOR);
        return base;
    }

    @Test
    void baseEstimateDelegatesAsExpected() {
        given(utils.nonDegenerateDiv(anyLong(), anyInt())).willReturn(1L);
        given(utils.baseNetworkRbs()).willReturn(networkRbs);
        given(utils.baseEstimate(txn, sigUsage)).willReturn(baseEstimate());
        given(utils.withDefaultTxnPartitioning(
                        baseEstimate().build(),
                        SubType.DEFAULT,
                        ESTIMATOR_UTILS.nonDegenerateDiv(networkRbs, HRS_DIVISOR),
                        sigUsage.numPayerKeys()))
                .willReturn(A_USAGES_MATRIX);

        // when:
        final var actual = subject.get();

        // then:
        assertSame(A_USAGES_MATRIX, actual);
    }
}
