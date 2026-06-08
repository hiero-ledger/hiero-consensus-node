// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class CommonPbjConvertersTest {

    @Test
    void keyFromProtoToPbj() {
        final var ed25519Bytes = ByteString.copyFrom(new byte[32]);
        final var protoKey = com.hederahashgraph.api.proto.java.Key.newBuilder()
                .setEd25519(ed25519Bytes)
                .build();

        final var pbjKey = toPbj(protoKey);

        assertEquals(Key.KeyOneOfType.ED25519, pbjKey.key().kind());
        assertEquals(Bytes.wrap(ed25519Bytes.toByteArray()), pbjKey.ed25519OrThrow());
    }

    @Test
    void timestampFromProtoToPbj() {
        final var protoTimestamp = com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                .setSeconds(1_700_000_000L)
                .setNanos(42)
                .build();

        final var pbjTimestamp = toPbj(protoTimestamp);

        assertEquals(Timestamp.class, pbjTimestamp.getClass());
        assertEquals(1_700_000_000L, pbjTimestamp.seconds());
        assertEquals(42, pbjTimestamp.nanos());
    }

    @Test
    void transactionBodyFromProtoToPbj() {
        final var protoTxnId = com.hederahashgraph.api.proto.java.TransactionID.newBuilder()
                .setAccountID(com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                        .setAccountNum(7)
                        .build())
                .setTransactionValidStart(com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                        .setSeconds(1_700_000_000L)
                        .setNanos(0)
                        .build())
                .build();
        final var protoTxBody = com.hederahashgraph.api.proto.java.TransactionBody.newBuilder()
                .setTransactionID(protoTxnId)
                .setTransactionFee(1_000_000L)
                .setMemo("test-memo")
                .build();

        final var pbjTxBody = toPbj(protoTxBody);

        assertEquals(TransactionBody.class, pbjTxBody.getClass());
        assertEquals(7, pbjTxBody.transactionIDOrThrow().accountIDOrThrow().accountNum());
        assertEquals(
                1_700_000_000L,
                pbjTxBody.transactionIDOrThrow().transactionValidStartOrThrow().seconds());
        assertEquals(1_000_000L, pbjTxBody.transactionFee());
        assertEquals("test-memo", pbjTxBody.memo());
    }
}
