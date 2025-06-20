// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenUnpauseMetaTest {

    @Test
    void getterAndToStringWork() {
        final var expected = "TokenUnpauseMeta{bpt=56}";

        final var subject = new TokenUnpauseMeta(56);
        assertEquals(56, subject.getBpt());
        assertEquals(expected, subject.toString());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var meta1 = new TokenUnpauseMeta(32);
        final var meta2 = new TokenUnpauseMeta(32);

        assertEquals(meta1, meta2);
        assertEquals(meta1.hashCode(), meta1.hashCode());
    }
}
