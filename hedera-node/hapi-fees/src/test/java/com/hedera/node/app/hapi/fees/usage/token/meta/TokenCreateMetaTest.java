// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenCreateMetaTest {
    @Test
    void allGettersAndToStringWork() {
        final var expected = "TokenCreateMeta{baseSize=1235, lifeTime=1234567, customFeeScheduleSize=200,"
                + " fungibleNumTransfers=1, numTokens=1, networkRecordRb=1000, nftsTransfers=0,"
                + " subType=TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES}";

        final var subject = new TokenCreateMeta.Builder()
                .baseSize(1235)
                .lifeTime(1_234_567L)
                .customFeeScheleSize(200)
                .fungibleNumTransfers(1)
                .nftsTranfers(0)
                .numTokens(1)
                .networkRecordRb(1000)
                .subType(TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES)
                .build();

        assertEquals(1235, subject.getBaseSize());
        assertEquals(1_234_567L, subject.getLifeTime());
        assertEquals(200, subject.getCustomFeeScheduleSize());
        assertEquals(1, subject.getFungibleNumTransfers());
        assertEquals(0, subject.getNftsTransfers());
        assertEquals(1, subject.getNumTokens());
        assertEquals(1000, subject.getNetworkRecordRb());
        assertEquals(TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES, subject.getSubType());
        assertEquals(expected, subject.toString());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var meta1 = new TokenCreateMeta.Builder()
                .baseSize(1235)
                .lifeTime(1_234_567L)
                .customFeeScheleSize(200)
                .fungibleNumTransfers(1)
                .nftsTranfers(0)
                .numTokens(1)
                .networkRecordRb(1000)
                .subType(TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES)
                .build();
        final var meta2 = new TokenCreateMeta.Builder()
                .baseSize(1235)
                .lifeTime(1_234_567L)
                .customFeeScheleSize(200)
                .fungibleNumTransfers(1)
                .nftsTranfers(0)
                .numTokens(1)
                .networkRecordRb(1000)
                .subType(TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES)
                .build();

        assertEquals(meta1, meta2);
        assertEquals(meta1.hashCode(), meta1.hashCode());
    }
}
