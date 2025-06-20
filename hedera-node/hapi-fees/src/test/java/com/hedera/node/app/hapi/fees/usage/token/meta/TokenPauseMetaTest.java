// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenPauseMetaTest {

    @Test
    void getterAndToStringWork() {
        final var expected = "TokenPauseMeta{bpt=56}";

        final var subject = new TokenPauseMeta(56);
        assertEquals(56, subject.getBpt());
        assertEquals(expected, subject.toString());
    }

    @Test
    void hashCodeAndEqualsWork() {
        final var meta1 = new TokenPauseMeta(32);
        final var meta2 = new TokenPauseMeta(32);

        assertEquals(meta1, meta2);
        assertEquals(meta1.hashCode(), meta1.hashCode());
    }
}
