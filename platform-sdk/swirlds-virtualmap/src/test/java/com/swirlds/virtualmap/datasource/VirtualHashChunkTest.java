// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.internal.Path;
import java.util.Random;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class VirtualHashChunkTest {

    private static final int HASH_LENGTH = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();

    @Test
    void createTest() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(0, 0, new byte[HASH_LENGTH]));
        assertDoesNotThrow(() -> new VirtualHashChunk(0, 1, new byte[HASH_LENGTH * 2]));
        assertDoesNotThrow(() -> new VirtualHashChunk(1, 1, new byte[HASH_LENGTH * 2]));
        assertDoesNotThrow(() -> new VirtualHashChunk(5, 1, new byte[HASH_LENGTH * 2]));
        for (int h = 2; h < 6; h++) {
            final int height = h;
            final int chunkSize = VirtualHashChunk.getChunkSize(height);
            final byte[] hashData = new byte[HASH_LENGTH * chunkSize];
            assertDoesNotThrow(() -> new VirtualHashChunk(0, height, hashData));
            assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(1, height, hashData));
            assertDoesNotThrow(() -> new VirtualHashChunk(Path.getLeftGrandChildPath(0, height), height, hashData));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new VirtualHashChunk(Path.getLeftGrandChildPath(0, height + 1), height, hashData));
        }
    }

    @Test
    void pathToChunkIdTest() {
        // Chunk height 1
        assertEquals(0, VirtualHashChunk.pathToChunkId(1, 1));
        assertEquals(0, VirtualHashChunk.pathToChunkId(2, 1));
        assertEquals(1, VirtualHashChunk.pathToChunkId(3, 1));
        assertEquals(1, VirtualHashChunk.pathToChunkId(4, 1));
        assertEquals(2, VirtualHashChunk.pathToChunkId(5, 1));
        assertEquals(2, VirtualHashChunk.pathToChunkId(6, 1));
        // Chunk height 2
        assertEquals(0, VirtualHashChunk.pathToChunkId(1, 2));
        assertEquals(0, VirtualHashChunk.pathToChunkId(2, 2));
        assertEquals(0, VirtualHashChunk.pathToChunkId(3, 2));
        assertEquals(0, VirtualHashChunk.pathToChunkId(6, 2));
        assertEquals(1, VirtualHashChunk.pathToChunkId(7, 2));
        assertEquals(1, VirtualHashChunk.pathToChunkId(8, 2));
        assertEquals(1, VirtualHashChunk.pathToChunkId(15, 2));
        assertEquals(1, VirtualHashChunk.pathToChunkId(16, 2));
        assertEquals(3, VirtualHashChunk.pathToChunkId(11, 2));
        assertEquals(3, VirtualHashChunk.pathToChunkId(12, 2));
        assertEquals(3, VirtualHashChunk.pathToChunkId(23, 2));
        assertEquals(3, VirtualHashChunk.pathToChunkId(26, 2));
    }

    @Test
    void chunkIdToChunkPathTest() {
        // Chunk height 1
        assertEquals(0, VirtualHashChunk.chunkIdToChunkPath(0, 1));
        assertEquals(1, VirtualHashChunk.chunkIdToChunkPath(1, 1));
        assertEquals(2, VirtualHashChunk.chunkIdToChunkPath(2, 1));
        assertEquals(5, VirtualHashChunk.chunkIdToChunkPath(5, 1));
        // Chunk height 2
        assertEquals(0, VirtualHashChunk.chunkIdToChunkPath(0, 2));
        assertEquals(3, VirtualHashChunk.chunkIdToChunkPath(1, 2));
        assertEquals(4, VirtualHashChunk.chunkIdToChunkPath(2, 2));
        assertEquals(5, VirtualHashChunk.chunkIdToChunkPath(3, 2));
        assertEquals(6, VirtualHashChunk.chunkIdToChunkPath(4, 2));
        assertEquals(15, VirtualHashChunk.chunkIdToChunkPath(5, 2));
        assertEquals(19, VirtualHashChunk.chunkIdToChunkPath(9, 2));
        // Chunk height 3
        assertEquals(0, VirtualHashChunk.chunkIdToChunkPath(0, 3));
        assertEquals(7, VirtualHashChunk.chunkIdToChunkPath(1, 3));
        assertEquals(63, VirtualHashChunk.chunkIdToChunkPath(9, 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void chunkIdToChunkPathTest2(final int chunkHeight) {
        for (int path = 1; path < 10000; path++) {
            final long chunkId = VirtualHashChunk.pathToChunkId(path, chunkHeight);
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, chunkHeight);
            final int pathIndex = VirtualHashChunk.getPathIndexInChunk(path, chunkPath, chunkHeight);
            assertEquals(path, VirtualHashChunk.getPathInChunk(chunkPath, pathIndex, chunkHeight));
        }
    }

    @Test
    void createDataLengthTest() {
        final int hashLen = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(0, 1, null));
        for (int h = 2; h < 6; h++) {
            final int height = h;
            assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(0, height, null));
            final int chunkSize = VirtualHashChunk.getChunkSize(height);
            final byte[] hashData = new byte[hashLen * chunkSize];
            final byte[] hashDataMinusOne = new byte[hashLen * chunkSize - 1];
            final byte[] hashDataPlusOne = new byte[hashLen * chunkSize + 1];
            assertDoesNotThrow(() -> new VirtualHashChunk(0, height, hashData));
            assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(0, height, hashDataMinusOne));
            assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(0, height, hashDataPlusOne));
        }
    }

    @Test
    void getChunkSizeTest() {
        assertEquals(2, VirtualHashChunk.getChunkSize(1));
        assertEquals(6, VirtualHashChunk.getChunkSize(2));
        assertEquals(14, VirtualHashChunk.getChunkSize(3));
        assertEquals(30, VirtualHashChunk.getChunkSize(4));
    }

    @Test
    void getPathIndexInChunkTest1() {
        // Chunk at path 0
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(1, 0, 1));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(2, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(3, 0, 1));
        // Chunk at path 1
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(2, 1, 1));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(3, 1, 1));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(4, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(5, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(7, 1, 1));
        // Chunk at path 6
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(2, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(3, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(12, 6, 1));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(13, 6, 1));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(14, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(15, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(27, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(30, 6, 1));
    }

    @Test
    void getPathIndexInChunkTest2() {
        // Chunk at path 0
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(1, 0, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(2, 0, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(3, 0, 2));
        assertEquals(5, VirtualHashChunk.getPathIndexInChunk(6, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(7, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(28, 0, 2));
        // Chunk at path 3
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(3, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(4, 3, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(7, 3, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(8, 3, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(15, 3, 2));
        assertEquals(5, VirtualHashChunk.getPathIndexInChunk(18, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(9, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(31, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(36, 3, 2));
        // Chunk at path 14
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(6, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(13, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(14, 14, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(29, 14, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(30, 14, 2));
        assertEquals(3, VirtualHashChunk.getPathIndexInChunk(60, 14, 2));
        assertEquals(4, VirtualHashChunk.getPathIndexInChunk(61, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(58, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(63, 14, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(125, 14, 2));
    }

    @Test
    void getPathIndexInChunkTest3() {
        // Chunk at path 1
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(1, 1, 3));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(3, 1, 3));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(4, 1, 3));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(7, 1, 3));
        assertEquals(5, VirtualHashChunk.getPathIndexInChunk(10, 1, 3));
        assertEquals(6, VirtualHashChunk.getPathIndexInChunk(15, 1, 3));
        assertEquals(13, VirtualHashChunk.getPathIndexInChunk(22, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(2, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(5, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(11, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(31, 1, 3));
    }

    @Test
    void getPathTest1() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(0, -1, 1));
        assertEquals(1, VirtualHashChunk.getPathInChunk(0, 0, 1));
        assertEquals(2, VirtualHashChunk.getPathInChunk(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(0, 2, 1));
        // Chunk at path 3
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(3, -1, 1));
        assertEquals(7, VirtualHashChunk.getPathInChunk(3, 0, 1));
        assertEquals(8, VirtualHashChunk.getPathInChunk(3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(3, 2, 1));
        // Chunk at path 14
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(14, -1, 1));
        assertEquals(29, VirtualHashChunk.getPathInChunk(14, 0, 1));
        assertEquals(30, VirtualHashChunk.getPathInChunk(14, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(14, 2, 1));
    }

    @Test
    void getPathTest2() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(0, -1, 2));
        assertEquals(1, VirtualHashChunk.getPathInChunk(0, 0, 2));
        assertEquals(2, VirtualHashChunk.getPathInChunk(0, 1, 2));
        assertEquals(3, VirtualHashChunk.getPathInChunk(0, 2, 2));
        assertEquals(6, VirtualHashChunk.getPathInChunk(0, 5, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(0, 6, 2));
        // Chunk at path 4
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(4, -1, 2));
        assertEquals(9, VirtualHashChunk.getPathInChunk(4, 0, 2));
        assertEquals(10, VirtualHashChunk.getPathInChunk(4, 1, 2));
        assertEquals(19, VirtualHashChunk.getPathInChunk(4, 2, 2));
        assertEquals(22, VirtualHashChunk.getPathInChunk(4, 5, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(4, 7, 2));
        // Chunk at path 16
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(16, -1, 2));
        assertEquals(33, VirtualHashChunk.getPathInChunk(16, 0, 2));
        assertEquals(34, VirtualHashChunk.getPathInChunk(16, 1, 2));
        assertEquals(68, VirtualHashChunk.getPathInChunk(16, 3, 2));
        assertEquals(69, VirtualHashChunk.getPathInChunk(16, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(16, 8, 2));
    }

    @Test
    void getPathTest3() {
        // Chunk at path 2
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, -1, 3));
        assertEquals(5, VirtualHashChunk.getPathInChunk(2, 0, 3));
        assertEquals(6, VirtualHashChunk.getPathInChunk(2, 1, 3));
        assertEquals(11, VirtualHashChunk.getPathInChunk(2, 2, 3));
        assertEquals(13, VirtualHashChunk.getPathInChunk(2, 4, 3));
        assertEquals(28, VirtualHashChunk.getPathInChunk(2, 11, 3));
        assertEquals(29, VirtualHashChunk.getPathInChunk(2, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, 14, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, 15, 3));
    }

    private static Hash genRandomHash() {
        final Random random = new Random();
        final byte[] hashData = new byte[HASH_LENGTH];
        random.nextBytes(hashData);
        return new Hash(hashData);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void setHashByPathTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath = Path.getLeftGrandChildPath(0, random.nextInt(8) * height);
        final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, height, new byte[chunkSize * HASH_LENGTH]);
        for (int i = 0; i < chunkSize; i++) {
            final Hash hash = genRandomHash();
            assertNotEquals(hash, chunk.getHashAtIndex(i));
            final long path = chunk.getPath(i);
            assertNotEquals(hash, chunk.getHashAtPath(path));
            chunk.setHashAtPath(path, hash);
            assertEquals(hash, chunk.getHashAtIndex(i));
            assertEquals(hash, chunk.getHashAtPath(path));
        }
        assertThrows(IllegalArgumentException.class, () -> chunk.setHashAtPath(chunk.getPath(chunkSize), new Hash()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void setHashByIndexTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath = Path.getLeftGrandChildPath(0, random.nextInt(8) * height);
        final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, height, new byte[chunkSize * HASH_LENGTH]);
        for (int i = 0; i < chunkSize; i++) {
            final Hash hash = genRandomHash();
            assertNotEquals(hash, chunk.getHashAtIndex(i));
            final long path = chunk.getPath(i);
            assertNotEquals(hash, chunk.getHashAtPath(path));
            chunk.setHashAtIndex(i, hash);
            assertEquals(hash, chunk.getHashAtIndex(i));
            assertEquals(hash, chunk.getHashAtPath(path));
        }
        assertThrows(IllegalArgumentException.class, () -> chunk.setHashAtIndex(chunkSize, new Hash()));
    }
}
