// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import java.nio.charset.StandardCharsets;
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
            // Check chunk path / chunk height
            assertDoesNotThrow(() -> new VirtualHashChunk(0, height, hashData));
            assertThrows(IllegalArgumentException.class, () -> new VirtualHashChunk(1, height, hashData));
            assertDoesNotThrow(() -> new VirtualHashChunk(Path.getLeftGrandChildPath(0, height), height, hashData));
            assertDoesNotThrow(() -> new VirtualHashChunk(Path.getRightGrandChildPath(0, height), height, hashData));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new VirtualHashChunk(Path.getLeftGrandChildPath(0, height + 1), height, hashData));
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
        // Chunk height 3
        assertEquals(0, VirtualHashChunk.pathToChunkId(1, 3));
        assertEquals(0, VirtualHashChunk.pathToChunkId(1, 3));
        assertEquals(0, VirtualHashChunk.pathToChunkId(2, 3));
        assertEquals(0, VirtualHashChunk.pathToChunkId(7, 3));
        assertEquals(0, VirtualHashChunk.pathToChunkId(14, 3));
        assertEquals(1, VirtualHashChunk.pathToChunkId(15, 3));
        assertEquals(1, VirtualHashChunk.pathToChunkId(16, 3));
        assertEquals(1, VirtualHashChunk.pathToChunkId(63, 3));
        assertEquals(1, VirtualHashChunk.pathToChunkId(70, 3));
        assertEquals(2, VirtualHashChunk.pathToChunkId(71, 3));
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
        for (long path = 1; path < 10000; path++) {
            final long chunkId = VirtualHashChunk.pathToChunkId(path, chunkHeight);
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, chunkHeight);
            final int rank = Path.getRank(path);
            if (rank % chunkHeight == 0) {
                final int pathIndex = VirtualHashChunk.getPathIndexInChunk(path, chunkPath, chunkHeight);
                assertEquals(path, VirtualHashChunk.getPathInChunk(pathIndex, chunkPath, chunkHeight));
            } else {
                assertEquals(chunkPath, Path.getGrandParentPath(path, rank % chunkHeight));
            }
        }
    }

    @Test
    void getChunkSizeTest() {
        assertEquals(2, VirtualHashChunk.getChunkSize(1));
        assertEquals(4, VirtualHashChunk.getChunkSize(2));
        assertEquals(8, VirtualHashChunk.getChunkSize(3));
        assertEquals(16, VirtualHashChunk.getChunkSize(4));
    }

    @Test
    // Chunk height 1
    void getPathIndexInChunkTest1() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 0, 1));
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
    // Chunk height 2
    void getPathIndexInChunkTest2() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 0, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(1, 0, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(2, 0, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(3, 0, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(4, 0, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(5, 0, 2));
        assertEquals(3, VirtualHashChunk.getPathIndexInChunk(6, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(7, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(14, 0, 2));
        // Chunk at path 3
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(2, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(3, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(4, 3, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(7, 3, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(8, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(9, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(14, 3, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(15, 3, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(16, 3, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(17, 3, 2));
        assertEquals(3, VirtualHashChunk.getPathIndexInChunk(18, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(19, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(30, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(31, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(35, 3, 2));
        // Chunk at path 17
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(16, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(17, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(18, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(34, 17, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(35, 17, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(36, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(37, 17, 2));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(71, 17, 2));
        assertEquals(1, VirtualHashChunk.getPathIndexInChunk(72, 17, 2));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(73, 17, 2));
        assertEquals(3, VirtualHashChunk.getPathIndexInChunk(74, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(75, 17, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(143, 17, 2));
    }

    @Test
    // Chunk height 3
    void getPathIndexInChunkTest3() {
        // Chunk at path 12
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(0, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(11, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(12, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(13, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(24, 12, 3));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(25, 12, 3));
        assertEquals(4, VirtualHashChunk.getPathIndexInChunk(26, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(27, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(50, 12, 3));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(51, 12, 3));
        assertEquals(2, VirtualHashChunk.getPathIndexInChunk(52, 12, 3));
        assertEquals(6, VirtualHashChunk.getPathIndexInChunk(54, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(55, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(102, 12, 3));
        assertEquals(0, VirtualHashChunk.getPathIndexInChunk(103, 12, 3));
        assertEquals(7, VirtualHashChunk.getPathIndexInChunk(110, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(111, 12, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathIndexInChunk(207, 12, 3));
    }

    @Test
    // Chunk height == 1
    void getPathInChunkTest1() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 0, 1));
        assertEquals(1, VirtualHashChunk.getPathInChunk(0, 0, 1));
        assertEquals(2, VirtualHashChunk.getPathInChunk(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, 0, 1));
        // Chunk at path 3
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 3, 1));
        assertEquals(7, VirtualHashChunk.getPathInChunk(0, 3, 1));
        assertEquals(8, VirtualHashChunk.getPathInChunk(1, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, 3, 1));
        // Chunk at path 14
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 14, 1));
        assertEquals(29, VirtualHashChunk.getPathInChunk(0, 14, 1));
        assertEquals(30, VirtualHashChunk.getPathInChunk(1, 14, 1));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(2, 14, 1));
    }

    @Test
    // Chunk height 2
    void getPathInChunkTest2() {
        // Chunk at path 0
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 0, 2));
        assertEquals(3, VirtualHashChunk.getPathInChunk(0, 0, 2));
        assertEquals(4, VirtualHashChunk.getPathInChunk(1, 0, 2));
        assertEquals(5, VirtualHashChunk.getPathInChunk(2, 0, 2));
        assertEquals(6, VirtualHashChunk.getPathInChunk(3, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(4, 0, 2));
        // Chunk at path 4
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 4, 2));
        assertEquals(19, VirtualHashChunk.getPathInChunk(0, 4, 2));
        assertEquals(20, VirtualHashChunk.getPathInChunk(1, 4, 2));
        assertEquals(21, VirtualHashChunk.getPathInChunk(2, 4, 2));
        assertEquals(22, VirtualHashChunk.getPathInChunk(3, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(5, 4, 2));
        // Chunk at path 16
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 16, 2));
        assertEquals(67, VirtualHashChunk.getPathInChunk(0, 16, 2));
        assertEquals(68, VirtualHashChunk.getPathInChunk(1, 16, 2));
        assertEquals(69, VirtualHashChunk.getPathInChunk(2, 16, 2));
        assertEquals(70, VirtualHashChunk.getPathInChunk(3, 16, 2));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(8, 16, 2));
    }

    @Test
    void getPathInChunkTest3() {
        // Chunk at path 2
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(-1, 2, 3));
        assertEquals(23, VirtualHashChunk.getPathInChunk(0, 2, 3));
        assertEquals(24, VirtualHashChunk.getPathInChunk(1, 2, 3));
        assertEquals(25, VirtualHashChunk.getPathInChunk(2, 2, 3));
        assertEquals(26, VirtualHashChunk.getPathInChunk(3, 2, 3));
        assertEquals(28, VirtualHashChunk.getPathInChunk(5, 2, 3));
        assertEquals(30, VirtualHashChunk.getPathInChunk(7, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(8, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.getPathInChunk(15, 2, 3));
    }

    @Test
    void minChunkIdForPathsTest2() {
        // Chunk height 2
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(1, 2));
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(3, 2));
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(4, 2));
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(6, 2));
        assertEquals(1, VirtualHashChunk.minChunkIdForPaths(7, 2));
        assertEquals(2, VirtualHashChunk.minChunkIdForPaths(9, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(14, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(15, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(18, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(22, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(29, 2));
        assertEquals(4, VirtualHashChunk.minChunkIdForPaths(30, 2));
        assertEquals(5, VirtualHashChunk.minChunkIdForPaths(31, 2));
        assertEquals(20, VirtualHashChunk.minChunkIdForPaths(63, 2));
    }

    @Test
    void minChunkIdForPathsTest3() {
        // Chunk height 3
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(1, 3));
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(4, 3));
        assertEquals(0, VirtualHashChunk.minChunkIdForPaths(11, 3));
        assertEquals(1, VirtualHashChunk.minChunkIdForPaths(15, 3));
        assertEquals(2, VirtualHashChunk.minChunkIdForPaths(17, 3));
        assertEquals(8, VirtualHashChunk.minChunkIdForPaths(29, 3));
        assertEquals(8, VirtualHashChunk.minChunkIdForPaths(32, 3));
        assertEquals(8, VirtualHashChunk.minChunkIdForPaths(66, 3));
        assertEquals(8, VirtualHashChunk.minChunkIdForPaths(100, 3));
        assertEquals(9, VirtualHashChunk.minChunkIdForPaths(127, 3));
        assertEquals(10, VirtualHashChunk.minChunkIdForPaths(129, 3));
        assertEquals(72, VirtualHashChunk.minChunkIdForPaths(255, 3));
        assertEquals(72, VirtualHashChunk.minChunkIdForPaths(256, 3));
        assertEquals(72, VirtualHashChunk.minChunkIdForPaths(512, 3));
        assertEquals(73, VirtualHashChunk.minChunkIdForPaths(1023, 3));
    }

    private static Hash genRandomHash() {
        final Random random = new Random();
        final byte[] hashData = new byte[HASH_LENGTH];
        random.nextBytes(hashData);
        return new Hash(hashData);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void setHashTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath = Path.getLeftGrandChildPath(0, random.nextInt(8) * height);
        final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, height);
        for (int i = 0; i < chunkSize; i++) {
            final Hash hash = genRandomHash();
            assertNotEquals(hash, chunk.getHashAtIndex(i));
            final long path = chunk.getPath(i);
            assertNotEquals(hash, chunk.getHashAtPath(path));
            chunk.setHashAtPath(path, hash);
            assertEquals(hash, chunk.getHashAtIndex(i));
            assertEquals(hash, chunk.getHashAtPath(path));
            final Hash hash2 = genRandomHash();
            chunk.setHashAtIndex(i, hash2);
            assertEquals(hash2, chunk.getHashAtIndex(i));
            assertEquals(hash2, chunk.getHashAtPath(path));
        }
        assertThrows(IllegalArgumentException.class, () -> chunk.setHashAtPath(chunk.getPath(chunkSize), new Hash()));
    }

    @Test
    void calcHashTest36() {
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 2);
        final Hash hash3 =
                new Hash("345678901234567890123456789012345678901234567890".getBytes(StandardCharsets.UTF_8));
        final Hash hash4 =
                new Hash("456789012345678901234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        final Hash hash5 =
                new Hash("567890123456789012345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
        final Hash hash6 =
                new Hash("678901234567890123456789012345678901234567890123".getBytes(StandardCharsets.UTF_8));
        chunk.setHashAtPath(3, hash3);
        chunk.setHashAtPath(4, hash4);
        chunk.setHashAtPath(5, hash5);
        chunk.setHashAtPath(6, hash6);
        assertEquals(hash3, chunk.calcHash(3, 3, 6));
        assertEquals(hash4, chunk.calcHash(4, 3, 6));
        assertEquals(hash5, chunk.calcHash(5, 3, 6));
        assertEquals(hash6, chunk.calcHash(6, 3, 6));
        final Hash hash1 = VirtualHasher.hashInternal(hash3, hash4);
        final Hash hash2 = VirtualHasher.hashInternal(hash5, hash6);
        assertEquals(hash1, chunk.calcHash(1, 3, 6));
        assertEquals(hash2, chunk.calcHash(2, 3, 6));
        final Hash rootHash = VirtualHasher.hashInternal(hash1, hash2);
        assertEquals(rootHash, chunk.calcHash(0, 3, 6));
        assertEquals(rootHash, chunk.chunkRootHash(3, 6));
        assertEquals(rootHash, chunk.chunkRootHash(10, 20));
    }

    @Test
    void calcHashTest24() {
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 2);
        final Hash hash2 =
                new Hash("234567890123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        final Hash hash3 =
                new Hash("345678901234567890123456789012345678901234567890".getBytes(StandardCharsets.UTF_8));
        final Hash hash4 =
                new Hash("456789012345678901234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        chunk.setHashAtPath(2, hash2);
        chunk.setHashAtPath(3, hash3);
        chunk.setHashAtPath(4, hash4);
        assertEquals(hash2, chunk.calcHash(2, 2, 4));
        assertEquals(hash3, chunk.calcHash(3, 2, 4));
        assertEquals(hash4, chunk.calcHash(4, 2, 4));
        final Hash hash1 = VirtualHasher.hashInternal(hash3, hash4);
        assertEquals(hash1, chunk.calcHash(1, 2, 4));
        assertEquals(hash2, chunk.calcHash(2, 2, 4));
        final Hash rootHash = VirtualHasher.hashInternal(hash1, hash2);
        assertEquals(rootHash, chunk.calcHash(0, 2, 4));
        assertEquals(rootHash, chunk.chunkRootHash(2, 4));
    }

    @Test
    void calcHashTest11() {
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 1);
        final Hash hash1 =
                new Hash("123456789012345678901234567890123456789012345678".getBytes(StandardCharsets.UTF_8));
        chunk.setHashAtPath(1, hash1);
        assertEquals(hash1, chunk.calcHash(1, 1, 1));
        final Hash hash2 = new Hash();
        final Hash rootHash = VirtualHasher.hashInternal(hash1, hash2);
        assertEquals(rootHash, chunk.calcHash(0, 1, 1));
        assertEquals(rootHash, chunk.chunkRootHash(1, 1));
    }
}
