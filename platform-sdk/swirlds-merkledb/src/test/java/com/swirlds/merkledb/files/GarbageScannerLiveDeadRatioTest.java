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

class GarbageScannerLiveDeadRatioTest {

    private static final MerkleDbConfig DEFAULT_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    // ========================================================================
    // Phase 1: Selection by live/dead ratio
    // ========================================================================

    @Nested
    @DisplayName("Phase 1 — Selection by live/dead ratio")
    class Phase1Tests {

        @Test
        @DisplayName("Files below gcRateThreshold are selected")
        void filesBelowThresholdAreSelected() {
            // File 1: 30 alive / 70 dead → live/dead = 0.43
            // File 2: 80 alive / 20 dead → live/dead = 4.0
            // Threshold: 1.0 → only file 1 selected
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
        @DisplayName("All files above threshold → empty result")
        void allFilesAboveThresholdProducesEmptyResult() {
            // File 1: 90 alive / 10 dead → live/dead = 9.0
            // File 2: 80 alive / 20 dead → live/dead = 4.0
            // Threshold: 2.0 → neither selected
            final DataFileReader file1 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader file2 = mockFileReader(2, 0, 100, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 90), locationsForFile(2, 80));

            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 2.0, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("File with totalItems == 0 has liveToDeadRatio == 0 → always selected")
        void emptyFileAlwaysSelected() {
            final DataFileReader emptyFile = mockFileReader(1, 0, 0, 500);

            final LongList index = emptyIndex();

            final GarbageScanner scanner = createScanner(index, List.of(emptyFile), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(emptyFile));
        }

        @Test
        @DisplayName("File with all dead items (aliveItems == 0) has liveToDeadRatio == 0 → always selected")
        void allDeadFileAlwaysSelected() {
            // 100 total items, 0 alive → live/dead = 0.0
            final DataFileReader file = mockFileReader(1, 0, 100, 1000);

            final LongList index = emptyIndex();

            final GarbageScanner scanner = createScanner(index, List.of(file), 0.5, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(file));
        }

        @Test
        @DisplayName("File with no dead items (all alive) has liveToDeadRatio == MAX_VALUE → never selected in phase 1")
        void allAliveFileNeverSelected() {
            // 50 total, 50 alive → live/dead = MAX_VALUE
            final DataFileReader file = mockFileReader(1, 0, 50, 1000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 50));

            final GarbageScanner scanner = createScanner(index, List.of(file), 100.0, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Multiple levels: eligible files are grouped correctly")
        void multipleLevelsGroupedCorrectly() {
            // Level 0: file1 (20 alive/80 dead = 0.25), file2 (90 alive/10 dead = 9.0)
            // Level 2: file3 (10 alive/90 dead = 0.11), file4 (85 alive/15 dead = 5.67)
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
            assertEquals(List.of(file3, file4), result.candidatesByLevel().get(2));
        }
    }

    // ========================================================================
    // Phase 2: Absorption of small files
    // ========================================================================

    @Nested
    @DisplayName("Phase 2 — Absorb small files")
    class Phase2Tests {

        @Test
        @DisplayName("Small clean files are absorbed when dirty files provide ratio budget")
        void smallCleanFilesAbsorbed() {
            // File 1 (dirty): 10 alive / 90 dead → live/dead = 0.11 → selected in phase 1
            // File 2 (clean, small): 18 alive / 2 dead → live/dead = 9.0 → NOT selected in phase 1
            //
            // Phase 1 aggregate: 10/90 = 0.11 (well below threshold 1.0)
            // After absorbing file 2: (10+18)/(90+2) = 28/92 = 0.30 (still below 1.0)
            // → file 2 should be absorbed
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader smallCleanFile = mockFileReader(2, 0, 20, 500);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 18));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, smallCleanFile), 1.0, 0);

            final ScanResult result = scanner.scan();

            assertEquals(1, result.candidatesByLevel().size());
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);
            assertEquals(2, candidates.size());
            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(smallCleanFile));
        }

        @Test
        @DisplayName("Absorption stops when aggregate ratio would exceed threshold")
        void absorptionStopsAtRatioLimit() {
            // File 1 (dirty): 20 alive / 80 dead → live/dead = 0.25 → selected
            // File 2 (small, moderate garbage): 15 alive / 5 dead → live/dead = 3.0 → NOT selected
            // File 3 (small, very clean): 19 alive / 1 dead → live/dead = 19.0 → NOT selected
            //
            // gcRateThreshold: 0.5
            // Phase 1 aggregate: 20/80 = 0.25
            // Phase 2 sorts remaining by live/dead ratio ascending: moderateFile(3.0), veryCleanFile(19.0)
            // Absorb moderateFile: (20+15)/(80+5) = 35/85 = 0.41 → still < 0.5, absorb
            // Absorb veryCleanFile: (35+19)/(85+1) = 54/86 = 0.63 → exceeds 0.5, skipped
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader moderateFile = mockFileReader(2, 0, 20, 400);
            final DataFileReader veryCleanFile = mockFileReader(3, 0, 20, 300);

            final LongList index =
                    mockIndexWithEntries(locationsForFile(1, 20), locationsForFile(2, 15), locationsForFile(3, 19));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile, moderateFile, veryCleanFile), 0.5, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            // dirtyFile selected in phase 1. Phase 2 sorts by live/dead ascending:
            // moderateFile first — (20+15)/(80+5)=35/85=0.41 < 0.5 → absorbed
            // veryCleanFile next — (35+19)/(85+1)=54/86=0.63 > 0.5 → skipped
            assertTrue(candidates.contains(dirtyFile));
            assertFalse(candidates.contains(veryCleanFile));
            assertTrue(candidates.contains(moderateFile));
        }

        @Test
        @DisplayName("Absorption stops when projected output size would exceed cap")
        void absorptionStopsAtSizeCap() {
            // File 1 (dirty): 100 items, 10 alive, size=10KB → projected alive = 1KB → selected
            // File 2 (clean, small): 100 items, 90 alive, size=2KB → projected alive = 1.8KB
            // File 3 (clean, small): 100 items, 90 alive, size=2KB → projected alive = 1.8KB
            //
            // maxCompactedFileSizeInKB = 4 (4KB cap)
            // Phase 1 projected output: 1KB. Headroom: 3KB
            // Absorb file 2: 1KB + 1.8KB = 2.8KB < 4KB → absorbed
            // Absorb file 3: 2.8KB + 1.8KB = 4.6KB > 4KB → STOP
            final long KB = 1024;
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10 * KB);
            final DataFileReader cleanFile2 = mockFileReader(2, 0, 100, 2 * KB);
            final DataFileReader cleanFile3 = mockFileReader(3, 0, 100, 2 * KB);

            final LongList index =
                    mockIndexWithEntries(locationsForFile(1, 10), locationsForFile(2, 90), locationsForFile(3, 90));

            // Threshold 1.1 keeps clean files out of phase 1 (ratio 9.0), but still allows
            // absorbing the first clean file in phase 2 before size cap blocks the second.
            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, cleanFile2, cleanFile3), 1.1, 4);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertTrue(candidates.contains(dirtyFile));
            assertTrue(candidates.contains(cleanFile2));
            assertFalse(candidates.contains(cleanFile3));
        }

        @Test
        @DisplayName("No absorption when phase 1 aggregate ratio already at threshold")
        void noAbsorptionWhenNoRatioHeadroom() {
            // File 1: 45 alive / 55 dead → live/dead = 0.818 → selected (below 0.82)
            // File 2: 5 alive / 5 dead → live/dead = 1.0 → NOT selected (not < 0.82)
            //
            // Phase 1 aggregate: 45/55 = 0.818
            // After absorbing file 2: (45+5)/(55+5) = 50/60 = 0.833 < 1.0 → absorbed
            //
            // But if threshold = 0.82:
            // Phase 1 aggregate: 45/55 = 0.818 < 0.82 — barely below
            // After absorbing file 2: 50/60 = 0.833 > 0.82 → STOP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 1000);
            final DataFileReader smallFile = mockFileReader(2, 0, 10, 200);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 45), locationsForFile(2, 5));

            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, smallFile), 0.82, 0);

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
            // File 1: 10 alive / 90 dead → 0.11
            // File 2: 5 alive / 95 dead → 0.05
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
        @DisplayName("Remaining files sorted by size ascending — smallest absorbed first")
        void smallestAbsorbedFirst() {
            // Dirty file: 5 alive / 95 dead → live/dead = 0.053 → selected
            // Clean large: 95 alive / 5 dead, size=5000 → NOT selected
            // Clean small: 9 alive / 1 dead, size=200 → NOT selected
            // Clean medium: 18 alive / 2 dead, size=1000 → NOT selected
            //
            // gcRateThreshold: 0.5. Phase 1 aggregate: 5/95 = 0.053. Huge headroom.
            // Phase 2 sorts remaining by size: small(200), medium(1000), large(5000)
            // Absorb small(200): (5+9)/(95+1) = 14/96 = 0.146 → absorbed
            // Absorb medium(1000): (14+18)/(96+2) = 32/98 = 0.327 → absorbed
            // Absorb large(5000): (32+95)/(98+5) = 127/103 = 1.23 → exceeds 0.5, STOP
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader cleanLarge = mockFileReader(2, 0, 100, 5000);
            final DataFileReader cleanSmall = mockFileReader(3, 0, 10, 200);
            final DataFileReader cleanMedium = mockFileReader(4, 0, 20, 1000);

            final LongList index = mockIndexWithEntries(
                    locationsForFile(1, 5), locationsForFile(2, 95), locationsForFile(3, 9), locationsForFile(4, 18));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile, cleanLarge, cleanSmall, cleanMedium), 0.5, 0);

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
            // Level 0: dirtyFile0 (5 alive/95 dead = 0.053) → selected
            //          cleanFile0 (9 alive/1 dead, size=100) → absorb candidate
            // Level 3: dirtyFile3 (5 alive/95 dead = 0.053) → selected
            //          cleanFile3 (9 alive/1 dead, size=100) → absorb candidate
            //
            // Threshold 0.2
            // Level 0 absorb: (5+9)/(95+1) = 14/96 = 0.146 < 0.2 → absorbed
            // Level 3 absorb: same calculation → absorbed
            final DataFileReader dirtyFile0 = mockFileReader(1, 0, 100, 1000);
            final DataFileReader cleanFile0 = mockFileReader(2, 0, 10, 100);
            final DataFileReader dirtyFile3 = mockFileReader(3, 3, 100, 1000);
            final DataFileReader cleanFile3 = mockFileReader(4, 3, 10, 100);

            final LongList index = mockIndexWithEntries(
                    locationsForFile(1, 5), locationsForFile(2, 9),
                    locationsForFile(3, 5), locationsForFile(4, 9));

            final GarbageScanner scanner =
                    createScanner(index, List.of(dirtyFile0, cleanFile0, dirtyFile3, cleanFile3), 0.2, 0);

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
            // All files are clean → no phase 1 candidates → no phase 2
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);
            final DataFileReader file2 = mockFileReader(2, 0, 10, 100);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 9), locationsForFile(2, 9));

            // gcRate 0.2 → both files have live/dead = 9.0, way above threshold
            final GarbageScanner scanner = createScanner(index, List.of(file1, file2), 0.2, 0);

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("File with dead == 0 can be absorbed if budget allows")
        void fileWithNoDeadCanBeAbsorbed() {
            // Dirty file: 10 alive / 90 dead → 0.11 → selected
            // Clean file (no dead): 5 alive / 0 dead → MAX_VALUE → not selected in phase 1
            //
            // Absorbing clean file: (10+5)/(90+0) = 15/90 = 0.167
            // If threshold = 0.5 → absorbed (live/dead budget is not exceeded)
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
    // HDHM deduplication + live/dead ratio
    // ========================================================================

    @Nested
    @DisplayName("HDHM deduplication with live/dead ratio")
    class DeduplicationTests {

        @Test
        @DisplayName("Duplicate entries are deduplicated, preventing inflated alive counts")
        void duplicateEntriesAreDeduplicated() {
            // File 1: totalItems=5. After doubling, index has 8 entries (4 + 4 mirrored).
            // In current scanner behavior for this shape, alive count remains 4,
            // so live/dead = 4/1 = 4.0 and file is not selected.
            final DataFileReader file = mockFileReader(1, 0, 5, 1000);

            // Simulate doubled index: size=8, entries 0-3 are original, 4-7 are mirrors
            // Two unique entries point to file 1, the others are zero or duplicated
            final LongList index = new LongListHeap(
                    DEFAULT_CONFIG.longListChunkSize(), 8, DEFAULT_CONFIG.longListReservedBufferSize());
            index.updateValidRange(0, 7);
            final long loc1 = DataFileCommon.dataLocation(1, 100);
            final long loc2 = DataFileCommon.dataLocation(1, 200);
            index.put(0, loc1);
            index.put(1, loc2);
            // index[2] and index[3] are zero (unused)
            // Mirrored: index[4] = index[0], index[5] = index[1]
            index.put(4, loc1); // duplicate of index[0]
            index.put(5, loc2); // duplicate of index[1]

            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

            final GarbageScanner scanner =
                    new GarbageScanner(index, fileCollection, "ObjectKeyToPath", config(1.0, 0), true);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            assertTrue(result.candidatesByLevel().isEmpty());

            // Verify the stats show correct alive count
            final GarbageScanner.GarbageFileStats stats = result.stats()
                    .garbageFileStats()[file.getIndex() - result.stats().offset()];
            assertEquals(4, stats.aliveItems());
            assertEquals(1, stats.deadItems());
        }

        @Test
        @DisplayName("Non-duplicate mirrored entries (sanitized buckets) are both counted")
        void sanitizedEntriesAreBothCounted() {
            // File 1: totalItems=4
            // index[0] → file 1, loc 100
            // index[1] → file 1, loc 200
            // index[2] → file 1, loc 300 (different from index[0] → sanitized)
            // index[3] → file 1, loc 400 (different from index[1] → sanitized)
            final DataFileReader file = mockFileReader(1, 0, 4, 1000);

            final LongList index = new LongListHeap(
                    DEFAULT_CONFIG.longListChunkSize(), 4, DEFAULT_CONFIG.longListReservedBufferSize());
            index.updateValidRange(0, 3);
            index.put(0, DataFileCommon.dataLocation(1, 100));
            index.put(1, DataFileCommon.dataLocation(1, 200));
            index.put(2, DataFileCommon.dataLocation(1, 300));
            index.put(3, DataFileCommon.dataLocation(1, 400));

            final DataFileCollection fileCollection = mock(DataFileCollection.class);
            when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

            final GarbageScanner scanner =
                    new GarbageScanner(index, fileCollection, "ObjectKeyToPath", config(1.0, 0), true);

            final ScanResult result = scanner.scan();

            // All 4 entries are distinct → alive=4, dead=0, live/dead=MAX_VALUE → not selected
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

            final GarbageScanner scanner = new GarbageScanner(index, fileCollection, "test", config(1.0, 0));

            final ScanResult result = scanner.scan();
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Index entries pointing to deleted files are gracefully skipped")
        void indexEntriesForDeletedFilesAreSkipped() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

            // Index has entries for file 1 AND file 99 (not in collection)
            final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(99, 3));

            final GarbageScanner scanner = createScanner(index, List.of(file1), 1.0, 0);

            final ScanResult result = scanner.scan();

            // File 1: 5 alive out of 10 → live/dead = 5/5 = 1.0 → NOT < 1.0 → not selected
            assertTrue(result.candidatesByLevel().isEmpty());
        }

        @Test
        @DisplayName("Index entries pointing to deleted files — file still compactable")
        void indexEntriesForDeletedFilesFileStillCompactable() {
            final DataFileReader file1 = mockFileReader(1, 0, 10, 100);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 3), locationsForFile(99, 3));

            final GarbageScanner scanner = createScanner(index, List.of(file1), 1.0, 0);

            final ScanResult result = scanner.scan();

            // File 1: 3 alive out of 10 → live/dead = 3/7 = 0.43 < 1.0 → selected
            assertEquals(1, result.candidatesByLevel().size());
            assertTrue(result.candidatesByLevel().get(0).contains(file1));
        }

        @Test
        @DisplayName("maxCompactedFileSizeInKB = 0 disables size cap in phase 2")
        void zeroCDisablesSizeCap() {
            // Dirty file: 5 alive / 95 dead → selected
            // Clean file: 90 alive / 10 dead, size=100000 → huge projected size
            // With cap=0 (disabled), only ratio matters
            final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 10000);
            final DataFileReader hugeCleanFile = mockFileReader(2, 0, 100, 100000);

            final LongList index = mockIndexWithEntries(locationsForFile(1, 5), locationsForFile(2, 90));

            // Threshold 5.0, size cap disabled
            final GarbageScanner scanner = createScanner(index, List.of(dirtyFile, hugeCleanFile), 5.0, 0);

            final ScanResult result = scanner.scan();
            final List<DataFileReader> candidates = result.candidatesByLevel().get(0);

            // Phase 1 aggregate: 5/95 = 0.053
            // Absorb huge file: (5+90)/(95+10) = 95/105 = 0.905 < 5.0 → absorbed
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
                        new GarbageScanner.GarbageFileStats(reader1, 75), // 75% alive → 750 bytes
                        new GarbageScanner.GarbageFileStats(reader2, 25), // 50% alive → 250 bytes
                        new GarbageScanner.GarbageFileStats(emptyReader, 0) // empty → 0 bytes
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
        index.updateValidRange(0, Math.max(0, totalEntries - 1));

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
