// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.GarbageScanner.ScanResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GarbageScannerEvaluateCandidatesTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    @Test
    void filesExceedingThresholdAreIncluded() {
        final DataFileReader file1 = mockFileReader(1, 0, 10, 100);
        final DataFileReader file2 = mockFileReader(2, 0, 10, 100);
        final DataFileReader file3 = mockFileReader(3, 0, 10, 100);

        final LongList index =
                mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 7), locationsForFile(3, 9));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1, file2, file3));

        final MerkleDbConfig config = config(0.4, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "HashStoreDisk", config);

        final ScanResult result = scanner.scan();

        assertEquals(1, result.candidatesByLevel().size());
        assertEquals(List.of(file1), result.candidatesByLevel().get(0));
    }

    @Test
    void filesAllBelowThresholdProducesEmptyResult() {
        final DataFileReader file1 = mockFileReader(1, 1, 100, 100);
        final DataFileReader file2 = mockFileReader(2, 1, 100, 100);

        final LongList index = mockIndexWithEntries(locationsForFile(1, 70), locationsForFile(2, 75));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1, file2));

        final MerkleDbConfig config = config(0.31, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "PathToKeyValue", config);

        final ScanResult result = scanner.scan();

        assertTrue(result.candidatesByLevel().isEmpty());
    }

    @Test
    void multipleLevelsCanHaveCompactionCandidates() {
        final DataFileReader level0File1 = mockFileReader(1, 0, 10, 100);
        final DataFileReader level0File2 = mockFileReader(2, 0, 10, 100);
        final DataFileReader level2File1 = mockFileReader(3, 2, 10, 100);
        final DataFileReader level2File2 = mockFileReader(4, 2, 10, 100);

        final LongList index = mockIndexWithEntries(
                locationsForFile(1, 5), locationsForFile(2, 4), locationsForFile(3, 5), locationsForFile(4, 4));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles())
                .thenReturn(List.of(level0File1, level0File2, level2File1, level2File2));

        final MerkleDbConfig config = config(0.5, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "HashStoreDisk", config);

        final ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(2, filesToCompact.size());
        assertEquals(List.of(level0File2), filesToCompact.get(0));
        assertEquals(List.of(level2File2), filesToCompact.get(2));
    }

    @Test
    void zeroTotalItemsAndBoundaryRatios() {
        final DataFileReader zeroTotal = mockFileReader(1, 1, 0, 100);
        final DataFileReader exactlyOnThreshold = mockFileReader(2, 1, 4, 100);

        final LongList index = mockIndexWithEntries(locationsForFile(2, 3));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(zeroTotal, exactlyOnThreshold));

        final MerkleDbConfig config = config(0.25, 0, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "PathToKeyValue", config);

        final ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(1, filesToCompact.size());
        assertEquals(List.of(zeroTotal), filesToCompact.get(1));
    }

    @Test
    void compactionCandidatesAreLimitedByTotalSizePerLevel() {
        final DataFileReader file1 = mockFileReader(1, 0, 10, 600 * KIBIBYTES_TO_BYTES);
        final DataFileReader file2 = mockFileReader(2, 0, 10, 600 * KIBIBYTES_TO_BYTES);
        final DataFileReader file3 = mockFileReader(3, 0, 10, 400 * KIBIBYTES_TO_BYTES);

        final LongList index =
                mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 5), locationsForFile(3, 5));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(file1, file2, file3));

        final MerkleDbConfig config = config(0.6, 1024, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "ObjectKeyToPath", config);

        final ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertTrue(filesToCompact.isEmpty());
    }

    @Test
    void firstEligibleFileIsSelectedEvenWhenItsSizeExceedsLimit() {
        final DataFileReader oversizedEligible = mockFileReader(1, 0, 10, 2 * 1024L * KIBIBYTES_TO_BYTES);
        final DataFileReader secondEligible = mockFileReader(2, 0, 10, 100 * KIBIBYTES_TO_BYTES);

        final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 5));

        final DataFileCollection dataFileCollection = mock(DataFileCollection.class);
        when(dataFileCollection.getAllCompletedFiles()).thenReturn(List.of(oversizedEligible, secondEligible));

        final MerkleDbConfig config = config(0.4, 1024, 5);
        final GarbageScanner scanner = new GarbageScanner(index, dataFileCollection, "HashStoreDisk", config);

        final ScanResult result = scanner.scan();
        final Map<Integer, List<DataFileReader>> filesToCompact = result.candidatesByLevel();

        assertEquals(1, filesToCompact.size());
        assertEquals(1, filesToCompact.get(0).size());
        assertTrue(filesToCompact.get(0).contains(oversizedEligible)
                || filesToCompact.get(0).contains(secondEligible));
        assertFalse(filesToCompact.get(0).contains(oversizedEligible)
                && filesToCompact.get(0).contains(secondEligible));
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
