// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class BlockImplUtilsTest {
    @Test
    void testCombineNormalCase() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash = BlockImplUtils.combine(leftHash, rightHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineEmptyHashes() throws NoSuchAlgorithmException {
        byte[] emptyHash = MessageDigest.getInstance("SHA-384").digest(new byte[0]);
        byte[] combinedHash = BlockImplUtils.combine(emptyHash, emptyHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineDifferentHashes() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash1 = BlockImplUtils.combine(leftHash, rightHash);
        byte[] combinedHash2 = BlockImplUtils.combine(rightHash, leftHash);

        assertNotNull(combinedHash1);
        assertNotNull(combinedHash2);
        assertNotEquals(new String(combinedHash1), new String(combinedHash2));
    }

    @Test
    void testCombineWithNull() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(new byte[0], null));
    }
}
