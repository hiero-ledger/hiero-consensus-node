// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Compares the result of [VirtualMap#fullLeafRehashIfNecessary()] with the hash computed by
/// [VirtualMap#getHash()] for the same leaves on trees of different sizes.
class FullRehashComparisonTest extends VirtualTestBase {

    static Stream<Arguments> chunkHeightsAndLeafCounts() {
        // Chunk heights 1, 2, 3 (in-memory data source default), and 6 (MerkleDb default). Leaf
        // counts cover first leaf ranks aligned and not aligned with chunk ranks, as well as single
        // leaf and two leaves trees
        return IntStream.of(1, 2, 3, 6).boxed().flatMap(height -> IntStream.of(
                        1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 63, 64, 65, 100, 1000, 5000)
                .mapToObj(leaves -> Arguments.of(height, leaves)));
    }

    @ParameterizedTest(name = "chunkHeight={0}, leaves={1}")
    @MethodSource("chunkHeightsAndLeafCounts")
    @DisplayName("Full rehash produces the same hashes as VirtualMap.getHash()")
    void fullRehashMatchesGetHash(final int chunkHeight, final int leafCount) throws IOException {
        // The expected map: leaves are put to the map and hashed in a regular way
        final VirtualMap expectedMap = new VirtualMap(new InMemoryBuilder(), DEFAULT_CONFIGURATION);
        for (int i = 0; i < leafCount; i++) {
            expectedMap.putBytes(TestKey.longToKey(i), Bytes.wrap("value-" + i));
        }
        final VirtualMap copy = expectedMap.copy();
        final Hash expectedHash = expectedMap.getHash();

        final long firstLeafPath = expectedMap.getMetadata().getFirstLeafPath();
        final long lastLeafPath = expectedMap.getMetadata().getLastLeafPath();

        // The map to rehash: the same leaves, but all hashes in the data source are wrong
        final VirtualMap map = new VirtualMap(new CustomBuilder(chunkHeight, path -> false), DEFAULT_CONFIGURATION);
        try {
            populateWithWrongHashes(map, expectedMap, firstLeafPath, lastLeafPath);

            map.fullLeafRehashIfNecessary();

            assertEquals(expectedHash, map.getHash(), "Root hash mismatch");
            // Hashes are read from the data source, as the map cache is empty
            for (long path = 1; path <= lastLeafPath; path++) {
                assertEquals(
                        expectedMap.getRecords().findHash(path),
                        map.getRecords().findHash(path),
                        "Hash mismatch, path=" + path);
            }
        } finally {
            map.release();
            expectedMap.release();
            copy.release();
        }
    }

    @Test
    @DisplayName("Full rehash fails if a leaf can't be read")
    void fullRehashFailsOnLeafReadFailure() throws IOException {
        final VirtualMap expectedMap = new VirtualMap(new InMemoryBuilder(), DEFAULT_CONFIGURATION);
        for (int i = 0; i < 1000; i++) {
            expectedMap.putBytes(TestKey.longToKey(i), Bytes.wrap("value-" + i));
        }
        final VirtualMap copy = expectedMap.copy();
        expectedMap.getHash();

        final long firstLeafPath = expectedMap.getMetadata().getFirstLeafPath();
        final long lastLeafPath = expectedMap.getMetadata().getLastLeafPath();

        // The first leaf is read to check if full rehash is needed, so fail on the last leaf
        final VirtualMap map =
                new VirtualMap(new CustomBuilder(3, path -> path == lastLeafPath), DEFAULT_CONFIGURATION);
        try {
            populateWithWrongHashes(map, expectedMap, firstLeafPath, lastLeafPath);

            final RuntimeException e = assertThrows(RuntimeException.class, map::fullLeafRehashIfNecessary);
            assertTrue(hasCause(e, IOException.class), "The root cause must be the leaf read exception");
        } finally {
            map.release();
            expectedMap.release();
            copy.release();
        }
    }

    // Saves all leaves from the expected map to the map data source, and sets all leaf hashes
    // to a wrong value, so full rehash is triggered
    private static void populateWithWrongHashes(
            final VirtualMap map, final VirtualMap expectedMap, final long firstLeafPath, final long lastLeafPath)
            throws IOException {
        map.getMetadata().setPaths(firstLeafPath, lastLeafPath);
        final VirtualDataSource dataSource = map.getDataSource();
        final int chunkHeight = dataSource.getHashChunkHeight();
        final Map<Long, VirtualHashChunk> chunks = new HashMap<>();
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        for (long path = firstLeafPath; path <= lastLeafPath; path++) {
            leaves.add(expectedMap.getRecords().findLeafRecord(path));
            chunks.computeIfAbsent(
                            VirtualHashChunk.pathToChunkPath(path, chunkHeight),
                            p -> new VirtualHashChunk(p, chunkHeight))
                    .setHashAtPath(path, wrongHash());
        }
        dataSource.saveRecords(
                firstLeafPath, lastLeafPath, chunks.values().stream(), leaves.stream(), Stream.empty(), false);
    }

    private static boolean hasCause(final Throwable t, final Class<? extends Throwable> type) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Hash wrongHash() {
        final byte[] bytes = new byte[Cryptography.DEFAULT_DIGEST_TYPE.digestLength()];
        bytes[0] = 1;
        return new Hash(bytes, Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /// Builds in-memory data sources with the given hash chunk height, which fail to read
    /// leaves at the given paths.
    private static final class CustomBuilder extends InMemoryBuilder {

        private final int chunkHeight;
        private final LongPredicate failingLeafPaths;

        CustomBuilder(final int chunkHeight, final LongPredicate failingLeafPaths) {
            this.chunkHeight = chunkHeight;
            this.failingLeafPaths = failingLeafPaths;
        }

        @Override
        protected InMemoryDataSource createDataSource(final String name) {
            return new InMemoryDataSource(name) {
                @Override
                public int getHashChunkHeight() {
                    return chunkHeight;
                }

                @Override
                public VirtualLeafBytes loadLeafRecord(final long path) {
                    if (failingLeafPaths.test(path)) {
                        throw new UncheckedIOException(new IOException("Test leaf read failure, path = " + path));
                    }
                    return super.loadLeafRecord(path);
                }
            };
        }
    }
}
