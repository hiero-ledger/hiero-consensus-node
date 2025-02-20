// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.test.IdUtils.asAccount;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoDeleteAllowanceMeta.countNftDeleteSerials;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_DELETE_ALLOWANCE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.NftRemoveAllowance;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoDeleteAllowanceMetaTest {
    private final AccountID proxy = asAccount("0.0.1234");
    private final NftRemoveAllowance nftAllowances = NftRemoveAllowance.newBuilder()
            .setOwner(proxy)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .addAllSerialNumbers(List.of(1L, 2L, 3L))
            .build();

    @BeforeEach
    void setUp() {}

    @Test
    void allGettersAndToStringWork() {
        final var expected = "CryptoDeleteAllowanceMeta{effectiveNow=1234567, msgBytesUsed=112}";
        final var now = 1_234_567;
        final var subject = CryptoDeleteAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .effectiveNow(now)
                .build();

        assertEquals(now, subject.getEffectiveNow());
        assertEquals(112, subject.getMsgBytesUsed());
        assertEquals(expected, subject.toString());
    }

    @Test
    void calculatesBaseSizeAsExpected() {
        final var op = CryptoDeleteAllowanceTransactionBody.newBuilder()
                .addAllNftAllowances(List.of(nftAllowances))
                .build();
        final var canonicalTxn =
                TransactionBody.newBuilder().setCryptoDeleteAllowance(op).build();

        final var subject = new CryptoDeleteAllowanceMeta(
                op, canonicalTxn.getTransactionID().getTransactionValidStart().getSeconds());

        final var expectedMsgBytes = (op.getNftAllowancesCount() * NFT_DELETE_ALLOWANCE_SIZE)
                + countNftDeleteSerials(op.getNftAllowancesList()) * LONG_SIZE;

        assertEquals(expectedMsgBytes, subject.getMsgBytesUsed());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var now = 1_234_567;
        final var subject1 = CryptoDeleteAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .effectiveNow(now)
                .build();

        final var subject2 = CryptoDeleteAllowanceMeta.newBuilder()
                .msgBytesUsed(112)
                .effectiveNow(now)
                .build();

        assertEquals(subject1, subject2);
        assertEquals(subject1.hashCode(), subject2.hashCode());
    }
}
