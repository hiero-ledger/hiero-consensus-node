// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class VirtualHashRecordTest {
    private static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the path Constructor works")
    void createInternalRecordUsingPathConstructor() {
        final VirtualHashRecord rec = new VirtualHashRecord(101);
        assertNull(rec.hash(), "hash should be null");
        assertEquals(101, rec.path(), "path should match expected");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Using the full constructor works")
    void createInternalRecordUsingPathHashConstructor() {
        final Hash hash = CRYPTO.digestSync("Fake Hash".getBytes(StandardCharsets.UTF_8));
        final VirtualHashRecord rec = new VirtualHashRecord(102, hash);
        assertEquals(hash, rec.hash(), "hash should match");
        assertEquals(102, rec.path(), "path should match expected");
    }

    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("toString with a null hash is OK")
    void toStringWithNullHashDoesNotThrow() {
        final VirtualHashRecord rec = new VirtualHashRecord(103);
        final String str = rec.toString();
        assertNotNull(str, "value should not be null");
    }
}
