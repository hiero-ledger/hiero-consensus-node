// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.checkDirectMemoryIsCleanedUpToLessThanBaseUsage;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.getDirectMemoryUsedBytes;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.hash;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HashListByteBufferTest {

    private static final int LARGE_MAX_HASHES = 1_000_000;
    private static final int LARGE_HASHES_PER_BUFFER = 10_000;

    public HashListByteBuffer createHashList(final int hashesPerBuffer, final long capacity, final boolean offHeap) {
        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(MerkleDbConfig.class)
                .withSource(new SimpleConfigSource("merkleDb.hashStoreRamBufferSize", hashesPerBuffer))
                .withSource(new SimpleConfigSource("merkleDb.hashStoreRamOffHeapBuffers", offHeap))
                .build();
        return new HashListByteBuffer(capacity, config);
    }

    public HashList createHashList(
            final Path file, final int hashesPerBuffer, final long capacity, final boolean offHeap) throws IOException {
        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(MerkleDbConfig.class)
                .withSource(new SimpleConfigSource("merkleDb.hashStoreRamBufferSize", hashesPerBuffer))
                .withSource(new SimpleConfigSource("merkleDb.hashStoreRamOffHeapBuffers", offHeap))
                .build();
        return new HashListByteBuffer(file, capacity, config);
    }

    /**
     * Keep track of initial direct memory used already, so we can check if we leek over and above what we started with
     */
    private long directMemoryUsedAtStart;

    @BeforeEach
    void initializeDirectMemoryAtStart() {
        directMemoryUsedAtStart = getDirectMemoryUsedBytes();
    }

    @AfterEach
    void checkDirectMemoryForLeeks() {
        // check all memory is freed after DB is closed
        assertTrue(
                checkDirectMemoryIsCleanedUpToLessThanBaseUsage(directMemoryUsedAtStart),
                "Direct Memory used is more than base usage even after 20 gc() calls. At start was "
                        + (directMemoryUsedAtStart * UnitConstants.BYTES_TO_MEBIBYTES) + "MB and is now "
                        + (getDirectMemoryUsedBytes() * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB");
    }

    // ------------------------------------------------------
    // Testing instance creation
    // ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance")
    void createInstance(final boolean offHeap) {
        // If this is created with no exceptions, then we will declare victory
        try (final HashList hashList = createHashList(10, 100, offHeap)) {
            assertEquals(100, hashList.capacity(), "Capacity should match capacity arg");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a negative for capacity throws IAE")
    void createInstanceWithNegativeCapacityThrows(final boolean offHeap) {
        assertThrows(
                IllegalArgumentException.class,
                () -> createHashList(10, -1, offHeap),
                "Negative max hashes shouldn't be allowed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a zero for capacity is fine")
    void createInstanceWithZeroCapacityIsOk(final boolean offHeap) {
        assertDoesNotThrow(() -> createHashList(10, 0, offHeap), "Should be legal to create a permanently empty list");
    }

    // ------------------------------------------------------
    // Testing get
    // ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for out of bounds conditions on get")
    void badIndexOnGetThrows(final boolean offHeap) throws Exception {
        try (final HashList hashList = createHashList(10, 100, offHeap)) {
            // Negative is no good
            assertThrows(IndexOutOfBoundsException.class, () -> hashList.get(-1), "Negative indices should be illegal");
            // Max of 1,000 hashes, but I'm going for index 1000, which would hold the 1001st hash. So out of bounds.
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> hashList.get(10 * 100),
                    "Size of list shouldn't be a valid index");
            // Clearly out of bounds.
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> hashList.get((10 * 100) + 1),
                    "Out-of-range indices shouldn't be valid");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for null on get of missing hashes")
    void getOnEmptyHashesReturnsNull(final boolean offHeap) throws IOException {
        try (final HashList hashList = createHashList(10, 100, offHeap)) {
            for (int i = 0; i < 100; i++) {
                assertNull(hashList.get(i), "Hashes not explicitly put should be null");
            }
        }
    }

    // ------------------------------------------------------
    // Testing put
    // ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for out of bounds conditions on put")
    void badIndexOnPutThrows(final boolean offHeap) throws IOException {
        try (final HashList hashList = createHashList(10, 1000, offHeap)) {
            final Hash hash = hash(123);
            // Negative is no good
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> hashList.put(-1, hash),
                    "Negative indices shouldn't be allowed");
            // Max of 1,000 hashes, but I'm going for index 1000, which would hold the 1001st hash. So out of bounds.
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> hashList.put(10 * 100, hash),
                    "Size should not be a valid index");
            // Clearly out of bounds.
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> hashList.put((10 * 100) + 1, hash),
                    "Out-of-bounds indexes shouldn't be allowed");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Put a hash at the end of the available range forcing creation of multiple buffers")
    void putAtEndOfRange(final boolean offHeap) throws IOException {
        try (final HashList hashList = createHashList(10, 100, offHeap)) {
            final Hash hash93 = hash(93);
            hashList.put(93, hash93);
            assertEquals(hash93, hashList.get(93), "Hash put at fixed index should be gettable from same index");
            final Hash hash99 = hash(99);
            hashList.put(99, hash99);
            assertEquals(hash99, hashList.get(99), "Hash put at fixed index should be gettable from same index");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Put hashes from max index to 0")
    void putInReverseOrder(final boolean offHeap) throws IOException {
        final int hashCount = 99;
        try (final HashList hashList = createHashList(10, hashCount, offHeap)) {
            for (int i = hashCount - 1; i >= 0; i--) {
                hashList.put(i, hash(hashCount - i));
            }
            assertEquals(hashCount, hashList.size());
            for (int i = 0; i < hashCount; i++) {
                assertEquals(hash(hashCount - i), hashList.get(i), "Unexpected hash at index " + i);
            }
        }
    }

    @RepeatedTest(100)
    void concurrentPuts() throws IOException {
        final int hashCount = 10_000;
        final int hashesPerBuffer = 20;
        try (final HashListByteBuffer hashList = createHashList(hashesPerBuffer, hashCount, true)) {
            final int threadCount = 5;
            IntStream.range(0, threadCount).parallel().forEach(t -> {
                for (int i = t; i < hashCount; i += threadCount) {
                    hashList.put(i, hash(i + 1));
                }
            });
            assertEquals(hashCount / hashesPerBuffer, hashList.getCurrentBufferCount());
            for (int i = 0; i < hashCount; i++) {
                assertEquals(hash(i + 1), hashList.get(i), "Unexpected hash at index " + i);
            }
        }
    }

    // ------------------------------------------------------
    // Larger tests that hammer things more
    // ------------------------------------------------------

    @ParameterizedTest
    @MethodSource("provideLargeHashLists")
    @DisplayName("Randomly set hashes across the entire hash list space from multiple threads with no collisions")
    void putRandomlyAcrossAll(final HashList hashList) throws IOException {
        // Write from multiple threads concurrently, but not to the same indexes
        IntStream.range(0, LARGE_MAX_HASHES).parallel().forEach(index -> {
            final Hash hash = hash(index);
            hashList.put(index, hash);
        });

        // Read from multiple threads concurrently, but not to the same indexes.
        IntStream.range(0, LARGE_MAX_HASHES).parallel().forEach(index -> {
            final Hash hash = hash(index);

            try {
                assertEquals(hash, hashList.get(index), () -> "Wrong hash read from index " + index);
            } catch (Exception e) {
                fail("Getting a hash from valid index " + index + " failed", e);
            }
        });
        // close
        hashList.close();
    }

    // ------------------------------------------------------
    // Test writing to file and reading back
    // ------------------------------------------------------

    @Test
    void restoreWithHashesPerBuffer(@TempDir final Path testDir) throws IOException {
        final int hashCount = 100;
        final Path file = testDir.resolve("restoreWithHashesPerBuffer.hl");
        try (final HashList hashList = createHashList(20, hashCount, true)) {
            for (int i = 0; i < hashCount; i++) {
                final Hash hash = hash(i);
                hashList.put(i, hash);
            }
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            assertEquals(
                    Integer.BYTES
                            + HashListByteBuffer.FILE_HEADER_SIZE_V2
                            + (long) hashCount * DigestType.SHA_384.digestLength(),
                    Files.size(file));
            try (final HashList restored = createHashList(file, 11, hashCount, true)) {
                assertEquals(hashList.size(), restored.size());
                assertEquals(hashList.capacity(), restored.capacity(), "Unexpected capacity: " + restored.capacity());
                for (int i = 0; i < hashCount; i++) {
                    assertEquals(hash(i), restored.get(i), "Wrong hash read from index " + i);
                }
            }
        }
    }

    @Test
    void restoreAndPut(@TempDir final Path testDir) throws IOException {
        final int hashCount = 100;
        final Path file = testDir.resolve("restoreAndPut.hl");
        try (final HashList hashList = createHashList(12, hashCount, true)) {
            for (int i = 0; i < hashCount / 2; i++) {
                hashList.put(i, hash(i));
            }
            assertEquals(hashCount / 2, hashList.size(), "Unexpected size: " + hashList.size());
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            assertEquals(
                    Integer.BYTES
                            + HashListByteBuffer.FILE_HEADER_SIZE_V2
                            + (long) hashCount / 2 * DigestType.SHA_384.digestLength(),
                    Files.size(file));
            try (final HashList restored = createHashList(file, 12, hashCount, true)) {
                assertEquals(hashList.size(), restored.size());
                assertEquals(hashList.capacity(), restored.capacity(), "Unexpected capacity: " + restored.capacity());
                for (int i = hashCount / 2; i < hashCount; i++) {
                    restored.put(i, hash(i));
                }
                for (int i = 0; i < hashCount; i++) {
                    assertEquals(hash(i), restored.get(i), "Wrong hash read from index " + i);
                }
            }
        }
    }

    @Test
    void saveManyBuffersRestoreOneBuffer(@TempDir final Path testDir) throws IOException {
        final ByteBuffer allZeroes = ByteBuffer.allocate(DigestType.SHA_384.digestLength());
        final Hash allZeroesHash = new Hash(Bytes.wrap(allZeroes.array()));
        final int hashCount = 999;
        final int gap = hashCount / 10;
        final Path file = testDir.resolve("saveManyBuffersRestoreOneBuffer.hl");
        try (final HashList hashList = createHashList(37, hashCount, true)) {
            for (int i = gap; i < hashCount - gap; i++) {
                hashList.put(i, hash(i));
            }
            assertEquals(hashCount - gap, hashList.size(), "Unexpected size: " + hashList.size());
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            try (final HashList restored = createHashList(file, hashCount + 1, hashCount, true)) {
                assertEquals(hashList.size(), restored.size());
                assertEquals(hashList.capacity(), restored.capacity(), "Unexpected capacity: " + restored.capacity());
                for (int i = 0; i < gap; i++) {
                    // FUTURE WORK
                    // HashList.get() should return null, if no hash was put for the given index. However,
                    // current implementation is that a non-null hash is returned instead, with all bytes
                    // set to zeroes
                    // assertNull(restored.get(i), "Wrong hash read from index " + i);
                    assertEquals(allZeroesHash, restored.get(i), "Wrong hash read from index " + i);
                    // For indices greater or equal to hash list size, nulls are returned
                    assertNull(restored.get(hashCount - 1 - i), "Wrong hash read from index " + i);
                }
                for (int i = gap; i < hashCount - gap; i++) {
                    assertEquals(hash(i), restored.get(i), "Wrong hash read from index " + i);
                }
            }
        }
    }

    @Test
    void saveoneBufferRestoreMultipleBuffer(@TempDir final Path testDir) throws IOException {
        final int hashCount = 1000;
        final int t = 100;
        final Path file = testDir.resolve("saveoneBufferRestoreMultipleBuffer.hl");
        try (final HashList hashList = createHashList(2 * hashCount, hashCount, true)) {
            for (int i = 0; i < t * 9; i++) {
                hashList.put(i, hash(i));
            }
            assertEquals(t * 9, hashList.size(), "Unexpected size: " + hashList.size());
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            try (final HashList restored = createHashList(file, t, hashCount, true)) {
                assertEquals(hashList.size(), restored.size());
                assertEquals(hashList.capacity(), restored.capacity(), "Unexpected capacity: " + restored.capacity());
                for (int i = 0; i < t * 9; i++) {
                    assertEquals(hash(i), restored.get(i), "Wrong hash read from index " + i);
                }
                for (int i = t * 9; i < hashCount; i++) {
                    assertNull(hashList.get(i));
                }
            }
        }
    }

    @Test
    void cannotRestoreDifferentCapacity(@TempDir final Path testDir) throws IOException {
        final int hashCount = 100;
        final Path file = testDir.resolve("cannotRestoreDifferentCapacity.hl");
        try (final HashList hashList = createHashList(10, hashCount, true)) {
            for (int i = 0; i < hashCount; i++) {
                hashList.put(i, hash(i));
            }
            assertEquals(hashCount, hashList.size(), "Unexpected size: " + hashList.size());
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            assertThrows(IllegalArgumentException.class, () -> createHashList(file, 10, hashCount + 1, true));
            assertThrows(IllegalArgumentException.class, () -> createHashList(file, 10, hashCount - 1, true));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restoreOnOffHeap(final boolean offheap, @TempDir final Path testDir) throws IOException {
        final int hashCount = 100;
        final Path file = testDir.resolve("restoreOnOffHeap.hl");
        try (final HashList hashList = createHashList(hashCount / 7, hashCount, offheap)) {
            for (int i = 0; i < hashCount; i++) {
                final Hash hash = hash(i);
                hashList.put(i, hash);
            }
            hashList.writeToFile(file);
            assertTrue(Files.exists(file));
            try (final HashList restored = createHashList(file, 10, hashCount, !offheap)) {
                assertEquals(hashList.size(), restored.size());
                assertEquals(hashList.capacity(), restored.capacity(), "Unexpected capacity: " + restored.capacity());
                for (int i = 0; i < hashCount; i++) {
                    assertEquals(hash(i), restored.get(i), "Wrong hash read from index " + i);
                }
            }
        }
    }

    @Test
    void restoreFromV1() throws Exception {
        // v1 hash list: buffer size = 48 hashes, max hashes = 1024, num hashes = 800
        final Path file = ResourceLoader.getFile("test_data/HashList_48_1024_800_v1.hl");
        try (final HashList hashList = createHashList(file, 48, 1024, true)) {
            assertEquals(800, hashList.size(), "Unexpected size: " + hashList.size());
            assertEquals(1024, hashList.capacity(), "Unexpected capacity: " + hashList.capacity());
            for (int i = 0; i < 800; i++) {
                assertEquals(hash(i), hashList.get(i), "Wrong hash read from index " + i);
            }
            for (int i = 800; i < 1024; i++) {
                assertNull(hashList.get(i), "Wrong hash read from index " + i);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> hashList.get(1025));
        }
    }

    @Test
    void restoreFromV1DecreasedCapacity() throws Exception {
        // v1 hash list: buffer size = 48 hashes, max hashes = 1024, num hashes = 800
        final Path file = ResourceLoader.getFile("test_data/HashList_48_1024_800_v1.hl");
        try (final HashList hashList = createHashList(file, 48, 800, true)) {
            assertEquals(800, hashList.size(), "Unexpected size: " + hashList.size());
            assertEquals(800, hashList.capacity(), "Unexpected capacity: " + hashList.capacity());
            for (int i = 0; i < 800; i++) {
                assertEquals(hash(i), hashList.get(i), "Wrong hash read from index " + i);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> hashList.get(800));
        }
    }

    @Test
    void restoreFromV1CapacityNotEnough() throws Exception {
        // v1 hash list: buffer size = 48 hashes, max hashes = 1024, num hashes = 800
        final Path file = ResourceLoader.getFile("test_data/HashList_48_1024_800_v1.hl");
        assertThrows(IllegalArgumentException.class, () -> createHashList(file, 48, 799, true));
    }

    @Test
    void testFiles(@TempDir final Path testDir) throws IOException {
        final Path file = testDir.resolve("HashListByteBufferTest.hl");
        // create a HashList with a bunch of data
        try (final HashList hashList = createHashList(20, 100, true)) {
            for (int i = 0; i < 95; i++) {
                final Hash hash = hash(i);
                hashList.put(i, hash);
            }
            // check all data
            for (int i = 0; i < 95; i++) {
                assertEquals(hash(i), hashList.get(i), "Unexpected value for hashList.get(" + i + ")");
            }
            // write hash list to the file
            hashList.writeToFile(file);
            // check file exists and contains some data
            assertTrue(Files.exists(file), "file should exist");
            assertTrue(Files.size(file) > (48 * 95), "file should contain some data");
            // now try and construct a new HashList reading from the file
            try (final HashList hashList2 = createHashList(file, 20, hashList.capacity(), true)) {
                // now check data and other attributes
                assertEquals(hashList.capacity(), hashList2.capacity(), "Unexpected value for hashList2.capacity()");
                assertEquals(hashList.size(), hashList2.size(), "Unexpected value for hashList2.size()");
                for (int i = 0; i < 95; i++) {
                    assertEquals(hash(i), hashList2.get(i), "Unexpected value for hashList2.get(" + i + ")");
                }
            }
            // delete file as we are done with it
            Files.delete(file);
        }
    }

    private Stream<Arguments> provideLargeHashLists() {
        return Stream.of(
                Arguments.of(createHashList(LARGE_HASHES_PER_BUFFER, LARGE_MAX_HASHES, false)),
                Arguments.of(createHashList(LARGE_HASHES_PER_BUFFER, LARGE_MAX_HASHES, true)));
    }
}
