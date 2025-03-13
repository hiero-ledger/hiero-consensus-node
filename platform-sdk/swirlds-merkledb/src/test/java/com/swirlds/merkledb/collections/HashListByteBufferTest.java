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

import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.crypto.Hash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    public HashList createHashList(final int numHashesPerBuffer, final long maxHashes, final boolean offHeap) {
        return new HashListByteBuffer(numHashesPerBuffer, maxHashes, offHeap);
    }

    public HashList createHashList(final Path file) throws IOException {
        return new HashListByteBuffer(file);
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
        final HashList hashList = new HashListByteBuffer(10, 100, offHeap);
        assertEquals(100, hashList.capacity(), "Capacity should match maxHashes arg");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a negative for numHashesPerBuffer throws IAE")
    void createInstanceWithNegativeHashesPerBufferThrows(final boolean offHeap) {
        assertThrows(
                IllegalArgumentException.class,
                () -> createHashList(-1, 100, offHeap),
                "Negative hashes per buffer shouldn't be allowed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a zero for numHashesPerBuffer throws IAE")
    void createInstanceWithZeroHashesPerBufferThrows(final boolean offHeap) {
        assertThrows(
                IllegalArgumentException.class,
                () -> createHashList(0, 100, offHeap),
                "Zero hashes per buffer shouldn't be allowed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a negative for maxHashes throws IAE")
    void createInstanceWithNegativeMaxHashesThrows(final boolean offHeap) {
        assertThrows(
                IllegalArgumentException.class,
                () -> createHashList(10, -1, offHeap),
                "Negative max hashes shouldn't be allowed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Creating an instance with a zero for maxHashes is fine")
    void createInstanceWithZeroMaxHashesIsOk(final boolean offHeap) {
        assertDoesNotThrow(() -> createHashList(10, 0, offHeap), "Should be legal to create a permanently empty list");
    }

    // ------------------------------------------------------
    // Testing get
    // ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for out of bounds conditions on get")
    void badIndexOnGetThrows(final boolean offHeap) throws Exception {
        final HashList hashList = createHashList(10, 100, offHeap);
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
        // close
        hashList.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for null on get of missing hashes")
    void getOnEmptyHashesReturnsNull(final boolean offHeap) throws IOException {
        final HashList hashList = createHashList(10, 100, offHeap);
        for (int i = 0; i < 100; i++) {
            assertNull(hashList.get(i), "Hashes not explicitly put should be null");
        }
    }

    // ------------------------------------------------------
    // Testing put
    // ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Check for out of bounds conditions on put")
    void badIndexOnPutThrows(final boolean offHeap) throws IOException {
        final HashList hashList = createHashList(10, 100, offHeap);
        final Hash hash = hash(123);
        // Negative is no good
        assertThrows(
                IndexOutOfBoundsException.class, () -> hashList.put(-1, hash), "Negative indices shouldn't be allowed");
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
        // close
        hashList.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Put a hash at the end of the available range forcing creation of multiple buffers")
    void putAtEndOfRange(final boolean offHeap) throws IOException {
        final HashList hashList = createHashList(10, 100, offHeap);
        final Hash hash = hash(93);
        hashList.put(93, hash);
        assertEquals(hash, hashList.get(93), "Hash put at fixed index should be gettable from same index");
        // close
        hashList.close();
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
    // Test writing to file adn reading back
    // ------------------------------------------------------

    @Test
    void testFiles(@TempDir final Path testDir) throws IOException {

        Path file = testDir.resolve("HashListByteBufferTest.hl");
        // create a HashList with a bunch of data
        HashList hashList = createHashList(20, 100, true);
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
        HashList hashList2 = createHashList(file);
        // now check data and other attributes
        assertEquals(hashList.capacity(), hashList2.capacity(), "Unexpected value for hashList2.capacity()");
        assertEquals(hashList.maxHashes(), hashList2.maxHashes(), "Unexpected value for hashList2.maxHashes()");
        assertEquals(hashList.size(), hashList2.size(), "Unexpected value for hashList2.size()");
        for (int i = 0; i < 95; i++) {
            assertEquals(hash(i), hashList2.get(i), "Unpected value for hashList2.get(" + i + ")");
        }
        // delete file as we are done with it
        Files.delete(file);
    }

    private Stream<Arguments> provideLargeHashLists() {
        return Stream.of(
                Arguments.of(createHashList(LARGE_HASHES_PER_BUFFER, LARGE_MAX_HASHES, false)),
                Arguments.of(createHashList(LARGE_HASHES_PER_BUFFER, LARGE_MAX_HASHES, true)));
    }
}
