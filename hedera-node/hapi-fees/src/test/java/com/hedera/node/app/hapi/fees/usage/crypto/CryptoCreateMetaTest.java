// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import org.junit.jupiter.api.Test;

class CryptoCreateMetaTest {
    private final Key key = Key.newBuilder()
            .setEd25519(ByteString.copyFromUtf8("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();

    @Test
    void allGettersAndToStringWork() {
        final var expected =
                "CryptoCreateMeta{baseSize=1234, lifeTime=123456789, maxAutomaticAssociations=123, numHooks=0}";

        final var subject = new CryptoCreateMeta.Builder()
                .baseSize(1_234)
                .lifeTime(123_456_789L)
                .maxAutomaticAssociations(123)
                .build();

        assertEquals(1_234, subject.getBaseSize());
        assertEquals(123_456_789L, subject.getLifeTime());
        assertEquals(123, subject.getMaxAutomaticAssociations());
        assertEquals(expected, subject.toString());
    }

    @Test
    void calculatesBaseSizeAsExpected() {
        final var cryptoCreateTxnBody = CryptoCreateTransactionBody.newBuilder()
                .setMemo("")
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(7776000L))
                .setKey(key)
                .build();

        final var subject = new CryptoCreateMeta(cryptoCreateTxnBody);

        assertEquals(32, subject.getBaseSize());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var subject1 = new CryptoCreateMeta.Builder()
                .baseSize(1_234)
                .lifeTime(123_456_789L)
                .maxAutomaticAssociations(123)
                .build();

        final var subject2 = new CryptoCreateMeta.Builder()
                .baseSize(1_234)
                .lifeTime(123_456_789L)
                .maxAutomaticAssociations(123)
                .build();

        assertEquals(subject1, subject2);
        assertEquals(subject1.hashCode(), subject2.hashCode());
    }
}
