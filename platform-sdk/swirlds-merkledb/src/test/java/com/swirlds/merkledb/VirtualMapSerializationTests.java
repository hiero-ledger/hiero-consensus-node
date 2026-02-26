// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.assertVmsAreEqual;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.test.fixtures.ExampleFixedValue;
import com.swirlds.merkledb.test.fixtures.ExampleLongKey;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("VirtualMap Serialization Test")
class VirtualMapSerializationTests {

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkledb");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
    }

    /**
     * Create a new virtual map data source builder.
     */
    public static MerkleDbDataSourceBuilder constructBuilder() {
        return constructBuilder(CONFIGURATION);
    }

    public static MerkleDbDataSourceBuilder constructBuilder(final Configuration configuration) {
        return new MerkleDbDataSourceBuilder(configuration, 10_000);
    }

    /**
     * Add a number of randomized entries to the map.
     *
     * @param map
     * 		the map to update
     * @param count
     * 		the number of entries to add or update
     * @param updateCount
     * 		the number of entries to update. If zero then all entries are added.
     * @param seed
     * 		the seed to use
     */
    private void addRandomEntries(final VirtualMap map, final int count, final int updateCount, final long seed) {

        final Random random = new Random(seed);
        final int offset = (int) Math.max(0, map.size() - updateCount);

        for (int i = 0; i < count; i++) {
            final int v = random.nextInt();

            final Bytes key = ExampleLongKey.longToKey(i + offset);
            final ExampleFixedValue value = new ExampleFixedValue(v);

            map.put(key, value, ExampleFixedValue.CODEC);
        }
    }

    /**
     * Create a map and fill it with random key/value pairs.
     */
    @SuppressWarnings("SameParameterValue")
    private VirtualMap generateRandomMap(final long seed, final int count) {
        final VirtualMap map = new VirtualMap(constructBuilder(), CONFIGURATION);
        addRandomEntries(map, count, 0, seed);
        return map;
    }

    /**
     * Make sure the comparison utility function works as expected.
     */
    @Test
    @DisplayName("Map Comparison Test")
    void mapComparisonTest() throws InterruptedException {
        final VirtualMap map0 = generateRandomMap(0, 1_000);
        final VirtualMap map1 = generateRandomMap(0, 1_000);
        final VirtualMap map2 = generateRandomMap(1234, 1_000);

        try {
            assertVmsAreEqual(map0, map0);
            assertVmsAreEqual(map0, map1);
            assertVmsAreEqual(map1, map1);
            assertVmsAreEqual(map1, map0);
            assertVmsAreEqual(map2, map2);
            assertThrows(AssertionError.class, () -> assertVmsAreEqual(map0, map2), "maps should not be equal");
            assertThrows(AssertionError.class, () -> assertVmsAreEqual(map1, map2), "maps should not be equal");
            assertThrows(AssertionError.class, () -> assertVmsAreEqual(map2, map0), "maps should not be equal");
            assertThrows(AssertionError.class, () -> assertVmsAreEqual(map2, map1), "maps should not be equal");
        } finally {
            map0.release();
            map1.release();
            map2.release();

            assertTrue(map2.getPipeline().awaitTermination(10, SECONDS), "Pipeline termination timed out");
        }
    }

    /**
     * Test serialization of a map. Does not release any resources created by caller.
     */
    @SuppressWarnings("resource")
    private void testMapSerialization(final VirtualMap map) throws IOException {

        final Path savedStateDirectory =
                LegacyTemporaryFileBuilder.buildTemporaryDirectory("saved-state", CONFIGURATION);

        // Make sure the map is hashed
        map.getHash();

        map.createSnapshot(savedStateDirectory);

        try (final Stream<Path> filesInDirectory = Files.list(savedStateDirectory)) {
            List<Path> list = filesInDirectory.toList();
            assertNotNull(list, "saved state directory is not a valid directory");
            assertFalse(list.isEmpty(), "there should be a non-zero number of files created");
        }

        final VirtualMap deserializedMap =
                VirtualMap.loadFromDirectory(savedStateDirectory, CONFIGURATION, () -> constructBuilder(CONFIGURATION));

        assertVmsAreEqual(map, deserializedMap);

        deserializedMap.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 10, 100, 1000, 1023, 1024, 1025})
    @DisplayName("Serialize Unflushed Data")
    void serializeUnflushedData(final int count) throws IOException, InterruptedException {

        final long seed = new Random().nextLong();
        System.out.println("seed = " + seed);

        final VirtualMap map = generateRandomMap(seed, count);
        final VirtualMap copy = map.copy();

        try {
            testMapSerialization(map);
            assertFalse(map.isFlushed(), "for this test, the root is expected not to be flushed");
        } finally {
            map.release();
            copy.release();
            assertTrue(map.getPipeline().awaitTermination(10, SECONDS), "Pipeline termination timed out");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 10, 100, 1000, 1023, 1024, 1025})
    @DisplayName("Serialize Only Flushed Data")
    void serializeOnlyFlushedData(final int count) throws InterruptedException, IOException {
        final long seed = new Random().nextLong();
        System.out.println("seed = " + seed);

        final VirtualMap map = generateRandomMap(seed, count);
        map.enableFlush();

        final VirtualMap serializedCopy = map.copy();
        final VirtualMap mutableCopy = serializedCopy.copy();

        try {
            map.release();
            map.waitUntilFlushed();

            testMapSerialization(serializedCopy);
            assertTrue(map.isFlushed(), "for this test, the root is expected to be flushed");
        } finally {
            serializedCopy.release();
            mutableCopy.release();
            assertTrue(map.getPipeline().awaitTermination(10, SECONDS), "Pipeline termination timed out");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 10, 100, 1000, 1023, 1024, 1025})
    @DisplayName("Serialize Flushed And Unflushed Data")
    void serializeFlushedAndUnflushedData(final int count) throws InterruptedException, IOException {
        final long seed = new Random().nextLong();
        System.out.println("seed = " + seed);

        final VirtualMap map = generateRandomMap(seed, count);
        map.enableFlush();

        final VirtualMap copy0 = map.copy();
        addRandomEntries(copy0, count, count / 2, seed * 2 + 1);
        final VirtualMap copy1 = copy0.copy();

        try {
            map.release();
            map.waitUntilFlushed();

            System.out.println("map size: " + map.size() + ", copy0 size: " + copy0.size());
            testMapSerialization(copy0);

            assertTrue(map.isFlushed(), "for this test, the root is expected to be flushed");
            assertFalse(copy0.isFlushed(), "for this test, the root0 is expected to not be flushed");
        } finally {
            copy0.release();
            copy1.release();
            assertTrue(map.getPipeline().awaitTermination(10, SECONDS), "Pipeline termination timed out");
        }
    }
}
