// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.dataLocation;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
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

        // gcRateThreshold=1.0 → all three files have dead/alive > 1.0, so all selected
        final GarbageScanner task = createTask(indexWithEntries(indexEntries), List.of(file1, file2, file3));

        final GarbageScanner.ScanResult result = task.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(2, filesToCompact.size());
        assertEquals(List.of(file1, file2), filesToCompact.get(0));
        assertEquals(List.of(file3), filesToCompact.get(1));
    }

    @Test
    void scanWithEmptyIndexReportsNoAliveItems() {
        // All items dead → dead/alive = MAX_VALUE → always selected
        final DataFileReader file1 = mockFileReader(1, 0, 5);
        final DataFileReader file2 = mockFileReader(2, 1, 9);

        final GarbageScanner task = createTask(indexWithEntries(new LinkedHashMap<>()), List.of(file1, file2));

        final GarbageScanner.ScanResult result = task.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(2, filesToCompact.size());
        assertEquals(List.of(file1), filesToCompact.get(0));
        assertEquals(List.of(file2), filesToCompact.get(1));
    }

    @Test
    void scanAllIndexEntriesPointToSingleFile() {
        // file2: 8 alive out of 8 total → 0 dead → dead/alive = 0.0 → not selected
        // file1, file3: 0 alive out of 8 → dead/alive = MAX_VALUE → selected
        final DataFileReader file1 = mockFileReader(1, 0, 8);
        final DataFileReader file2 = mockFileReader(2, 0, 8);
        final DataFileReader file3 = mockFileReader(3, 0, 8);

        final Map<Long, Long> indexEntries = new LinkedHashMap<>();
        for (long key = 0; key < 8; key++) {
            indexEntries.put(key, dataLocation(2, key + 1));
        }

        final GarbageScanner task = createTask(indexWithEntries(indexEntries), List.of(file1, file2, file3));

        final GarbageScanner.ScanResult result = task.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(1, filesToCompact.size());
        assertEquals(List.of(file1, file3), filesToCompact.get(0));
    }

    private static GarbageScanner createTask(final LongList index, final List<DataFileReader> files) {
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);
        // gcRateThreshold=1.0 → selects files with dead/alive > 1.0 (more dead than alive)
        return new GarbageScanner(index, fileCollection, "test-store", config(1.0, 0, 5));
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

    private static MerkleDbConfig config(
            final double gcRateThreshold, final long maxCompactedFileSizeInKB, final int maxCompactionLevel) {
        return new MerkleDbConfig(
                DEFAULT_CONFIG.initialCapacity(),
                DEFAULT_CONFIG.maxNumOfKeys(),
                DEFAULT_CONFIG.hashesRamToDiskThreshold(),
                DEFAULT_CONFIG.hashStoreRamBufferSize(),
                DEFAULT_CONFIG.hashChunkCacheThreshold(),
                DEFAULT_CONFIG.hashStoreRamOffHeapBuffers(),
                DEFAULT_CONFIG.longListChunkSize(),
                DEFAULT_CONFIG.longListReservedBufferSize(),
                DEFAULT_CONFIG.compactionThreads(),
                gcRateThreshold,
                maxCompactedFileSizeInKB,
                maxCompactionLevel,
                DEFAULT_CONFIG.iteratorInputBufferBytes(),
                DEFAULT_CONFIG.reconnectKeyLeakMitigationEnabled(),
                DEFAULT_CONFIG.indexRebuildingEnforced(),
                DEFAULT_CONFIG.goodAverageBucketEntryCount(),
                DEFAULT_CONFIG.tablesToRepairHdhm(),
                DEFAULT_CONFIG.percentHalfDiskHashMapFlushThreads(),
                DEFAULT_CONFIG.numHalfDiskHashMapFlushThreads(),
                DEFAULT_CONFIG.leafRecordCacheSize(),
                DEFAULT_CONFIG.maxFileChannelsPerFileReader(),
                DEFAULT_CONFIG.maxThreadsPerFileChannel(),
                DEFAULT_CONFIG.useDiskIndices());
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
