// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HcpqCanonicalTransactionTest {
    private static final SignaturePair HCPQ_SIGNATURE = SignaturePair.newBuilder()
            .pubKeyPrefix(Bytes.wrap(new byte[32]))
            .mlDsa44(Bytes.wrap(new byte[2420]))
            .build();

    @Test
    void requiresExactPbjEncodingOnlyForHcpq() {
        final var body = TransactionBody.DEFAULT;
        final var canonical = TransactionBody.PROTOBUF.toBytes(body);
        final var canonicalSigned = new SignedTransaction(
                canonical, SignatureMap.newBuilder().sigPair(HCPQ_SIGNATURE).build(), false);
        assertThat(TransactionChecker.hasCanonicalHcpqBody(canonicalSigned, body))
                .isTrue();

        final var alternate = Arrays.copyOf(canonical.toByteArray(), Math.toIntExact(canonical.length()) + 2);
        // An explicitly encoded empty memo is semantically the default but not canonical PBJ output.
        alternate[alternate.length - 2] = 0x32;
        alternate[alternate.length - 1] = 0x00;
        final var alternateHcpq =
                canonicalSigned.copyBuilder().bodyBytes(Bytes.wrap(alternate)).build();
        assertThat(TransactionChecker.hasCanonicalHcpqBody(alternateHcpq, body)).isFalse();

        final var classical = alternateHcpq
                .copyBuilder()
                .sigMap(SignatureMap.newBuilder()
                        .sigPair(SignaturePair.newBuilder()
                                .pubKeyPrefix(Bytes.EMPTY)
                                .ed25519(Bytes.wrap(new byte[64]))
                                .build())
                        .build())
                .build();
        assertThat(TransactionChecker.hasCanonicalHcpqBody(classical, body)).isTrue();
    }
}
