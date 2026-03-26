// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GarbageScannerDeadAliveRatioTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    // ========================================================================
    // Phase 1: Selection by dead/alive ratio
    // ========================================================================

    @Nested
    @DisplayName("Phase 1 — Selection by dead/alive ratio")
    class Phase1Tests {

        @Test
        @DisplayName("Files above gcRateThreshold are selected")
        void filesAboveThresholdAreSelected() {
            // File 1: 30 alive / 70 dead → dead/alive = 2.33 → selected (above 1.0)
            // File 2: 80 alive / 20 dead → dead/alive = 0.25 → NOT selected (below 1.0)
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 30), locationsForFile(2, 80));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(file1));
            assertFalse(result.candidatesByLevel().get(0).contains(file2));
        }

        @Test
        @DisplayName("All files below threshold → empty result")
        void allFilesBelowThresholdProducesEmptyResult() {
            // File 1: 90 alive / 10 dead → dead/alive = 0.11
            // File 2: 80 alive / 20 dead → dead/alive = 0.25
            // Threshold: 0.5 → neither selected
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 90), locationsForFile(2, 80));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 0.5, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("File with totalItems == 0 has deadToAliveRatio == MAX_VALUE → always selected")
        void emptyFileAlwaysSelected() {
            final DataFileReader emptyFile = mockFileReader(1, 0, 0, 500);

            final LongList index = emptyIndex();

            final GarbageScanner scanner = createScanner(index, List.of(emptyFile), 0.5, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(emptyFile));
        }

        @Test
        @DisplayName("File with all dead items (aliveItems == 0) has deadToAliveRatio == MAX_VALUE → always selected")
        void allDeadFileAlwaysSelected() {
            // 100 total items, 0 alive → dead/alive = MAX_VALUE
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);

            final LongList index = emptyIndex();

            final GarbageScanner scanner = createScanner(index, List.of(file), 0.5, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(file));
        }

        @Test
        @DisplayName("File with no dead items (all alive) has deadToAliveRatio == 0.0 → never selected in phase 1")
        void allAliveFileNeverSelected() {
            // 50 total, 50 alive → dead/alive = 0.0
            final DataFileReader file = mockFileReader(1, 0, 50, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 50));

            final GarbageScanner scanner = createScanner(index, List.of(file), 0.01, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Multiple levels: eligible files are grouped correctly")
        void multipleLevelsGroupedCorrectly() {
            // Level 0: file1 (20 alive/80 dead, d/a=4.0), file2 (90 alive/10 dead, d/a=0.11)
            // Level 2: file3 (10 alive/90 dead, d/a=9.0), file4 (85 alive/15 dead, d/a=0.18)
            // Threshold: 1.0
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);
            final DataFileReader file3 = mockFileReader(3, 2, 100, 1000);
            final DataFileReader file4 = mockFileReader(4, 2, 100, 1000);

            final LongList index = mockIndexWithEntries(
                    locationsForFile(1, 20), locationsForFile(2, 90),
                    locationsForFile(3, 10), locationsForFile(4, 85));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2, file3, file4), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(2, result.candidatesByLevel().size());
            assertEquals(List.of(file1), result.candidatesByLevel().get(0));
            assertEquals(List.of(file3), result.candidatesByLevel().get(2));
        }
    }

    // ========================================================================
    // Phase 2: Absorption of remaining files
    // ========================================================================

    @Nested
    @DisplayName("Phase 2 — Absorb remaining files")
    class Phase2Tests {

        @Test
        @DisplayName("Clean files are absorbed when dirty files provide ratio budget")
        void cleanFilesAbsorbed() {
            // File 1 (dirty): 10 alive / 90 dead → d/a = 9.0 → selected in phase 1
            // File 2 (clean): 18 alive / 2 dead → d/a = 0.11 → NOT selected in phase 1
            //
            // Phase 1 aggregate: 90/10 = 9.0 (well above threshold 1.0)
            // After absorbing file 2: (90+2)/(10+18) = 92/28 = 3.29 (still above 1.0)
            // → file 2 should be absorbed
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader cleanFile = mockFileReader(2, 0, 20, 500);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 18));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, cleanFile), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);
            assertEquals(2, candidates.size());
            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(cleanFile));
        }

        @Test
        @DisplayName("Absorption skips files when aggregate ratio would drop below threshold")
        void absorptionSkipsAtRatioLimit() {
            // File 1 (dirty): 20 alive / 80 dead → d/a = 4.0 → selected
            // File 2 (moderate): 15 alive / 5 dead → d/a = 0.33 → NOT selected
            // File 3 (very clean): 19 alive / 1 dead → d/a = 0.053 → NOT selected
            //
            // gcRateThreshold: 2.0
            // Phase 1 aggregate: 80/20 = 4.0
            // Phase 2 sorts by d/a descending: moderateFile(0.33), veryCleanFile(0.053)
            // Absorb moderateFile: (80+5)/(20+15) = 85/35 = 2.43 → still > 2.0, absorb
            // Absorb veryCleanFile: (85+1)/(35+19) = 86/54 = 1.59 → below 2.0, SKIP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader moderateFile = mockFileReader(2, 0, 20, 400);
            final DataFileReader veryCleanFile = mockFileReader(3, 0, 20, 300);

            final LongList index =
                    mockIndexWithEntries(locationsForFile(1, 20), locationsForFile(2, 15), locationsForFile(3, 19));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile, moderateFile, veryCleanFile), 2.0, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(moderateFile));
            assertFalse(candidates.contains(veryCleanFile));
        }

        @Test
        @DisplayName("Absorption skips files when projected output size would exceed cap")
        void absorptionSkipsAtSizeCap() {
            // File 1 (dirty): 100 items, 10 alive, size=10KB → projected alive = 1KB → selected
            // File 2 (clean): 100 items, 90 alive, size=2KB → projected alive = 1.8KB → not selected
            // File 3 (clean): 100 items, 90 alive, size=2KB → projected alive = 1.8KB → not selected
            //
            // maxCompactedFileSizeInKB = 4 (4KB cap)
            // Phase 1 projected output: 1KB. Headroom: 3KB
            // Absorb file 2: 1KB + 1.8KB = 2.8KB < 4KB → absorbed
            // Absorb file 3: 2.8KB + 1.8KB = 4.6KB > 4KB → SKIP
            final long KB = 1024;
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10 * KB);
            final DataFileReader cleanFile2 = mockFileReader(2, 0, 100, 2 * KB);
            final DataFileReader cleanFile3 = mockFileReader(3, 0, 100, 2 * KB);

            final LongList index =
                    mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 90), locationsForFile(3, 90));

            // Threshold very low (0.01) so ratio is not the limiting factor
            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, cleanFile2, cleanFile3), 0.01, 4);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(cleanFile2));
            assertFalse(candidates.contains(cleanFile3));
        }

        @Test
        @DisplayName("No absorption when phase 1 aggregate ratio already at threshold")
        void noAbsorptionWhenNoRatioHeadroom() {
            // File 1: 45 alive / 55 dead → d/a = 1.22 → selected (above 1.2)
            // File 2: 5 alive / 5 dead → d/a = 1.0 → NOT selected (not > 1.2)
            //
            // gcRateThreshold: 1.2
            // Phase 1 aggregate: 55/45 = 1.22 — barely above threshold
            // After absorbing file 2: (55+5)/(45+5) = 60/50 = 1.2 → not > 1.2, SKIP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 1000);
            final DataFileReader smallFile = mockFileReader(2, 0, 10, 200);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 45), locationsForFile(2, 5));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, smallFile), 1.2, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertEquals(1, candidates.size());
            assertTrue(candidates.contains(dirtyFile));
            assertFalse(candidates.contains(smallFile));
        }

        @Test
        @DisplayName("No remaining files to absorb → phase 2 is a no-op")
        void noRemainingFiles() {
            // Both files have enough garbage → both selected in phase 1
            // File 1: 10 alive / 90 dead → d/a = 9.0
            // File 2: 5 alive / 95 dead → d/a = 19.0
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 5));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 1.0, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertEquals(2, candidates.size());
            assertTrue(candidates.contains(file1));
            assertTrue(candidates.contains(file2));
        }

        @Test
        @DisplayName("Remaining files sorted by dead/alive descending — highest ratio absorbed first")
        void highestRatioAbsorbedFirst() {
            // Dirty file: 5 alive / 95 dead → d/a = 19.0 → selected
            // Clean large: 95 alive / 5 dead, size=5000 → d/a = 0.053 → NOT selected
            // Clean small: 9 alive / 1 dead, size=200 → d/a = 0.11 → NOT selected
            // Clean medium: 18 alive / 2 dead, size=1000 → d/a = 0.11 → NOT selected
            //
            // gcRateThreshold: 2.0. Phase 1 aggregate: 95/5 = 19.0. Huge headroom.
            // Phase 2 sorts by d/a descending: small(0.11), medium(0.11), large(0.053)
            // Absorb small: (95+1)/(5+9) = 96/14 = 6.86 → absorbed
            // Absorb medium: (96+2)/(14+18) = 98/32 = 3.06 → absorbed
            // Absorb large: (98+5)/(32+95) = 103/127 = 0.81 → below 2.0, SKIP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader cleanLarge = mockFileReader(2, 0, 100, 5000);
            final DataFileReader cleanSmall = mockFileReader(3, 0, 10, 200);
            final DataFileReader cleanMedium = mockFileReader(4, 0, 20, 1000);

            final LongList index = mockIndexWithEntries(
                    locationsForFile(1, 5), locationsForFile(2, 95), locationsForFile(3, 9), locationsForFile(4, 18));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile, cleanLarge, cleanSmall, cleanMedium), 2.0, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(cleanSmall));
            assertTrue(candidates.contains(cleanMedium));
            assertFalse(candidates.contains(cleanLarge));
        }

        @Test
        @DisplayName("Absorption works independently per level")
        void absorptionIndependentPerLevel() {
            // Level 0: dirtyFile0 (5 alive/95 dead, d/a=19.0) → selected
            //          cleanFile0 (9 alive/1 dead, d/a=0.11, size=100) → absorb candidate
            // Level 3: dirtyFile3 (5 alive/95 dead, d/a=19.0) → selected
            //          cleanFile3 (9 alive/1 dead, d/a=0.11, size=100) → absorb candidate
            //
            // Threshold 5.0
            // Level 0 absorb: (95+1)/(5+9) = 96/14 = 6.86 > 5.0 → absorbed
            // Level 3 absorb: same → absorbed
            final DataFileReader dirtyFile0 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader cleanFile0 = mockFileReader(2, 0, 10, 100);
            final DataFileReader dirtyFile3 = mockFileReader(3, 3, 100, 1000);
            final DataFileReader cleanFile3 = mockFileReader(4, 3, 10, 100);

            final LongList index = mockIndexWithEntries(
                    locationsForFile(1, 5), locationsForFile(2, 9),
                    locationsForFile(3, 5), locationsForFile(4, 9));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile0, cleanFile0, dirtyFile3, cleanFile3), 5.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(2, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(dirtyFile0));
            assertTrue(result.candidatesByLevel().get(0).contains(cleanFile0));
            assertTrue(result.candidatesByLevel().get(3).contains(dirtyFile3));
            assertTrue(result.candidatesByLevel().get(3).contains(cleanFile3));
        }

        @Test
        @DisplayName("Phase 2 does not run for levels with no phase 1 candidates")
        void phase2SkipsLevelsWithNoPhase1Candidates() {
            // Both files have d/a = 1/9 = 0.11, below threshold 0.5
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);
            final DataFileReader file2 = mockFileReader(2, 0, 10, 100);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 9), locationsForFile(2, 9));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 0.5, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("File with dead == 0 is not absorbed (d/a = 0.0, would lower aggregate)")
        void fileWithNoDeadNotAbsorbed() {
            // Dirty file: 10 alive / 90 dead → d/a = 9.0 → selected
            // Clean file (no dead): 5 alive / 0 dead → d/a = 0.0 → not selected
            //
            // Phase 1 aggregate: 90/10 = 9.0
            // Absorbing clean file: (90+0)/(10+5) = 90/15 = 6.0
            // If threshold = 7.0 → 6.0 not > 7.0, SKIP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 1000);
            final DataFileReader cleanFile = mockFileReader(2, 0, 5, 200);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 5));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, cleanFile), 7.0, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertEquals(1, candidates.size());
            assertTrue(candidates.contains(dirtyFile));
        }

        @Test
        @DisplayName("File with dead == 0 is absorbed when threshold is low enough")
        void fileWithNoDeadAbsorbedWithLowThreshold() {
            // Dirty file: 10 alive / 90 dead → d/a = 9.0 → selected
            // Clean file (no dead): 5 alive / 0 dead → d/a = 0.0 → not selected
            //
            // Phase 1 aggregate: 90/10 = 9.0
            // Absorbing clean file: (90+0)/(10+5) = 90/15 = 6.0
            // If threshold = 0.5 → 6.0 > 0.5, absorb
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 1000);
            final DataFileReader cleanFile = mockFileReader(2, 0, 5, 200);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 5));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, cleanFile), 0.5, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertEquals(2, candidates.size());
            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(cleanFile));
        }
    }

    // ========================================================================
    // HDHM deduplication + dead/alive ratio
    // ========================================================================

    @Nested
    @DisplayName("HDHM deduplication with dead/alive ratio")
    class DeduplicationTests {

        @Test
        @DisplayName("Duplicate entries are deduplicated, preventing inflated alive counts")
        void duplicateEntriesAreDeduplicated() {
            // File 1: totalItems=5. After doubling, index has 8 entries (4 + 4 mirrored).
            // Only 2 unique alive entries → dead=3, alive=2, d/a = 1.5
            // Without dedup: alive=4, dead=1, d/a = 0.25 (wrong, wouldn't be selected with threshold 0.5)
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

            final GarbageScanner scanner =
                    new GarbageScanner(index, fileCollection, "ObjectKeyToPath", config(0.5, 0), true);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            // With dedup: alive=2, dead=3, d/a = 1.5 > 0.5 → selected
            assertTrue(candidates.contains(file));

            final GarbageScanner.GarbageFileStats stats = result.stats()
                    .garbageFileStats()[file.getIndex() - result.stats().offset()];
            assertEquals(2, stats.aliveItems());
            assertEquals(3, stats.deadItems());
        }

        @Test
        @DisplayName("Non-duplicate mirrored entries (sanitized buckets) are both counted")
        void sanitizedEntriesAreBothCounted() {
            // File 1: totalItems=4, all 4 entries distinct → alive=4, dead=0, d/a=0.0
            final DataFileReader file = mockFileReader(1, 0, 4, 1000);

            final LongList index = new LongListHeap(
                    DEFAULT_CONFIG.longListChunkSize(), 4, DEFAULT_CONFIG.longListReservedBufferSize());
            index.put(0, DataFileCommon.dataLocation(1, 100));
            index.put(1, DataFileCommon.dataLocation(1, 200));
            index.put(2, DataFileCommon.dataLocation(1, 300));
            index.put(3, DataFileCommon.dataLocation(1, 400));

            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

            final GarbageScanner scanner =
                    new GarbageScanner(index, fileCollection, "ObjectKeyToPath", config(0.5, 0), true);

            final ScanResult result = scanner.scan();

            // alive=4, dead=0, d/a=0.0 → not > 0.5 → not selected
            assertTrue(result.candidatesByLevel().isEmpty());
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty file collection produces empty result")
        void emptyFileCollection() {
            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of());

            final LongList index = emptyIndex();

            final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "test", config(0.5, 0));

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Index entries pointing to deleted files are gracefully skipped")
        void indexEntriesForDeletedFilesAreSkipped() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

            // Index has entries for file 1 AND file 99 (not in collection)
            final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(99, 3));

            // file1: 5 alive / 5 dead → d/a = 1.0 → not > 1.0 → not selected
            final GarbageScanner scanner = createScanner(index, List.of(file1), 1.0, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Index entries for deleted files — file still compactable")
        void indexEntriesForDeletedFilesFileStillCompactable() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 3), locationsForFile(99, 3));

            // file1: 3 alive / 7 dead → d/a = 2.33 > 1.0 → selected
            final GarbageScanner scanner = createScanner(index, List.of(file1), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(file1));
        }

        @Test
        @DisplayName("maxCompactedFileSizeInKB = 0 disables size cap in phase 2")
        void zeroCDisablesSizeCap() {
            // Dirty file: 5 alive / 95 dead → d/a = 19.0 → selected
            // Clean file: 90 alive / 10 dead, size=100000 → d/a = 0.11 → not selected
            // With cap=0 (disabled), only ratio matters
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader hugeCleanFile = mockFileReader(2, 0, 100, 100000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 90));

            // Threshold 0.2, size cap disabled
            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, hugeCleanFile), 0.2, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            // Phase 1 aggregate: 95/5 = 19.0
            // Absorb huge file: (95+10)/(5+90) = 105/95 = 1.11 > 0.2 → absorbed
            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(hugeCleanFile));
        }
    }

    // ========================================================================
    // estimateAliveSize utility
    // ========================================================================

    @Nested
    @DisplayName("estimateAliveSize")
    class EstimateAliveSizeTests {

        @Test
        @DisplayName("Correctly computes weighted sum from stats")
        void estimateAliveSizeComputation() {
            final DataFileReader reader1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader reader2 = mockFileReader(2, 0, 50, 500);
            final DataFileReader emptyReader = mockFileReader(3, 0, 0, 27);

            final GarbageScanner.IndexedGarbageFileStats stats =
                    new GarbageScanner.IndexedGarbageFileStats(1, new GarbageScanner.GarbageFileStats[] {
                        new GarbageScanner.GarbageFileStats(reader1, 75),
                        new GarbageScanner.GarbageFileStats(reader2, 25),
                        new GarbageScanner.GarbageFileStats(emptyReader, 0)
                    });

            final long result = GarbageScanner.estimateAliveSize(List.of(reader1, reader2, emptyReader), stats);

            assertEquals(1000, result);
        }

        @Test
        @DisplayName("Missing stats contribute zero")
        void estimateAliveSizeMissingStats() {
            final DataFileReader reader1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader deletedReader = mockFileReader(99, 0, 50, 500);

            final GarbageScanner.IndexedGarbageFileStats stats = new GarbageScanner.IndexedGarbageFileStats(
                    1, new GarbageScanner.GarbageFileStats[] {new GarbageScanner.GarbageFileStats(reader1, 75)});

            final long result = GarbageScanner.estimateAliveSize(List.of(reader1, deletedReader), stats);

            assertEquals(750, result);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static GarbageScanner createScanner(
            final LongList index,
            final List<DataFileReader> files,
            final double gcRateThreshold,
            final long maxCompactedFileSizeInKB) {
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);
        return new GarbageScanner(index, fileCollection, "test", config(gcRateThreshold, maxCompactedFileSizeInKB));
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

    private static MerkleDbConfig config(final double gcRateThreshold, final long maxCompactedFileSizeInKB) {
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
                DEFAULT_CONFIG.maxCompactionLevel(),
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
