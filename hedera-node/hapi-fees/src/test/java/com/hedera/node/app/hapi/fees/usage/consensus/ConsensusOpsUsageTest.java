// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.test.AdapterUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
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
                .setNetworkdata(FeeComponents.newBuilder()
                        .setConstant(1)
                        .setBpt(277)
                        .setVpt(3)
                        .setRbh(4))
                .setNodedata(FeeComponents.newBuilder()
                        .setConstant(1)
                        .setBpt(277)
                        .setVpt(1)
                        .setBpr(4))
                .setServicedata(FeeComponents.newBuilder().setConstant(1).setRbh(8))
                .build();
        final var accum = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);
        final var submitMeta = new SubmitMessageMeta(message.length(), 0);

        subject.submitMessageUsage(sigUsage, submitMeta, baseMeta, accum);
        final var actualLegacyRepr = AdapterUtils.feeDataFrom(accum);

        assertEquals(expected, actualLegacyRepr);
    }
}
