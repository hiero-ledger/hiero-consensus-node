// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class UtilOpsUsageTest {
    private static final long now = 1_234_567L;
    private final UtilOpsUsage subject = new UtilOpsUsage();

    @Test
    void estimatesAutoRenewAsExpected() {
        final var op = UtilPrngTransactionBody.newBuilder().range(10).build();
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now)))
                .utilPrng(op)
                .build();

        final Bytes canonicalSig = Bytes.wrap("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(Bytes.wrap("a"))
                        .ed25519(canonicalSig)
                        .build())
                .build();
        final SigUsage singleSigUsage = new SigUsage(
                1, (int) SignatureMap.PROTOBUF.toBytes(onePairSigMap).length(), 1);
        final var opMeta = new UtilPrngMeta(txn.utilPrng());
        final var baseMeta = new BaseTransactionMeta(0, 0);

        final var actual = new UsageAccumulator();
        final var expected = new UsageAccumulator();

        expected.resetForTransaction(baseMeta, singleSigUsage);
        expected.addBpt(4);

        subject.prngUsage(singleSigUsage, baseMeta, opMeta, actual);

        assertEquals(expected, actual);
    }
}
