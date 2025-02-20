// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
import org.junit.jupiter.api.Test;

class UtilOpsUsageTest {
    private static final long now = 1_234_567L;
    private final UtilOpsUsage subject = new UtilOpsUsage();

    @Test
    void estimatesAutoRenewAsExpected() {
        final var op = UtilPrngTransactionBody.newBuilder().setRange(10).build();
        final var txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setUtilPrng(op)
                .build();

        final ByteString canonicalSig =
                ByteString.copyFromUtf8("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                        .setEd25519(canonicalSig))
                .build();
        final SigUsage singleSigUsage = new SigUsage(1, onePairSigMap.getSerializedSize(), 1);
        final var opMeta = new UtilPrngMeta(txn.getUtilPrng());
        final var baseMeta = new BaseTransactionMeta(0, 0);

        final var actual = new UsageAccumulator();
        final var expected = new UsageAccumulator();

        expected.resetForTransaction(baseMeta, singleSigUsage);
        expected.addBpt(4);

        subject.prngUsage(singleSigUsage, baseMeta, opMeta, actual);

        assertEquals(expected, actual);
    }
}
