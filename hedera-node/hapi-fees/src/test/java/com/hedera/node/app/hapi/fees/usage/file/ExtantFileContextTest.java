// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import org.junit.jupiter.api.Test;

class ExtantFileContextTest {
    private final String memo = "Currently unavailable";
    private final long expiry = 1_234_567L;
    private final KeyList wacl = KeyUtils.A_KEY_LIST.keyList();
    private final long size = 54_321L;

    @Test
    void buildsAsExpected() {
        // given:
        final var subject = ExtantFileContext.newBuilder()
                .setCurrentExpiry(expiry)
                .setCurrentMemo(memo)
                .setCurrentWacl(wacl)
                .setCurrentSize(size)
                .build();

        // expect:
        assertEquals(memo, subject.currentMemo());
        assertEquals(expiry, subject.currentExpiry());
        assertEquals(wacl, subject.currentWacl());
        assertEquals(size, subject.currentSize());
    }

    @Test
    void rejectsIncompleteContext() {
        // given:
        final var builder = ExtantFileContext.newBuilder()
                .setCurrentExpiry(expiry)
                .setCurrentMemo(memo)
                .setCurrentWacl(wacl);

        // expect:
        assertThrows(IllegalStateException.class, builder::build);
    }
}
