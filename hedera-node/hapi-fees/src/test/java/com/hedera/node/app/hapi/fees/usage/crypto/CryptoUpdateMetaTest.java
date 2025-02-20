// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.Test;

class CryptoUpdateMetaTest {
    private final long keyBytes = 123;
    private final long msgBytes = 1_234;
    private final long memoSize = 20;
    private final long now = 1_234_567;
    private final long expiry = 2_234_567;
    private final boolean hasProxy = true;
    private final boolean hasMaxAutoAssociations = true;
    private final int maxAutoAssociations = 12;

    @Test
    void allGettersAndToStringWork() {
        final var expected = "CryptoUpdateMeta{keyBytesUsed=123, msgBytesUsed=1234, memoSize=20,"
                + " effectiveNow=1234567, expiry=2234567, hasProxy=true,"
                + " maxAutomaticAssociations=12, hasMaxAutomaticAssociations=true}";

        final var subject = new CryptoUpdateMeta.Builder()
                .keyBytesUsed(keyBytes)
                .msgBytesUsed(msgBytes)
                .memoSize(memoSize)
                .effectiveNow(now)
                .expiry(expiry)
                .hasProxy(hasProxy)
                .maxAutomaticAssociations(maxAutoAssociations)
                .hasMaxAutomaticAssociations(hasMaxAutoAssociations)
                .build();

        assertEquals(keyBytes, subject.getKeyBytesUsed());
        assertEquals(msgBytes, subject.getMsgBytesUsed());
        assertEquals(memoSize, subject.getMemoSize());
        assertEquals(now, subject.getEffectiveNow());
        assertEquals(expiry, subject.getExpiry());
        assertTrue(subject.hasProxy());
        assertTrue(subject.hasMaxAutomaticAssociations());
        assertEquals(maxAutoAssociations, subject.getMaxAutomaticAssociations());
        assertEquals(expected, subject.toString());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var subject1 = new CryptoUpdateMeta.Builder()
                .keyBytesUsed(keyBytes)
                .msgBytesUsed(msgBytes)
                .memoSize(memoSize)
                .effectiveNow(now)
                .expiry(expiry)
                .hasProxy(hasProxy)
                .maxAutomaticAssociations(maxAutoAssociations)
                .hasMaxAutomaticAssociations(hasMaxAutoAssociations)
                .build();

        final var subject2 = new CryptoUpdateMeta.Builder()
                .keyBytesUsed(keyBytes)
                .msgBytesUsed(msgBytes)
                .memoSize(memoSize)
                .effectiveNow(now)
                .expiry(expiry)
                .hasProxy(hasProxy)
                .maxAutomaticAssociations(maxAutoAssociations)
                .hasMaxAutomaticAssociations(hasMaxAutoAssociations)
                .build();

        assertEquals(subject1, subject2);
        assertEquals(subject1.hashCode(), subject2.hashCode());
    }

    @Test
    void calculatesSizesAsExpected() {
        final var memo = "updateMemo";
        final var accountID = AccountID.newBuilder().setAccountNum(1_234L).build();
        final var proxyID = AccountID.newBuilder().setAccountNum(1_230L).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                        .setMemo(StringValue.of(memo))
                        .setMaxAutomaticTokenAssociations(Int32Value.of(5))
                        .setProxyAccountID(proxyID)
                        .setAutoRenewPeriod(Duration.newBuilder().setSeconds(expiry))
                        .setAccountIDToUpdate(accountID)
                        .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry)))
                .build();

        final var expectedMsgBytes =
                BASIC_ENTITY_ID_SIZE + memo.length() + LONG_SIZE + LONG_SIZE + BASIC_ENTITY_ID_SIZE + INT_SIZE;

        final var subject = new CryptoUpdateMeta(
                canonicalTxn.getCryptoUpdateAccount(),
                canonicalTxn.getTransactionID().getTransactionValidStart().getSeconds());

        assertEquals(0, subject.getKeyBytesUsed());
        assertEquals(expectedMsgBytes, subject.getMsgBytesUsed());
        assertEquals(memo.length(), subject.getMemoSize());
        assertEquals(expiry, subject.getExpiry());
        assertTrue(subject.hasProxy());
        assertTrue(subject.hasMaxAutomaticAssociations());
        assertEquals(5, subject.getMaxAutomaticAssociations());
    }
}
