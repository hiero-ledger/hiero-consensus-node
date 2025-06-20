// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenWipeMetaTest {
    @Test
    void allGettersAndToStringWork() {
        final var expected = "TokenWipeMeta{bpt=1000, transferRecordDb=12345, "
                + "subType=TOKEN_NON_FUNGIBLE_UNIQUE, serialNumsCount=2}";

        final var subject = new TokenWipeMeta(1000, TOKEN_NON_FUNGIBLE_UNIQUE, 12345L, 2);
        assertEquals(1000, subject.getBpt());
        assertEquals(12_345L, subject.getTransferRecordDb());
        assertEquals(TOKEN_NON_FUNGIBLE_UNIQUE, subject.getSubType());
        assertEquals(2, subject.getSerialNumsCount());
        assertEquals(expected, subject.toString());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var meta1 = new TokenWipeMeta(1000, TOKEN_NON_FUNGIBLE_UNIQUE, 12345L, 1);
        final var meta2 = new TokenWipeMeta(1000, TOKEN_NON_FUNGIBLE_UNIQUE, 12345L, 1);

        assertEquals(meta1, meta2);
        assertEquals(meta1.hashCode(), meta1.hashCode());
    }
}
