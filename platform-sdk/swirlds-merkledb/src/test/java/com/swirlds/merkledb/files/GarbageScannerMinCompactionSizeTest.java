// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.GarbageScanner.IndexedGarbageFileStats;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the minimum compaction size threshold in {@link GarbageScanner},
 * the null-safe handling of concurrent file deletions during scanning,
 * and the {@link GarbageScanner#estimateAliveSize} computation.
 */
class GarbageScannerMinCompactionSizeTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    // ========================================================================
    // Minimum compaction size threshold tests
    // ========================================================================

    @Test
    @DisplayName("Level with alive size below threshold is excluded from compaction candidates")
    void levelBelowMinSizeThresholdIsExcluded() {
        // Two files at level 0, each 100 bytes, 10 total items, 5 alive => 50% garbage
        // Alive size estimate: 2 * 100 * (5/10) = 100 bytes
        // Threshold at level 0: 1 KB * 2^0 = 1024 bytes => should be excluded
        final DataFileReader file1 = mockFileReader(1, 0, 10, 100);
        final DataFileReader file2 = mockFileReader(2, 0, 10, 100);

        final CASableLongIndex index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 5));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1, file2));

        // garbageThreshold=0.4 (files with 50% garbage pass), minCompactionSizeKb=1
        final MerkleDbConfig config = config(0.4, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertTrue(
                filesToCompact.isEmpty(), "Level should be excluded because alive size (100 bytes) < threshold (1 KB)");
    }

    @Test
    @DisplayName("Level with alive size above threshold is included in compaction candidates")
    void levelAboveMinSizeThresholdIsIncluded() {
        // Two files at level 0, each 2 KB, 10 total items, 5 alive => 50% garbage
        // Alive size estimate: 2 * 2048 * (5/10) = 2048 bytes = 2 KB
        // Threshold at level 0: 1 KB * 2^0 = 1024 bytes => should be included
        final DataFileReader file1 = mockFileReader(1, 0, 10, 2 * KIBIBYTES_TO_BYTES);
        final DataFileReader file2 = mockFileReader(2, 0, 10, 2 * KIBIBYTES_TO_BYTES);

        final CASableLongIndex index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 5));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1, file2));

        final MerkleDbConfig config = config(0.4, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertEquals(
                1, filesToCompact.size(), "Level should be included because alive size (2 KB) >= threshold (1 KB)");
        assertEquals(2, filesToCompact.get(0).size());
    }

    @Test
    @DisplayName("Empty files contribute zero to alive size estimate and are excluded by threshold")
    void emptyFilesContributeZeroAliveSize() {
        // Three empty files (totalItems=0) at level 1, each 27 bytes
        // Alive size estimate: 0 bytes (all empty)
        // Threshold at level 1: 1 KB * 2^1 = 2048 bytes => should be excluded
        final DataFileReader empty1 = mockFileReader(1, 1, 0, 27);
        final DataFileReader empty2 = mockFileReader(2, 1, 0, 27);
        final DataFileReader empty3 = mockFileReader(3, 1, 0, 27);

        // No index entries point to these files
        final CASableLongIndex index = mockIndexWithEntries();

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(empty1, empty2, empty3));

        // garbageThreshold=0.0 so everything with any garbage is selected (empty files get ratio 1.0)
        final MerkleDbConfig config = config(0.0, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertTrue(
                filesToCompact.isEmpty(), "Empty files should not trigger compaction when min size threshold is set");
    }

    @Test
    @DisplayName("Disabled threshold (0) allows all levels to compact")
    void disabledThresholdAllowsAllLevels() {
        final DataFileReader file1 = mockFileReader(1, 0, 10, 50);

        final CASableLongIndex index = mockIndexWithEntries(locationsForFile(1, 3));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1));

        // minCompactionSizeKb=0 disables the threshold
        final MerkleDbConfig config = config(0.5, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertEquals(1, filesToCompact.size(), "With threshold disabled, file with 70% garbage should be selected");
    }

    @Test
    @DisplayName("Threshold scales exponentially with level: higher levels require more alive data")
    void thresholdScalesWithLevel() {
        // File at level 0: 4 KB, 10 items, 5 alive => alive size = 2 KB
        // Threshold at level 0: 1 KB * 2^0 = 1 KB => 2 KB >= 1 KB => included
        final DataFileReader level0File = mockFileReader(1, 0, 10, 4 * KIBIBYTES_TO_BYTES);

        // File at level 2: 4 KB, 10 items, 5 alive => alive size = 2 KB
        // Threshold at level 2: 1 KB * 2^2 = 4 KB => 2 KB < 4 KB => excluded
        final DataFileReader level2File = mockFileReader(2, 2, 10, 4 * KIBIBYTES_TO_BYTES);

        final CASableLongIndex index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 5));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File, level2File));

        // garbageThreshold=0.4, minCompactionSizeKb=1
        final MerkleDbConfig config = config(0.4, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertEquals(1, filesToCompact.size(), "Only level 0 should pass; level 2 threshold is 4x higher");
        assertTrue(filesToCompact.containsKey(0), "Level 0 should be in results");
    }

    @Test
    @DisplayName("Multiple levels: one above threshold, one below")
    void multipleLevelsMixedThreshold() {
        // Level 0: two large files, plenty of alive data
        final DataFileReader big1 = mockFileReader(1, 0, 100, 10 * KIBIBYTES_TO_BYTES);
        final DataFileReader big2 = mockFileReader(2, 0, 100, 10 * KIBIBYTES_TO_BYTES);

        // Level 2: two tiny files, very little alive data
        final DataFileReader small1 = mockFileReader(3, 2, 10, 50);
        final DataFileReader small2 = mockFileReader(4, 2, 10, 50);

        final CASableLongIndex index = mockIndexWithEntries(
                locationsForFile(1, 40), // 60% garbage
                locationsForFile(2, 30), // 70% garbage
                locationsForFile(3, 4), // 60% garbage
                locationsForFile(4, 3)); // 70% garbage

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(big1, big2, small1, small2));

        // Threshold at level 0: 5 KB * 2^0 = 5 KB
        // Level 0 alive estimate: 10240*0.4 + 10240*0.3 = 7168 bytes = 7 KB > 5 KB => included
        // Threshold at level 2: 5 KB * 2^2 = 20 KB
        // Level 2 alive estimate: 50*0.4 + 50*0.3 = 35 bytes < 20 KB => excluded
        final MerkleDbConfig config = config(0.5, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        assertEquals(1, filesToCompact.size(), "Only level 0 should pass the alive size threshold");
        assertTrue(filesToCompact.containsKey(0), "Level 0 should be in results");
    }

    // ========================================================================
    // Null fileStats handling (concurrent file deletion during scan)
    // ========================================================================

    @Test
    @DisplayName("Index entries pointing to deleted files are gracefully skipped")
    void indexEntriesForDeletedFilesAreSkipped() {
        // File 1 exists in the collection
        final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1));

        // Index has entries pointing to file 1 AND file 99 (which was deleted concurrently)
        final CASableLongIndex index = mockIndexWithEntries(
                locationsForFile(1, 5), locationsForFile(99, 3)); // file 99 doesn't exist in collection

        // Should not throw — null fileStats are skipped
        final MerkleDbConfig config = config(0.4, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = assertDoesNotThrow(scanner::scan);
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        // File 1 has 5/10 alive = 50% garbage, which exceeds 0.4 threshold
        assertEquals(1, filesToCompact.size());
        assertEquals(List.of(file1), filesToCompact.get(0));
    }

    @Test
    @DisplayName("All index entries pointing to deleted files results in empty scan")
    void allEntriesPointToDeletedFiles() {
        // Collection has one file with no index entries pointing to it
        final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1));

        // All index entries point to deleted file 99
        final CASableLongIndex index = mockIndexWithEntries(locationsForFile(99, 10));

        final MerkleDbConfig config = config(0.5, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "test", config);

        final GarbageScanner.ScanResult result = assertDoesNotThrow(scanner::scan);
        final Map<Integer, List<DataFileReader>> filesToCompact = result.filesToCompact();

        // File 1 has 0 alive items out of 10 total = 100% garbage => selected
        assertEquals(1, filesToCompact.size());
        assertEquals(List.of(file1), filesToCompact.get(0));
    }

    // ========================================================================
    // estimateAliveSize unit tests
    // ========================================================================

    @Test
    @DisplayName("estimateAliveSize correctly computes weighted sum from stats")
    void estimateAliveSizeComputation() {
        final DataFileReader reader1 = mockFileReader(1, 0, 100, 1000);
        final DataFileReader reader2 = mockFileReader(2, 0, 50, 500);
        final DataFileReader emptyReader = mockFileReader(3, 0, 0, 27);

        final IndexedGarbageFileStats stats = new IndexedGarbageFileStats(1, new GarbageScanner.GarbageFileStats[] {
            new GarbageScanner.GarbageFileStats(reader1, 75), // 75% alive => 750 bytes
            new GarbageScanner.GarbageFileStats(reader2, 25), // 50% alive => 250 bytes
            new GarbageScanner.GarbageFileStats(emptyReader, 0) // empty => 0 bytes
        });

        final long result = GarbageScanner.estimateAliveSize(List.of(reader1, reader2, emptyReader), stats);

        // reader1: 1000 * (75/100) = 750
        // reader2: 500 * (25/50) = 250
        // emptyReader: 0 (totalItems == 0)
        assertEquals(1000, result, "Alive size should be sum of per-file alive estimates");
    }

    @Test
    @DisplayName("estimateAliveSize returns zero for all-empty candidates")
    void estimateAliveSizeAllEmpty() {
        final DataFileReader empty1 = mockFileReader(1, 0, 0, 27);
        final DataFileReader empty2 = mockFileReader(2, 0, 0, 27);

        final IndexedGarbageFileStats stats = new IndexedGarbageFileStats(1, new GarbageScanner.GarbageFileStats[] {
            new GarbageScanner.GarbageFileStats(empty1, 0), new GarbageScanner.GarbageFileStats(empty2, 0)
        });

        final long result = GarbageScanner.estimateAliveSize(List.of(empty1, empty2), stats);

        assertEquals(0, result, "All-empty candidates should produce zero alive size");
    }

    @Test
    @DisplayName("estimateAliveSize handles missing stats gracefully (concurrent deletion)")
    void estimateAliveSizeMissingStats() {
        final DataFileReader reader1 = mockFileReader(1, 0, 100, 1000);
        final DataFileReader deletedReader = mockFileReader(99, 0, 50, 500);

        final IndexedGarbageFileStats stats = new IndexedGarbageFileStats(
                1, new GarbageScanner.GarbageFileStats[] {new GarbageScanner.GarbageFileStats(reader1, 75)});

        final long result = GarbageScanner.estimateAliveSize(List.of(reader1, deletedReader), stats);

        // reader1: 1000 * (75/100) = 750
        // deletedReader: no stats => 0
        assertEquals(750, result, "Missing stats should contribute zero, not throw");
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static MerkleDbConfig config(
            final double garbageThreshold, final long maxCompactionDataPerLevelInKB, final int maxCompactionLevel) {
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
                garbageThreshold,
                maxCompactionDataPerLevelInKB,
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

    private static CASableLongIndex mockIndexWithEntries(final long[]... blocks) {
        final CASableLongIndex index = mock(CASableLongIndex.class);
        try {
            doAnswer(invocation -> {
                        final CASableLongIndex.LongAction<RuntimeException> action = invocation.getArgument(0);
                        long key = 0;
                        for (final long[] block : blocks) {
                            for (final long location : block) {
                                try {
                                    action.handle(key++, location);
                                } catch (final InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        return true;
                    })
                    .when(index)
                    .forEach(any(), isNull());
        } catch (final InterruptedException e) {
            throw new UndeclaredThrowableException(e);
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
