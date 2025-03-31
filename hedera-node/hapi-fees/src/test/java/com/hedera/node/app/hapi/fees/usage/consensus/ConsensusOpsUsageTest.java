// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.node.app.hapi.fees.test.AdapterUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import org.junit.jupiter.api.Test;

class ConsensusOpsUsageTest {
    private static final int numSigs = 3;
    private static final int sigSize = 100;
    private static final int numPayerKeys = 1;
    private static final String memo = "The commonness of thoughts and images";
    private static final String message = "That have the frenzy of our Western seas";
    private static final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);

    private static final ConsensusOpsUsage subject = new ConsensusOpsUsage();

    @Test
    void matchesLegacyEstimate() {
        final var expected = FeeData.newBuilder()
                .networkdata(
                        FeeComponents.newBuilder().constant(1).bpt(277).vpt(3).rbh(4))
                .nodedata(FeeComponents.newBuilder().constant(1).bpt(277).vpt(1).bpr(4))
                .servicedata(FeeComponents.newBuilder().constant(1).rbh(8))
                .build();
        final var accum = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);
        final var submitMeta = new SubmitMessageMeta(message.length());

        subject.submitMessageUsage(sigUsage, submitMeta, baseMeta, accum);
        final var actualLegacyRepr = AdapterUtils.feeDataFrom(accum);

        assertEquals(expected, actualLegacyRepr);
    }
}
