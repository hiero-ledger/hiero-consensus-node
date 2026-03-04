// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GarbageScannerEvaluateCandidatesTest {

    @Test
    void levelExceedingMaxThresholdTriggersAndIncludesAllAboveMin() {
        final DataFileReader file1 = mockFileReader(1, 0);
        final DataFileReader file2 = mockFileReader(2, 0);
        final DataFileReader file3 = mockFileReader(3, 0);

        final Map<Integer, GarbageScannerTask.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5),
                2, stats(2, 0, 10, 7),
                3, stats(3, 0, 10, 9));

        final Map<Integer, List<DataFileReader>> result =
                GarbageScannerTask.evaluateCompactionCandidates(scanResult, List.of(file1, file2, file3), 0.2, 0.4);

        assertEquals(1, result.size());
        assertEquals(List.of(file1, file2), result.get(0));
    }

    @Test
    void levelBelowMaxThresholdDoesNotTriggerCompaction() {
        final DataFileReader file1 = mockFileReader(1, 1);
        final DataFileReader file2 = mockFileReader(2, 1);

        final Map<Integer, GarbageScannerTask.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 1, 100, 70),
                2, stats(2, 1, 100, 75));

        final Map<Integer, List<DataFileReader>> result =
                GarbageScannerTask.evaluateCompactionCandidates(scanResult, List.of(file1, file2), 0.2, 0.4);

        assertTrue(result.isEmpty());
    }

    @Test
    void multipleEligibleLevelsAreReturned() {
        final DataFileReader level0File1 = mockFileReader(1, 0);
        final DataFileReader level0File2 = mockFileReader(2, 0);
        final DataFileReader level2File1 = mockFileReader(3, 2);
        final DataFileReader level2File2 = mockFileReader(4, 2);

        final Map<Integer, GarbageScannerTask.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5),
                2, stats(2, 0, 10, 7),
                3, stats(3, 2, 10, 5),
                4, stats(4, 2, 10, 7));

        final Map<Integer, List<DataFileReader>> result = GarbageScannerTask.evaluateCompactionCandidates(
                scanResult, List.of(level0File1, level0File2, level2File1, level2File2), 0.2, 0.4);

        assertEquals(2, result.size());
        assertEquals(List.of(level0File1, level0File2), result.get(0));
        assertEquals(List.of(level2File1, level2File2), result.get(2));
    }

    @Test
    void filesBelowMinThresholdAreExcludedFromEligibleLevel() {
        final DataFileReader triggeringFile = mockFileReader(1, 0);
        final DataFileReader belowMinFile = mockFileReader(2, 0);

        final Map<Integer, GarbageScannerTask.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 0, 10, 5),
                2, stats(2, 0, 100, 82));

        final Map<Integer, List<DataFileReader>> result = GarbageScannerTask.evaluateCompactionCandidates(
                scanResult, List.of(triggeringFile, belowMinFile), 0.2, 0.4);

        assertEquals(1, result.size());
        assertEquals(List.of(triggeringFile), result.get(0));
        assertFalse(result.get(0).contains(belowMinFile));
    }

    @Test
    void edgeCasesZeroTotalItemsBoundaryAndEmptyFileList() {
        final DataFileReader zeroTotal = mockFileReader(1, 1);
        final DataFileReader boundaryFile = mockFileReader(2, 1);

        final Map<Integer, GarbageScannerTask.GarbageFileStats> scanResult = Map.of(
                1, stats(1, 1, 0, 0),
                2, stats(2, 1, 10, 6));

        final Map<Integer, List<DataFileReader>> result =
                GarbageScannerTask.evaluateCompactionCandidates(scanResult, List.of(zeroTotal, boundaryFile), 0.2, 0.4);

        assertTrue(result.isEmpty());

        final Map<Integer, List<DataFileReader>> emptyFilesResult =
                GarbageScannerTask.evaluateCompactionCandidates(scanResult, List.of(), 0.2, 0.4);
        assertTrue(emptyFilesResult.isEmpty());
    }

    private static GarbageScannerTask.GarbageFileStats stats(
            final int fileIndex, final int level, final long totalItems, final long aliveItems) {
        return new GarbageScannerTask.GarbageFileStats(fileIndex, level, totalItems, aliveItems);
    }

    private static DataFileReader mockFileReader(final int fileIndex, final int level) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getCompactionLevel()).thenReturn(level);

        final DataFileReader reader = mock(DataFileReader.class);
        when(reader.getIndex()).thenReturn(fileIndex);
        when(reader.getMetadata()).thenReturn(metadata);
        return reader;
    }
}
