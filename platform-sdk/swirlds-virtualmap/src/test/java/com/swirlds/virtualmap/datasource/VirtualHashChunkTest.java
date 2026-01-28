// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
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
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(1, 2));
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(3, 2));
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(4, 2));
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(6, 2));
        assertEquals(1, VirtualHashChunk.lastChunkIdForPaths(7, 2));
        assertEquals(2, VirtualHashChunk.lastChunkIdForPaths(9, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(14, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(15, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(18, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(22, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(29, 2));
        assertEquals(4, VirtualHashChunk.lastChunkIdForPaths(30, 2));
        assertEquals(5, VirtualHashChunk.lastChunkIdForPaths(31, 2));
        assertEquals(20, VirtualHashChunk.lastChunkIdForPaths(63, 2));
    }

    @Test
    void minChunkIdForPathsTest3() {
        // Chunk height 3
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(1, 3));
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(4, 3));
        assertEquals(0, VirtualHashChunk.lastChunkIdForPaths(11, 3));
        assertEquals(1, VirtualHashChunk.lastChunkIdForPaths(15, 3));
        assertEquals(2, VirtualHashChunk.lastChunkIdForPaths(17, 3));
        assertEquals(8, VirtualHashChunk.lastChunkIdForPaths(29, 3));
        assertEquals(8, VirtualHashChunk.lastChunkIdForPaths(32, 3));
        assertEquals(8, VirtualHashChunk.lastChunkIdForPaths(66, 3));
        assertEquals(8, VirtualHashChunk.lastChunkIdForPaths(100, 3));
        assertEquals(9, VirtualHashChunk.lastChunkIdForPaths(127, 3));
        assertEquals(10, VirtualHashChunk.lastChunkIdForPaths(129, 3));
        assertEquals(72, VirtualHashChunk.lastChunkIdForPaths(255, 3));
        assertEquals(72, VirtualHashChunk.lastChunkIdForPaths(256, 3));
        assertEquals(72, VirtualHashChunk.lastChunkIdForPaths(512, 3));
        assertEquals(73, VirtualHashChunk.lastChunkIdForPaths(1023, 3));
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

    // Copy tests

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void copyTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath = Path.getLeftGrandChildPath(0, random.nextInt(8) * height);
        final VirtualHashChunk original = new VirtualHashChunk(chunkPath, height);

        // Populate original chunk with random hashes
        final Hash[] hashes = new Hash[chunkSize];
        for (int i = 0; i < chunkSize; i++) {
            hashes[i] = genRandomHash();
            original.setHashAtIndex(i, hashes[i]);
        }

        // Create a copy
        final VirtualHashChunk copy = original.copy();

        // Verify copy has the same path and height
        assertEquals(original.path(), copy.path());
        assertEquals(original.height(), copy.height());

        // Verify copy has the same hash data
        assertEquals(original.hashData().length, copy.hashData().length);
        for (int i = 0; i < chunkSize; i++) {
            assertEquals(original.getHashAtIndex(i), copy.getHashAtIndex(i));
        }

        // Verify it's a deep copy - hash data arrays are different objects
        assertNotSame(original.hashData(), copy.hashData());
    }

    @Test
    void copyIndependenceTest() {
        // Create original chunk with height 2 (4 hashes)
        final VirtualHashChunk original = new VirtualHashChunk(0, 2);

        final Hash hash1 =
                new Hash("111111111111111111111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
        final Hash hash2 =
                new Hash("222222222222222222222222222222222222222222222222".getBytes(StandardCharsets.UTF_8));
        final Hash hash3 =
                new Hash("333333333333333333333333333333333333333333333333".getBytes(StandardCharsets.UTF_8));
        final Hash hash4 =
                new Hash("444444444444444444444444444444444444444444444444".getBytes(StandardCharsets.UTF_8));

        original.setHashAtPath(3, hash1);
        original.setHashAtPath(4, hash2);
        original.setHashAtPath(5, hash3);
        original.setHashAtPath(6, hash4);

        // Create a copy
        final VirtualHashChunk copy = original.copy();

        // Verify initial state matches
        assertEquals(hash1, copy.getHashAtPath(3));
        assertEquals(hash2, copy.getHashAtPath(4));
        assertEquals(hash3, copy.getHashAtPath(5));
        assertEquals(hash4, copy.getHashAtPath(6));

        // Modify the original
        final Hash newHash1 =
                new Hash("AAA111111111111111111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
        final Hash newHash2 =
                new Hash("BBB222222222222222222222222222222222222222222222".getBytes(StandardCharsets.UTF_8));
        original.setHashAtPath(3, newHash1);
        original.setHashAtPath(4, newHash2);

        // Verify copy is unchanged
        assertEquals(hash1, copy.getHashAtPath(3));
        assertEquals(hash2, copy.getHashAtPath(4));
        assertEquals(hash3, copy.getHashAtPath(5));
        assertEquals(hash4, copy.getHashAtPath(6));

        // Verify original was modified
        assertEquals(newHash1, original.getHashAtPath(3));
        assertEquals(newHash2, original.getHashAtPath(4));
    }

    @Test
    void copyThenModifyCopyTest() {
        // Create original chunk with height 1 (2 hashes)
        final VirtualHashChunk original = new VirtualHashChunk(0, 1);

        final Hash originalHash1 =
                new Hash("ORIG11111111111111111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
        final Hash originalHash2 =
                new Hash("ORIG22222222222222222222222222222222222222222222".getBytes(StandardCharsets.UTF_8));

        original.setHashAtIndex(0, originalHash1);
        original.setHashAtIndex(1, originalHash2);

        // Create a copy
        final VirtualHashChunk copy = original.copy();

        // Modify the copy
        final Hash copyHash1 =
                new Hash("COPY11111111111111111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
        final Hash copyHash2 =
                new Hash("COPY22222222222222222222222222222222222222222222".getBytes(StandardCharsets.UTF_8));
        copy.setHashAtIndex(0, copyHash1);
        copy.setHashAtIndex(1, copyHash2);

        // Verify original is unchanged
        assertEquals(originalHash1, original.getHashAtIndex(0));
        assertEquals(originalHash2, original.getHashAtIndex(1));

        // Verify copy was modified
        assertEquals(copyHash1, copy.getHashAtIndex(0));
        assertEquals(copyHash2, copy.getHashAtIndex(1));
    }

    @Test
    void copyPreservesChunkPathTest() {
        // Test copying chunks at various paths
        final int height = 2;
        final long[] testPaths = {0, 3, 4, 15, 67};

        for (long path : testPaths) {
            final VirtualHashChunk original = new VirtualHashChunk(path, height);
            final Hash testHash = genRandomHash();
            final long firstLeafPath = Path.getLeftGrandChildPath(path, height);
            original.setHashAtPath(firstLeafPath, testHash);

            final VirtualHashChunk copy = original.copy();

            assertEquals(path, copy.path());
            assertEquals(height, copy.height());
            assertEquals(testHash, copy.getHashAtPath(firstLeafPath));

            // Verify independence
            assertNotSame(original.hashData(), copy.hashData());
        }
    }

    @Test
    void copyChunkIdPreservedTest() {
        // Verify that the copy has the same chunk ID as the original
        final int height = 3;
        final long chunkPath = 7;
        final VirtualHashChunk original = new VirtualHashChunk(chunkPath, height);
        final VirtualHashChunk copy = original.copy();

        assertEquals(original.getChunkId(), copy.getChunkId());
    }

    // Serialization tests

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void serializationRoundTripTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath = Path.getLeftGrandChildPath(0, random.nextInt(8) * height);
        final VirtualHashChunk original = new VirtualHashChunk(chunkPath, height);

        // Populate with random hashes
        for (int i = 0; i < chunkSize; i++) {
            original.setHashAtIndex(i, genRandomHash());
        }

        // Serialize
        final int sizeInBytes = original.getSizeInBytes();
        final byte[] buffer = new byte[sizeInBytes];
        final BufferedData out = BufferedData.wrap(buffer);
        original.writeTo(out);

        // Verify size calculation was correct
        assertEquals(sizeInBytes, out.position());

        // Deserialize
        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());
        final VirtualHashChunk deserialized = VirtualHashChunk.parseFrom(in);

        // Verify all fields match
        assertNotNull(deserialized);
        assertEquals(original.path(), deserialized.path());
        assertEquals(original.height(), deserialized.height());
        assertEquals(original.hashData().length, deserialized.hashData().length);

        // Verify all hashes match
        for (int i = 0; i < chunkSize; i++) {
            assertEquals(original.getHashAtIndex(i), deserialized.getHashAtIndex(i));
        }
    }

    @Test
    void serializationEmptyChunkTest() {
        // Test serialization of a chunk with no hashes set (all zeros)
        final VirtualHashChunk original = new VirtualHashChunk(0, 2);

        // Serialize
        final int sizeInBytes = original.getSizeInBytes();
        final byte[] buffer = new byte[sizeInBytes];
        final BufferedData out = BufferedData.wrap(buffer);
        original.writeTo(out);

        // Deserialize
        final ReadableSequentialData in = BufferedData.wrap(buffer);
        final VirtualHashChunk deserialized = VirtualHashChunk.parseFrom(in);

        // Verify
        assertNotNull(deserialized);
        assertEquals(0, deserialized.path());
        assertEquals(2, deserialized.height());
        assertEquals(original.getChunkSize(), deserialized.getChunkSize());
    }

    @Test
    void parseFromNullInputTest() {
        // Test that parseFrom returns null for null input
        final VirtualHashChunk result = VirtualHashChunk.parseFrom(null);
        assertNull(result);
    }

    @Test
    void parseFromEmptyInputTest() {
        // Test parsing from empty input (no data)
        final byte[] emptyBuffer = new byte[0];
        final ReadableSequentialData in = BufferedData.wrap(emptyBuffer);

        // This should throw IllegalArgumentException because required fields are missing
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void parseFromWrongFieldTypePathTest() {
        // Test parsing with wrong wire type for path field
        final byte[] buffer = new byte[100];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        // Write path field with wrong wire type (VARINT instead of FIXED64)
        final int wrongTag = (VirtualHashChunk.FIELD_HASHCHUNK_PATH.number() << ProtoParserTools.TAG_FIELD_OFFSET)
                | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
        out.writeVarInt(wrongTag, false);
        out.writeVarInt(0, false);

        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());

        // Should throw IllegalArgumentException for wrong field type
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void parseFromWrongFieldTypeHeightTest() {
        // Test parsing with wrong wire type for height field
        final byte[] buffer = new byte[100];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        // Write path field correctly
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_PATH);
        out.writeLong(0);

        // Write height field with wrong wire type (VARINT instead of FIXED32)
        final int wrongTag = (VirtualHashChunk.FIELD_HASHCHUNK_HEIGHT.number() << ProtoParserTools.TAG_FIELD_OFFSET)
                | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
        out.writeVarInt(wrongTag, false);
        out.writeVarInt(2, false);

        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());

        // Should throw IllegalArgumentException for wrong field type
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void parseFromWrongFieldTypeHashDataTest() {
        // Test parsing with wrong wire type for hashData field
        final byte[] buffer = new byte[200];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        // Write path field correctly
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_PATH);
        out.writeLong(0);

        // Write height field correctly
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_HEIGHT);
        out.writeInt(2);

        // Write hashData field with wrong wire type (FIXED64 instead of DELIMITED)
        final int wrongTag = (VirtualHashChunk.FIELD_HASHCHUNK_HASHDATA.number() << ProtoParserTools.TAG_FIELD_OFFSET)
                | ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal();
        out.writeVarInt(wrongTag, false);

        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());

        // Should throw IllegalArgumentException for wrong field type
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void parseFromUnknownFieldTest() {
        // Test parsing with an unknown field tag
        final byte[] buffer = new byte[200];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        // Write an unknown field (field number 99)
        final int unknownTag =
                (99 << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
        out.writeVarInt(unknownTag, false);
        out.writeVarInt(12345, false);

        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());

        // Should throw IllegalArgumentException for unknown field
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void parseFromInsufficientBytesTest() {
        // Test parsing when hashData length exceeds available bytes
        final byte[] buffer = new byte[200];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        // Write path field correctly
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_PATH);
        out.writeLong(0);

        // Write height field correctly
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_HEIGHT);
        out.writeInt(2);

        // Write hashData field tag and claim large length, but don't provide data
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_HASHDATA);
        out.writeVarInt(1000, false); // Claim 1000 bytes but buffer doesn't have them

        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());

        // Should throw IllegalArgumentException for failed byte read
        assertThrows(IllegalArgumentException.class, () -> VirtualHashChunk.parseFrom(in));
    }

    @Test
    void serializationFieldOrderTest() {
        // Test that fields can be parsed in any order (protobuf requirement)
        final byte[] buffer = new byte[500];
        final WritableSequentialData out = BufferedData.wrap(buffer);

        final int height = 2;
        final long path = 15;
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final byte[] hashData = new byte[chunkSize * HASH_LENGTH];
        new Random().nextBytes(hashData);

        // Write fields in reverse order: hashData, height, path
        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_HASHDATA);
        out.writeVarInt(hashData.length, false);
        out.writeBytes(hashData);

        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_HEIGHT);
        out.writeInt(height);

        ProtoWriterTools.writeTag(out, VirtualHashChunk.FIELD_HASHCHUNK_PATH);
        out.writeLong(path);

        // Parse
        final ReadableSequentialData in = BufferedData.wrap(buffer, 0, (int) out.position());
        final VirtualHashChunk chunk = VirtualHashChunk.parseFrom(in);

        // Verify all fields were parsed correctly despite different order
        assertNotNull(chunk);
        assertEquals(path, chunk.path());
        assertEquals(height, chunk.height());
        assertArrayEquals(hashData, chunk.hashData());
    }

    // Calc hashes

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
    void calcHashPath2BeyondLastLeafTest() {
        // Test the special case where path 2 is beyond lastLeafPath
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 1);

        // Set hash for path 1 only (firstLeafPath and lastLeafPath are 1)
        final Hash hash1 =
                new Hash("111111111111111111111111111111111111111111111111".getBytes(StandardCharsets.UTF_8));
        chunk.setHashAtPath(1, hash1);

        // Calculate hash at path 0 (root), which needs to hash(hash1, hash2)
        // Since path 2 > lastLeafPath (1), it should use VirtualHasher.NO_PATH2_HASH
        final Hash calculatedRootHash = chunk.calcHash(0, 1, 1);

        // Expected: hash of (hash1, NO_PATH2_HASH)
        final Hash expectedRootHash = VirtualHasher.hashInternal(hash1, VirtualHasher.NO_PATH2_HASH);
        assertEquals(expectedRootHash, calculatedRootHash);

        // Also test via chunkRootHash
        final Hash chunkRootHash = chunk.chunkRootHash(1, 1);
        assertEquals(expectedRootHash, chunkRootHash);
    }

    @Test
    void calcHashInternalRankPathTest() {
        // Test calculating hash for a path at an internal rank (not at last chunk rank)
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 2);

        final Hash hash3 =
                new Hash("333333333333333333333333333333333333333333333333".getBytes(StandardCharsets.UTF_8));
        final Hash hash4 =
                new Hash("444444444444444444444444444444444444444444444444".getBytes(StandardCharsets.UTF_8));
        final Hash hash5 =
                new Hash("555555555555555555555555555555555555555555555555".getBytes(StandardCharsets.UTF_8));
        final Hash hash6 =
                new Hash("666666666666666666666666666666666666666666666666".getBytes(StandardCharsets.UTF_8));

        chunk.setHashAtPath(3, hash3);
        chunk.setHashAtPath(4, hash4);
        chunk.setHashAtPath(5, hash5);
        chunk.setHashAtPath(6, hash6);

        // Calculate hash at path 1 (internal rank)
        final Hash hash1Calculated = chunk.calcHash(1, 3, 6);
        final Hash hash1Expected = VirtualHasher.hashInternal(hash3, hash4);
        assertEquals(hash1Expected, hash1Calculated);

        // Calculate hash at path 2 (internal rank)
        final Hash hash2Calculated = chunk.calcHash(2, 3, 6);
        final Hash hash2Expected = VirtualHasher.hashInternal(hash5, hash6);
        assertEquals(hash2Expected, hash2Calculated);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void calcHashAtLastChunkRankTest(final int height) {
        final Random random = new Random();
        final int chunkSize = VirtualHashChunk.getChunkSize(height);
        final long chunkPath =
                Path.getLeftGrandChildPath(0, random.nextInt(1, 8) * height) + random.nextInt(1 << height);
        final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, height);

        for (int i = 0; i < chunkSize; i++) {
            final Hash hash = genRandomHash();
            chunk.setHashAtIndex(i, hash);
        }

        // Check that getHash() and calcHash() are equal
        final long firstLeafPath = chunk.getPath(chunkSize - 1) + 1; // must be greater than the last chunk path
        final long lastLeafPath = firstLeafPath * 2;
        for (int i = 0; i < chunkSize; i++) {
            final long path = chunk.getPath(i);
            assertEquals(chunk.getHashAtIndex(i), chunk.calcHash(path, firstLeafPath, lastLeafPath));
        }
    }

    @Test
    void calcHashRecursiveDepthTest() {
        // Test that recursion works correctly through multiple levels
        final VirtualHashChunk chunk = new VirtualHashChunk(0, 3);

        for (int i = 0; i < chunk.getChunkSize(); i++) {
            final Hash hash = genRandomHash();
            chunk.setHashAtPath(1 + i, hash);
        }

        // Calculate intermediate nodes to verify recursion
        final Hash hash3 = chunk.calcHash(3, 7, 14); // Should calculate from paths 7-8
        assertNotNull(hash3);
        final Hash hash4 = chunk.calcHash(4, 7, 14); // Should calculate from paths 9-10
        assertNotNull(hash4);

        final Hash expectedHash1 = VirtualHasher.hashInternal(hash3, hash4);
        final Hash hash1 = chunk.calcHash(1, 7, 14);
        assertEquals(expectedHash1, hash1);
    }
}
