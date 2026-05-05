// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import org.junit.jupiter.api.Test;

class HapiTxnOpTest {

    @Test
    void signatureMapFromProtoToPbj() {
        final var pubKeyPrefix = ByteString.copyFrom(new byte[]{1, 2, 3, 4});
        final var ed25519Sig = ByteString.copyFrom(new byte[64]);
        final var protoSigMap = com.hederahashgraph.api.proto.java.SignatureMap.newBuilder()
                .addSigPair(com.hederahashgraph.api.proto.java.SignaturePair.newBuilder()
                        .setPubKeyPrefix(pubKeyPrefix)
                        .setEd25519(ed25519Sig))
                .build();

        assertDoesNotThrow(() -> {
            final var pbjSigMap = protoToPbj(protoSigMap, SignatureMap.class);
            assertEquals(1, pbjSigMap.sigPair().size());
            assertEquals(
                    com.hedera.pbj.runtime.io.buffer.Bytes.wrap(pubKeyPrefix.toByteArray()),
                    pbjSigMap.sigPair().getFirst().pubKeyPrefix());
        });
    }

    @Test
    void transactionIdFromProtoToPbj() {
        final var protoAccountId = com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                .setAccountNum(42)
                .build();
        final var protoTxnId = com.hederahashgraph.api.proto.java.TransactionID.newBuilder()
                .setAccountID(protoAccountId)
                .setScheduled(true)
                .build();

        assertDoesNotThrow(() -> {
            final var pbjTxnId = protoToPbj(protoTxnId, TransactionID.class);
            assertEquals(42, pbjTxnId.accountIDOrThrow().accountNum());
            assertTrue(pbjTxnId.scheduled());
        });
    }
}
