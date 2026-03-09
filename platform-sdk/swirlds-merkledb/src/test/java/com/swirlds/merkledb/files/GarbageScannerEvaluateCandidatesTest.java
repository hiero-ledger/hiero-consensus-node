// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GarbageScannerEvaluateCandidatesTest {

    @Test
    void filesExceedingThresholdAreIncluded() {
        final DataFileReader file1 = mockFileReader(1, 0);
        final DataFileReader file2 = mockFileReader(2, 0);
        final DataFileReader file3 = mockFileReader(3, 0);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5), // ratio 0.5
                2, stats(2, 0, 10, 7), // ratio 0.3
                3, stats(3, 0, 10, 9)); // ratio 0.1

        final Map<Integer, List<DataFileReader>> result =
                GarbageScanner.evaluateCompactionCandidates(scanResult, List.of(file1, file2, file3), 0.4, 0);

        assertEquals(1, result.size());
        assertEquals(List.of(file1), result.get(0));
    }

    @Test
    void filesAllBelowThresholdProducesEmptyResult() {
        final DataFileReader file1 = mockFileReader(1, 1);
        final DataFileReader file2 = mockFileReader(2, 1);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 1, 100, 70),
                2, stats(2, 1, 100, 75));

        final Map<Integer, List<DataFileReader>> result =
                GarbageScanner.evaluateCompactionCandidates(scanResult, List.of(file1, file2), 0.4, 0);

        assertTrue(result.isEmpty());
    }

    @Test
    void multipleLevelsCanHaveCompactionCandidates() {
        final DataFileReader level0File1 = mockFileReader(1, 0);
        final DataFileReader level0File2 = mockFileReader(2, 0);
        final DataFileReader level2File1 = mockFileReader(3, 2);
        final DataFileReader level2File2 = mockFileReader(4, 2);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5), // ratio 0.5
                2, stats(2, 0, 10, 4), // ratio 0.6
                3, stats(3, 2, 10, 5), // ratio 0.5
                4, stats(4, 2, 10, 4)); // ratio 0.6

        final Map<Integer, List<DataFileReader>> result = GarbageScanner.evaluateCompactionCandidates(
                scanResult, List.of(level0File1, level0File2, level2File1, level2File2), 0.3, 0);

        assertEquals(2, result.size());
        assertEquals(List.of(level0File1, level0File2), result.get(0));
        assertEquals(List.of(level2File1, level2File2), result.get(2));
    }

    @Test
    void zeroTotalItemsAndBoundaryRatios() {
        final DataFileReader zeroTotal = mockFileReader(1, 1);
        final DataFileReader exactlyOnThreshold = mockFileReader(2, 1);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 1, 0, 0), // ratio 1.0
                2, stats(2, 1, 4, 3)); // ratio 1 - 3/4 = 0.25

        final Map<Integer, List<DataFileReader>> result = GarbageScanner.evaluateCompactionCandidates(
                scanResult, List.of(zeroTotal, exactlyOnThreshold), 0.25, 0);

        assertEquals(1, result.size());
        assertEquals(List.of(zeroTotal), result.get(1));

        final Map<Integer, List<DataFileReader>> emptyFilesResult =
                GarbageScanner.evaluateCompactionCandidates(scanResult, List.of(), 0.25, 0);
        assertTrue(emptyFilesResult.isEmpty());
    }

    @Test
    void compactionCandidatesAreLimitedByTotalSizePerLevel() {
        final DataFileReader file1 = mockFileReader(1, 0, 600 * 1024L);
        final DataFileReader file2 = mockFileReader(2, 0, 600 * 1024L);
        final DataFileReader file3 = mockFileReader(3, 0, 400 * 1024L);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5),
                2, stats(2, 0, 10, 5),
                3, stats(3, 0, 10, 5));

        final Map<Integer, List<DataFileReader>> result =
                GarbageScanner.evaluateCompactionCandidates(scanResult, List.of(file1, file2, file3), 0.1, 1024);

        assertEquals(1, result.size());
        assertEquals(List.of(file1, file3), result.get(0));
    }

    @Test
    void firstEligibleFileIsSelectedEvenWhenItsSizeExceedsLimit() {
        final DataFileReader oversizedEligible = mockFileReader(1, 0, 2 * 1024L * 1024L);
        final DataFileReader secondEligible = mockFileReader(2, 0, 100 * 1024L);

        final Map<Integer, GarbageScanner.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5),
                2, stats(2, 0, 10, 5));

        final Map<Integer, List<DataFileReader>> result = GarbageScanner.evaluateCompactionCandidates(
                scanResult, List.of(oversizedEligible, secondEligible), 0.1, 1024);

        assertEquals(1, result.size());
        assertEquals(List.of(oversizedEligible), result.get(0));
    }

    private static GarbageScanner.GarbageFileStats stats(
            final int fileIndex, final int level, final long totalItems, final long aliveItems) {
        return new GarbageScanner.GarbageFileStats(fileIndex, level, totalItems, aliveItems);
    }

    private static DataFileReader mockFileReader(final int fileIndex, final int level) {
        return mockFileReader(fileIndex, level, 0);
    }

    private static DataFileReader mockFileReader(final int fileIndex, final int level, final long sizeBytes) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getCompactionLevel()).thenReturn(level);

        final DataFileReader reader = mock(DataFileReader.class);
        when(reader.getIndex()).thenReturn(fileIndex);
        when(reader.getMetadata()).thenReturn(metadata);
        when(reader.getSize()).thenReturn(sizeBytes);
        return reader;
    }
}
