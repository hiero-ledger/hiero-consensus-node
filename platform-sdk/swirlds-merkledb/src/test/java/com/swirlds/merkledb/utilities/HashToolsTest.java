// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.utilities;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import java.nio.ByteBuffer;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Test;

class HashToolsTest {

    @Test
    void constructsExpectedBuffer() {
        final Hash hash = MerkleDbTestUtils.hash(123);

        final ByteBuffer buffer = HashTools.hashToByteBuffer(hash);

        assertArrayEquals(hash.copyToByteArray(), buffer.array(), "Hash digest should match created buffer");
    }

    @Test
    void putsDigestAsExpected() {
        final Hash hash = MerkleDbTestUtils.hash(123);
        final ByteBuffer buffer = ByteBuffer.allocate(48);

        HashTools.hashToByteBuffer(hash, buffer);

        assertArrayEquals(hash.copyToByteArray(), buffer.array(), "Hash digest should matched filled buffer");
    }

    @Test
    void canOnlyTranslateByteBuffersWithCurrentSerializationVersion() {
        final ByteBuffer buffer = ByteBuffer.allocate(48);
        assertThrows(
                IllegalArgumentException.class,
                () -> HashTools.byteBufferToHash(buffer, 2),
                "Should reject non-current serialization version");
    }
}
