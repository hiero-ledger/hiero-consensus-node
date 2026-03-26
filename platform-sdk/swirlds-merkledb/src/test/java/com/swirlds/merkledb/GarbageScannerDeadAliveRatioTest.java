// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.GarbageScanner.GarbageFileStats;
import com.swirlds.merkledb.GarbageScanner.IndexedGarbageFileStats;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.DataFileReader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GarbageScannerDeadAliveRatioTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    // ========================================================================
    // dead/alive ratio computation
    // ========================================================================

    @Nested
    @DisplayName("dead/alive ratio computation from scan stats")
    class RatioComputationTests {

        @Test
        @DisplayName("Normal case: partial alive items → correct dead/alive ratio")
        void normalDeadAliveRatio() {
            // 30 alive out of 100 total → 70 dead / 30 alive = 2.33
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);
            final LongList index = mockIndexWithEntries(locationsForFile(1, 30));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            final GarbageFileStats fs = stats.garbageFileStats()[0];
            assertNotNull(fs);
            assertEquals(30, fs.aliveItems());
            assertEquals(70, fs.deadItems());
            assertEquals(70.0 / 30.0, fs.deadToAliveRatio(), 1e-9);
        }

        @Test
        @DisplayName("All items alive → dead/alive = 0.0")
        void allAliveRatio() {
            final DataFileReader file = mockFileReader(1, 0, 50, 1000);
            final LongList index = mockIndexWithEntries(locationsForFile(1, 50));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(0.0, stats.garbageFileStats()[0].deadToAliveRatio());
        }

        @Test
        @DisplayName("All items dead → dead/alive = MAX_VALUE")
        void allDeadRatio() {
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);
            final LongList index = emptyIndex();

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(Double.MAX_VALUE, stats.garbageFileStats()[0].deadToAliveRatio());
        }

        @Test
        @DisplayName("Empty file (totalItems == 0) → dead/alive = MAX_VALUE")
        void emptyFileRatio() {
            final DataFileReader file = mockFileReader(1, 0, 0, 500);
            final LongList index = emptyIndex();

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(Double.MAX_VALUE, stats.garbageFileStats()[0].deadToAliveRatio());
        }

        @Test
        @DisplayName("Exactly half alive → dead/alive = 1.0")
        void halfAliveRatio() {
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);
            final LongList index = mockIndexWithEntries(locationsForFile(1, 50));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(1.0, stats.garbageFileStats()[0].deadToAliveRatio(), 1e-9);
        }

        @Test
        @DisplayName("Multiple files: each tracked independently")
        void multipleFilesTrackedIndependently() {
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 20), locationsForFile(2, 80));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file1, file2)).scan();

            // file1: 20 alive, 80 dead → d/a = 4.0
            assertEquals(4.0, stats.garbageFileStats()[0].deadToAliveRatio(), 1e-9);
            // file2: 80 alive, 20 dead → d/a = 0.25
            assertEquals(0.25, stats.garbageFileStats()[1].deadToAliveRatio(), 1e-9);
        }

        @Test
        @DisplayName("Multiple levels: stats grouped by file, not by level")
        void multipleLevels() {
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 3, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 90));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file1, file2)).scan();

            assertEquals(0, stats.garbageFileStats()[0].compactionLevel());
            assertEquals(9.0, stats.garbageFileStats()[0].deadToAliveRatio(), 1e-9);

            assertEquals(3, stats.garbageFileStats()[1].compactionLevel());
            assertEquals(10.0 / 90.0, stats.garbageFileStats()[1].deadToAliveRatio(), 1e-9);
        }
    }

    // ========================================================================
    // HDHM deduplication
    // ========================================================================

    @Nested
    @DisplayName("HDHM deduplication")
    class DeduplicationTests {

        @Test
        @DisplayName("Duplicate entries are deduplicated, preventing inflated alive counts")
        void duplicateEntriesAreDeduplicated() {
            // File 1: totalItems=5. After doubling, index has 8 entries (4 + 4 mirrored).
            // Only 2 unique alive entries → dead=3, alive=2, d/a = 1.5
            final DataFileReader file = mockFileReader(1, 0, 5, 1000);

            final LongList index = new LongListHeap(
                    DEFAULT_CONFIG.longListChunkSize(), 8, DEFAULT_CONFIG.longListReservedBufferSize());
            final long loc1 = DataFileCommon.dataLocation(1, 100);
            final long loc2 = DataFileCommon.dataLocation(1, 200);
            index.put(0, loc1);
            index.put(1, loc2);
            index.put(4, loc1); // duplicate of index[0]
            index.put(5, loc2); // duplicate of index[1]

            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

            final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "ObjectKeyToPath", true);

            final IndexedGarbageFileStats stats = scanner.scan();

            final GarbageFileStats fs = stats.garbageFileStats()[0];
            assertEquals(2, fs.aliveItems());
            assertEquals(3, fs.deadItems());
            assertEquals(1.5, fs.deadToAliveRatio(), 1e-9);
        }

        @Test
        @DisplayName("Non-duplicate mirrored entries are both counted")
        void sanitizedEntriesAreBothCounted() {
            // File 1: totalItems=4, all 4 entries distinct → alive=4, dead=0
            final DataFileReader file = mockFileReader(1, 0, 4, 1000);

            final LongList index = new LongListHeap(
                    DEFAULT_CONFIG.longListChunkSize(), 4, DEFAULT_CONFIG.longListReservedBufferSize());
            index.put(0, DataFileCommon.dataLocation(1, 100));
            index.put(1, DataFileCommon.dataLocation(1, 200));
            index.put(2, DataFileCommon.dataLocation(1, 300));
            index.put(3, DataFileCommon.dataLocation(1, 400));

            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

            final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "ObjectKeyToPath", true);

            final IndexedGarbageFileStats stats = scanner.scan();

            assertEquals(4, stats.garbageFileStats()[0].aliveItems());
            assertEquals(0, stats.garbageFileStats()[0].deadItems());
            assertEquals(0.0, stats.garbageFileStats()[0].deadToAliveRatio());
        }
    }

    // ========================================================================
    // garbageRatio (used for size estimation)
    // ========================================================================

    @Nested
    @DisplayName("garbageRatio computation")
    class GarbageRatioTests {

        @Test
        @DisplayName("Normal garbage ratio")
        void normalGarbageRatio() {
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);
            final LongList index = mockIndexWithEntries(locationsForFile(1, 40));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(0.6, stats.garbageFileStats()[0].garbageRatio(), 1e-9);
        }

        @Test
        @DisplayName("Empty file → garbageRatio = 1.0")
        void emptyFileGarbageRatio() {
            final DataFileReader file = mockFileReader(1, 0, 0, 500);
            final LongList index = emptyIndex();

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file)).scan();

            assertEquals(1.0, stats.garbageFileStats()[0].garbageRatio());
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty file collection produces empty stats array")
        void emptyFileCollection() {
            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of());

            final LongList index = emptyIndex();

            final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "test");

            final IndexedGarbageFileStats stats = scanner.scan();
            assertEquals(0, stats.garbageFileStats().length);
        }

        @Test
        @DisplayName("Index entries pointing to deleted files are silently skipped")
        void indexEntriesForDeletedFilesAreSkipped() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

            // Index has entries for file 1 AND file 99 (not in collection)
            final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(99, 3));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file1)).scan();

            // file1: 5 alive (entries for file 99 skipped silently)
            assertEquals(5, stats.garbageFileStats()[0].aliveItems());
        }

        @Test
        @DisplayName("Non-contiguous file indices produce sparse array with null gaps")
        void sparseFileIndices() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);
            final DataFileReader file5 = mockFileReader(5, 0, 10, 100);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 3), locationsForFile(5, 7));

            final IndexedGarbageFileStats stats =
                    createScanner(index, List.of(file1, file5)).scan();

            // Array size = 5 - 1 + 1 = 5, offset = 1
            assertEquals(5, stats.garbageFileStats().length);
            assertEquals(1, stats.offset());

            // file1 at array[0], file5 at array[4], gaps are null
            assertNotNull(stats.garbageFileStats()[0]);
            assertEquals(3, stats.garbageFileStats()[0].aliveItems());
            assertTrue(stats.garbageFileStats()[1] == null);
            assertTrue(stats.garbageFileStats()[2] == null);
            assertTrue(stats.garbageFileStats()[3] == null);
            assertNotNull(stats.garbageFileStats()[4]);
            assertEquals(7, stats.garbageFileStats()[4].aliveItems());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static GarbageScanner createScanner(final LongList index, final List<DataFileReader> files) {
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);
        return new GarbageScanner(index, fileCollection, "test");
    }

    private static LongList emptyIndex() {
        return new LongListHeap(DEFAULT_CONFIG.longListChunkSize(), 1, DEFAULT_CONFIG.longListReservedBufferSize());
    }

    private static LongList mockIndexWithEntries(final long[]... blocks) {
        long totalEntries = 0;
        for (final long[] block : blocks) {
            totalEntries += block.length;
        }

        final LongList index = new LongListHeap(
                DEFAULT_CONFIG.longListChunkSize(),
                Math.max(1, totalEntries),
                DEFAULT_CONFIG.longListReservedBufferSize());

        long key = 0;
        for (final long[] block : blocks) {
            for (final long location : block) {
                index.put(key++, location);
            }
        }
        return index;
    }

    private static long[] locationsForFile(final int fileIndex, final int aliveItems) {
        final long[] locations = new long[aliveItems];
        for (int i = 0; i < aliveItems; i++) {
            locations[i] = DataFileCommon.dataLocation(fileIndex, i);
        }
        return locations;
    }

    private static DataFileReader mockFileReader(
            final int fileIndex, final int level, final long totalItems, final long sizeBytes) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getCompactionLevel()).thenReturn(level);
        when(metadata.getItemsCount()).thenReturn(totalItems);

        final DataFileReader reader = mock(DataFileReader.class);
        when(reader.getIndex()).thenReturn(fileIndex);
        when(reader.getMetadata()).thenReturn(metadata);
        when(reader.getSize()).thenReturn(sizeBytes);
        return reader;
    }
}
