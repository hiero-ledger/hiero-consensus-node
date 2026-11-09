// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_VIRTUAL_MAP_CONFIG;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.Hash;
import org.hiero.base.file.FileSystemManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Tests [VirtualMap#detach()] and [VirtualMap#detachAsDataSourceCopy()] with MerkleDb data
/// sources, with in-memory and disk based indices. The detached map has data both in its data source
/// and in its cache.
class VirtualMapMerkleDbDetachTest {

    /// The number of keys flushed to the data source. Keys are from 0 to COUNT - 1
    private static final int COUNT = 10_000;

    /// The number of keys removed, and the number of keys added, in the cache of the detached copy
    private static final int CHANGED = COUNT / 10;

    @TempDir
    private Path tempDir;

    @AfterEach
    void checkDatabasesClosed() {
        assertAllDatabasesClosed();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Detached copies have the data from the data source and the cache, and outlive the map")
    void detachedCopiesHaveAllData(final boolean useDiskIndices) throws Exception {
        final Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.USE_DISK_INDICES, Boolean.toString(useDiskIndices))
                .build();
        final FileSystemManager fileSystemManager = new FileSystemManager(tempDir);
        final MerkleDbDataSourceBuilder builder = new MerkleDbDataSourceBuilder(config, fileSystemManager, COUNT);
        final Map<Long, String> expected = new HashMap<>();

        // Flush the first copy to the data source
        final VirtualMap map0 = new VirtualMap(builder, config);
        for (long k = 0; k < COUNT; k++) {
            put(map0, expected, k, "v0-" + k);
        }
        map0.enableFlush();
        final VirtualMap map1 = map0.copy();
        map0.release();
        assertEventuallyTrue(map0::isFlushed, Duration.ofSeconds(30), "The first copy should be flushed");

        // Keep changes to the second copy in its cache: update, remove and add keys
        for (long k = 0; k < COUNT / 2; k++) {
            put(map1, expected, k, "v1-" + k);
        }
        for (long k = COUNT / 2; k < COUNT / 2 + CHANGED; k++) {
            map1.remove(TestKey.longToKey(k), TestValueCodec.INSTANCE);
            expected.remove(k);
        }
        for (long k = COUNT; k < COUNT + CHANGED; k++) {
            put(map1, expected, k, "v1-" + k);
        }
        final VirtualMap map2 = map1.copy();
        final Hash expectedHash = map1.getHash();

        RecordAccessor detached = null;
        RecordAccessor dataSourceCopy = null;
        try {
            detached = map1.detach();
            dataSourceCopy = accessorFor(map1.detachAsDataSourceCopy(), detached.getMetadata());

            // Changes to newer copies don't affect detached copies
            for (long k = 0; k < COUNT + CHANGED; k++) {
                map2.put(TestKey.longToKey(k), new TestValue("v2-" + k), TestValueCodec.INSTANCE);
            }
            assertContents(expected, expectedHash, detached);
            assertContents(expected, expectedHash, dataSourceCopy);

            // Detached copies outlive the map family and its data source
            map1.release();
            map2.release();
            assertTrue(map1.waitUntilFamilyDestroyed(Duration.ofSeconds(30)), "Map family should be destroyed");
            assertContents(expected, expectedHash, detached);
            assertContents(expected, expectedHash, dataSourceCopy);
        } finally {
            if (!map1.isDestroyed()) {
                map1.release();
            }
            if (!map2.isDestroyed()) {
                map2.release();
            }
            if (detached != null) {
                detached.close();
            }
            if (dataSourceCopy != null) {
                dataSourceCopy.close();
            }
        }
    }

    private static void put(
            final VirtualMap map, final Map<Long, String> expected, final long key, final String value) {
        map.put(TestKey.longToKey(key), new TestValue(value), TestValueCodec.INSTANCE);
        expected.put(key, value);
    }

    /// Wraps a data source copy into a record accessor with an empty cache, so all reads go to the
    /// data source copy. Closing the accessor closes the data source copy.
    private static RecordAccessor accessorFor(final VirtualDataSource dataSource, final VirtualMap.Metadata metadata) {
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        final VirtualNodeCache emptyCache =
                new VirtualNodeCache(DEFAULT_VIRTUAL_MAP_CONFIG, hashChunkHeight, dataSource::loadHashChunk);
        return new RecordAccessor(metadata, hashChunkHeight, emptyCache, dataSource);
    }

    private static void assertContents(
            final Map<Long, String> expected, final Hash expectedHash, final RecordAccessor accessor)
            throws IOException {
        assertEquals(expectedHash, accessor.rootHash(), "Root hash mismatch");
        for (long k = 0; k < COUNT + CHANGED; k++) {
            final VirtualLeafBytes<TestValue> leaf = accessor.findLeafRecord(TestKey.longToKey(k));
            final String value = expected.get(k);
            if (value == null) {
                assertNull(leaf, "Key " + k + " should be removed");
            } else {
                assertNotNull(leaf, "Key " + k + " should be found");
                assertEquals(
                        new TestValue(value),
                        leaf.value(TestValueCodec.INSTANCE, Codec.DEFAULT_MAX_SIZE),
                        "Value mismatch for key " + k);
            }
        }
    }
}
