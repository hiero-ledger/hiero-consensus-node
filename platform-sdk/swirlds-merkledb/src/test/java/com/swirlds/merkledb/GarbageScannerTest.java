// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.files.DataFileCommon.dataLocation;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.GarbageScanner.IndexedGarbageFileStats;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.DataFileReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GarbageScannerTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    @Test
    void scanTracksAliveItemsAcrossMultipleFiles() {
        final DataFileReader file1 = mockFileReader(1, 0, 10);
        final DataFileReader file2 = mockFileReader(2, 0, 10);
        final DataFileReader file3 = mockFileReader(3, 1, 10);

        // file1: 3 alive, 7 dead → dead/alive = 2.33
        // file2: 2 alive, 8 dead → dead/alive = 4.0
        // file3: 1 alive, 9 dead → dead/alive = 9.0
        final Map<Long, Long> indexEntries = new LinkedHashMap<>();
        indexEntries.put(0L, dataLocation(1, 1));
        indexEntries.put(1L, dataLocation(2, 1));
        indexEntries.put(2L, dataLocation(2, 2));
        indexEntries.put(3L, dataLocation(3, 1));
        indexEntries.put(4L, dataLocation(1, 2));
        indexEntries.put(5L, dataLocation(1, 3));

        final GarbageScanner scanner = createScanner(indexWithEntries(indexEntries), List.of(file1, file2, file3));

        final IndexedGarbageFileStats stats = scanner.scan();

        // file1 at index 1, offset is 1 → array[0]
        final GarbageScanner.GarbageFileStats f1Stats = stats.garbageFileStats()[0];
        assertNotNull(f1Stats);
        assertEquals(3, f1Stats.aliveItems());
        assertEquals(7, f1Stats.deadItems());

        // file2 at index 2, offset is 1 → array[1]
        final GarbageScanner.GarbageFileStats f2Stats = stats.garbageFileStats()[1];
        assertNotNull(f2Stats);
        assertEquals(2, f2Stats.aliveItems());
        assertEquals(8, f2Stats.deadItems());

        // file3 at index 3, offset is 1 → array[2]
        final GarbageScanner.GarbageFileStats f3Stats = stats.garbageFileStats()[2];
        assertNotNull(f3Stats);
        assertEquals(1, f3Stats.aliveItems());
        assertEquals(9, f3Stats.deadItems());
    }

    @Test
    void scanWithEmptyIndexReportsNoAliveItems() {
        final DataFileReader file1 = mockFileReader(1, 0, 5);
        final DataFileReader file2 = mockFileReader(2, 1, 9);

        final GarbageScanner scanner = createScanner(indexWithEntries(new LinkedHashMap<>()), List.of(file1, file2));

        final IndexedGarbageFileStats stats = scanner.scan();

        // file1: 0 alive, 5 dead
        final GarbageScanner.GarbageFileStats f1Stats = stats.garbageFileStats()[0];
        assertNotNull(f1Stats);
        assertEquals(0, f1Stats.aliveItems());
        assertEquals(5, f1Stats.deadItems());

        // file2: 0 alive, 9 dead
        final GarbageScanner.GarbageFileStats f2Stats = stats.garbageFileStats()[1];
        assertNotNull(f2Stats);
        assertEquals(0, f2Stats.aliveItems());
        assertEquals(9, f2Stats.deadItems());
    }

    @Test
    void scanAllIndexEntriesPointToSingleFile() {
        // file2: 8 alive out of 8 total → 0 dead
        // file1, file3: 0 alive → all dead
        final DataFileReader file1 = mockFileReader(1, 0, 8);
        final DataFileReader file2 = mockFileReader(2, 0, 8);
        final DataFileReader file3 = mockFileReader(3, 0, 8);

        final Map<Long, Long> indexEntries = new LinkedHashMap<>();
        for (long key = 0; key < 8; key++) {
            indexEntries.put(key, dataLocation(2, key + 1));
        }

        final GarbageScanner scanner = createScanner(indexWithEntries(indexEntries), List.of(file1, file2, file3));

        final IndexedGarbageFileStats stats = scanner.scan();

        // file1: 0 alive, 8 dead
        assertEquals(0, stats.garbageFileStats()[0].aliveItems());
        assertEquals(8, stats.garbageFileStats()[0].deadItems());

        // file2: 8 alive, 0 dead
        assertEquals(8, stats.garbageFileStats()[1].aliveItems());
        assertEquals(0, stats.garbageFileStats()[1].deadItems());

        // file3: 0 alive, 8 dead
        assertEquals(0, stats.garbageFileStats()[2].aliveItems());
        assertEquals(8, stats.garbageFileStats()[2].deadItems());
    }

    @Test
    void scanEmptyFileCollection() {
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of());

        final LongList index =
                new LongListHeap(DEFAULT_CONFIG.longListChunkSize(), 1, DEFAULT_CONFIG.longListReservedBufferSize());

        final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "test-store");

        final IndexedGarbageFileStats stats = scanner.scan();
        assertEquals(0, stats.garbageFileStats().length);
    }

    @Test
    void scanIndexEntriesForDeletedFilesAreSkipped() {
        final DataFileReader file1 = mockFileReader(1, 0, 10);

        // Index has entries for file 1 AND file 99 (not in collection)
        final Map<Long, Long> indexEntries = new LinkedHashMap<>();
        for (long key = 0; key < 5; key++) {
            indexEntries.put(key, dataLocation(1, key + 1));
        }
        for (long key = 5; key < 8; key++) {
            indexEntries.put(key, dataLocation(99, key + 1));
        }

        final GarbageScanner scanner = createScanner(indexWithEntries(indexEntries), List.of(file1));

        final IndexedGarbageFileStats stats = scanner.scan();

        // file1: 5 alive (entries for file 99 are silently skipped)
        assertEquals(5, stats.garbageFileStats()[0].aliveItems());
        assertEquals(5, stats.garbageFileStats()[0].deadItems());
    }

    private static GarbageScanner createScanner(final LongList index, final List<DataFileReader> files) {
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);
        return new GarbageScanner(index, fileCollection, "test-store");
    }

    private static LongList indexWithEntries(final Map<Long, Long> indexEntries) {
        long maxKey = -1;
        for (final long key : indexEntries.keySet()) {
            maxKey = Math.max(maxKey, key);
        }

        final LongList index = new LongListHeap(
                DEFAULT_CONFIG.longListChunkSize(),
                Math.max(1, maxKey + 1),
                DEFAULT_CONFIG.longListReservedBufferSize());
        index.updateValidRange(0, Math.max(0, maxKey));

        for (final Map.Entry<Long, Long> entry : indexEntries.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        return index;
    }

    private static DataFileReader mockFileReader(final int fileIndex, final int level, final long totalItems) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getCompactionLevel()).thenReturn(level);
        when(metadata.getItemsCount()).thenReturn(totalItems);

        final DataFileReader fileReader = mock(DataFileReader.class);
        when(fileReader.getIndex()).thenReturn(fileIndex);
        when(fileReader.getMetadata()).thenReturn(metadata);
        return fileReader;
    }
}
