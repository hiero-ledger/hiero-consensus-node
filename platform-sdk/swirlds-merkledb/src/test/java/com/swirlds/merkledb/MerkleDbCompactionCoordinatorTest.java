// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.merkledb.MerkleDbDataSource.ID_TO_HASH_CHUNK;
import static com.swirlds.merkledb.MerkleDbDataSource.OBJECT_KEY_TO_PATH;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.GarbageScanner.GarbageFileStats;
import com.swirlds.merkledb.GarbageScanner.IndexedGarbageFileStats;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.DataFileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MerkleDbCompactionCoordinatorTest {

    private MerkleDbCompactionCoordinator coordinator;
    private MerkleDbConfig config;

    @BeforeEach
    void setUp() {
        final MerkleDbConfig defaultConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        config = new MerkleDbConfig(
                defaultConfig.initialCapacity(),
                defaultConfig.maxNumOfKeys(),
                defaultConfig.hashesRamToDiskThreshold(),
                defaultConfig.hashStoreRamBufferSize(),
                defaultConfig.hashChunkCacheThreshold(),
                defaultConfig.hashStoreRamOffHeapBuffers(),
                defaultConfig.longListChunkSize(),
                defaultConfig.longListReservedBufferSize(),
                defaultConfig.compactionThreads(),
                defaultConfig.gcRateThreshold(),
                defaultConfig.maxCompactedFileSizeInMB(),
                defaultConfig.maxCompactionLevel(),
                defaultConfig.iteratorInputBufferBytes(),
                defaultConfig.reconnectKeyLeakMitigationEnabled(),
                defaultConfig.indexRebuildingEnforced(),
                defaultConfig.goodAverageBucketEntryCount(),
                defaultConfig.tablesToRepairHdhm(),
                defaultConfig.percentHalfDiskHashMapFlushThreads(),
                defaultConfig.numHalfDiskHashMapFlushThreads(),
                defaultConfig.leafRecordCacheSize(),
                defaultConfig.maxFileChannelsPerFileReader(),
                defaultConfig.maxThreadsPerFileChannel(),
                defaultConfig.useDiskIndices(),
                defaultConfig.consolidationMaxInputFileSizeMB(),
                defaultConfig.consolidationMinFileCount());
        coordinator = new MerkleDbCompactionCoordinator(config);
        coordinator.enableBackgroundCompaction();
    }

    @AfterEach
    void tearDown() {
        coordinator.stopAndDisableBackgroundCompaction();
        assertEventuallyEquals(
                0,
                () -> ((ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                                CONFIGURATION.getConfigData(MerkleDbConfig.class)))
                        .getActiveCount(),
                Duration.ofSeconds(2),
                "Active task count is not 0");
    }

    // ========================================================================
    // Scanner task tests
    // ========================================================================

    @Test
    void testSubmitScanIfNotRunningDoesNotSubmitDuplicates() throws InterruptedException {
        final GarbageScanner scanner = mock(GarbageScanner.class);
        final CountDownLatch scanStarted = new CountDownLatch(1);
        final CountDownLatch releaseScan = new CountDownLatch(1);
        when(scanner.scan()).thenAnswer(_ -> {
            scanStarted.countDown();
            releaseScan.await(5, TimeUnit.SECONDS);
            return new IndexedGarbageFileStats(0, new GarbageFileStats[0]);
        });

        coordinator.submitScanIfNotRunning(ID_TO_HASH_CHUNK, scanner);
        assertTrue(scanStarted.await(1, TimeUnit.SECONDS), "Scanner task wasn't started");

        // Submit again while the first is still running — should be a no-op
        coordinator.submitScanIfNotRunning(ID_TO_HASH_CHUNK, scanner);

        assertEventuallyDoesNotThrow(
                () -> verify(scanner, times(1)).scan(), Duration.ofSeconds(1), "Duplicate scanner task was submitted");

        releaseScan.countDown();
    }

    // ========================================================================
    // submitCompactionTasks tests
    // ========================================================================

    @Test
    void testSubmitCompactionTasksWithNoScanResultsIsNoOp() throws InterruptedException, IOException {
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            factoryCalls.incrementAndGet();
            return compactor;
        };

        // No scan stats have been published
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, config);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        assertEquals(0, factoryCalls.get(), "Factory should not be called when no scan stats exist");
        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testTaskNoOpsWhenNoFilesExceedThreshold() throws InterruptedException, IOException {
        // File with all items alive → dead/alive = 0.0 → not eligible
        final DataFileReader cleanFile = mockFileReader(1, 0, 100, 1000);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(cleanFile, 100)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testSubmitCompactionTasksEvaluatesAtExecutionTime() throws InterruptedException, IOException {
        // Two levels, each with a dirty file
        // dead/alive must exceed gcRateThreshold (default 0.5)
        final DataFileReader level0File = mockFileReader(1, 0, 100, 1000);
        final DataFileReader level2File = mockFileReader(2, 2, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File, level2File));

        // 20 alive → 80 dead → d/a = 4.0 > 0.5 → eligible
        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File, 20), new StatsEntry(level2File, 20)));

        final CountDownLatch compactionsDone = new CountDownLatch(2);
        final List<Integer> compactedTargetLevels = new ArrayList<>();

        final DataFileCompactor taskCompactor1 = mock(DataFileCompactor.class);
        when(taskCompactor1.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            synchronized (compactedTargetLevels) {
                compactedTargetLevels.add(invocation.getArgument(1));
            }
            compactionsDone.countDown();
            return true;
        });
        when(taskCompactor1.getDataFileCollection()).thenReturn(fileCollection);

        final DataFileCompactor taskCompactor2 = mock(DataFileCompactor.class);
        when(taskCompactor2.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            synchronized (compactedTargetLevels) {
                compactedTargetLevels.add(invocation.getArgument(1));
            }
            compactionsDone.countDown();
            return true;
        });
        when(taskCompactor2.getDataFileCollection()).thenReturn(fileCollection);

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            final int call = factoryCalls.getAndIncrement();
            return (call == 0) ? taskCompactor1 : taskCompactor2;
        };

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, config);

        assertTrue(compactionsDone.await(2, TimeUnit.SECONDS), "Compaction tasks were not submitted");
        synchronized (compactedTargetLevels) {
            // source level 0 → target 1, source level 2 → target 3
            assertEquals(Set.of(1, 3), new HashSet<>(compactedTargetLevels), "Target levels should be sourceLevel + 1");
        }
    }

    @Test
    void testSubmitCompactionTasksDoesNotDuplicateSameLevelTasks() throws InterruptedException, IOException {
        final DataFileReader level0File1 = mockFileReader(1, 0, 100, 1000);
        final DataFileReader level0File2 = mockFileReader(2, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File1, level0File2));

        // Both files: 20 alive → 80 dead → d/a = 4.0 → eligible
        publishScanStats(
                ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File1, 20), new StatsEntry(level0File2, 20)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor taskCompactor = mock(DataFileCompactor.class);
        when(taskCompactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(5, TimeUnit.SECONDS);
            return true;
        });
        when(taskCompactor.getDataFileCollection()).thenReturn(fileCollection);

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            factoryCalls.incrementAndGet();
            return taskCompactor;
        };

        // First call: submits a task for level 0
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, config);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction task wasn't started");

        // Second call: level 0 is already submitted, should not submit another
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, config);

        // Only one compactSingleLevel call should have been made (one task)
        verify(taskCompactor, times(1)).compactSingleLevel(anyList(), anyInt());

        releaseTask.countDown();
    }

    // ========================================================================
    // Pause / resume / stop tests
    // ========================================================================

    @Test
    void testPauseAndResumeCompaction() throws InterruptedException, IOException {
        final DataFileReader level0File = mockFileReader(1, 0, 100, 1000);
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File, 20)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(2, TimeUnit.SECONDS);
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction didn't start");

        coordinator.pauseCompactionAndRun(() -> {
            verify(compactor).pauseCompaction();
        });
        verify(compactor).resumeCompaction();

        releaseTask.countDown();
    }

    @Test
    void testStopAndDisableInterruptsRunningCompaction() throws InterruptedException, IOException {
        final DataFileReader level0File = mockFileReader(1, 0, 100, 1000);
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File, 20)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(5, TimeUnit.SECONDS);
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        // Make interruptCompaction() release the latch
        doAnswer(_ -> {
                    releaseTask.countDown();
                    return null;
                })
                .when(compactor)
                .interruptCompaction();

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction didn't start");

        coordinator.stopAndDisableBackgroundCompaction();

        verify(compactor).interruptCompaction();
        assertFalse(coordinator.isCompactionEnabled(), "Compaction should be disabled");
    }

    // ========================================================================
    // Consolidation task submission tests
    // ========================================================================

    @Test
    void testConsolidationSubmitsTaskForSmallFiles() throws InterruptedException, IOException {
        // 12 small clean files at level 0, each 5 MB, all alive (d/a = 0.0 → not garbage-eligible)
        // consolidationMaxInputFileSizeMB = 50, consolidationMinFileCount = 10
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> smallFiles = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024); // 5 MB
            smallFiles.add(f);
            statsEntries.add(new StatsEntry(f, 100)); // all alive → d/a = 0.0
        }

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(smallFiles);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final CountDownLatch taskDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            compactedFiles.addAll(invocation.getArgument(0));
            taskDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        assertTrue(taskDone.await(2, TimeUnit.SECONDS), "Consolidation task should complete");

        // All 12 files should be consolidated (no garbage tasks were submitted since d/a = 0)
        assertEquals(12, compactedFiles.size(), "All small files should be consolidated");
    }

    @Test
    void testConsolidationSkipsWhenBelowMinFileCount() throws InterruptedException, IOException {
        // 5 small files, but minFileCount = 10 → no consolidation
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> smallFiles = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024);
            smallFiles.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testConsolidationSkipsFilesAboveSizeThreshold() throws InterruptedException, IOException {
        // 12 files: 10 small (5 MB) + 2 large (100 MB)
        // Only the 10 small files should be candidates
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> files = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024); // 5 MB
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }
        final DataFileReader large1 = mockFileReader(11, 0, 100, 100 * 1024 * 1024); // 100 MB
        final DataFileReader large2 = mockFileReader(12, 0, 100, 100 * 1024 * 1024); // 100 MB
        files.add(large1);
        files.add(large2);
        statsEntries.add(new StatsEntry(large1, 100));
        statsEntries.add(new StatsEntry(large2, 100));

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final CountDownLatch taskDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            compactedFiles.addAll(invocation.getArgument(0));
            taskDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        assertTrue(taskDone.await(2, TimeUnit.SECONDS), "Consolidation task should complete");

        // Only the 10 small files should be consolidated, not the 2 large ones
        assertEquals(10, compactedFiles.size(), "Only small files should be consolidated");
        assertFalse(compactedFiles.contains(large1), "Large file should not be consolidated");
        assertFalse(compactedFiles.contains(large2), "Large file should not be consolidated");
    }

    @Test
    void testConsolidationSkipsFilesAlreadyAssignedToGarbageTasks() throws InterruptedException, IOException {
        // 12 files at level 0: 2 dirty (garbage-eligible) + 10 small clean
        // The 2 dirty files are also small. After garbage compaction absorbs some,
        // consolidation should not re-include any file already assigned.
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> files = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();

        // 2 dirty files at level 1 (d/a > 0.5)
        final DataFileReader dirty1 = mockFileReader(1, 1, 100, 5 * 1024 * 1024);
        final DataFileReader dirty2 = mockFileReader(2, 1, 100, 5 * 1024 * 1024);
        files.add(dirty1);
        files.add(dirty2);
        statsEntries.add(new StatsEntry(dirty1, 20)); // d/a = 4.0
        statsEntries.add(new StatsEntry(dirty2, 20)); // d/a = 4.0

        // 10 clean files (d/a = 0.0)
        for (int i = 3; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024);
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(files);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final List<List<DataFileReader>> allCompactedGroups = new ArrayList<>();
        final CountDownLatch allTasksDone = new CountDownLatch(2); // garbage + consolidation
        final AtomicInteger compactorCalls = new AtomicInteger();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            synchronized (allCompactedGroups) {
                allCompactedGroups.add(new ArrayList<>(invocation.getArgument(0)));
            }
            allTasksDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        assertTrue(allTasksDone.await(2, TimeUnit.SECONDS), "Both tasks should complete");

        // Verify no file appears in both a garbage group and a consolidation group
        synchronized (allCompactedGroups) {
            final Set<DataFileReader> allFiles = new HashSet<>();
            for (final List<DataFileReader> group : allCompactedGroups) {
                for (final DataFileReader f : group) {
                    assertTrue(allFiles.add(f), "File " + f.getIndex() + " appears in multiple tasks");
                }
            }
        }
    }

    @Test
    void testConsolidationDisabledWhenMaxInputSizeIsZero() throws InterruptedException, IOException {
        // consolidationMaxInputFileSizeMB = 0 → disabled
        final MerkleDbConfig consolidationConfig = configWithConsolidation(0, 2);
        final List<DataFileReader> files = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 1024); // 1 KB — tiny
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100)); // all alive
        }

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    // ========================================================================
    // absorbIntoGroup tests
    // ========================================================================

    @Test
    void testAbsorbIntoGroupAbsorbsEligibleFiles() {
        final DataFileReader dirty = mockFileReader(1, 0, 100, 1000);
        final DataFileReader clean = mockFileReader(2, 0, 100, 500);

        // dirty: 10 alive, 90 dead → d/a = 9.0
        // clean: 90 alive, 10 dead → d/a = 0.11
        final IndexedGarbageFileStats stats = buildStats(new StatsEntry(dirty, 10), new StatsEntry(clean, 90));

        final List<DataFileReader> group = new ArrayList<>(List.of(dirty));
        final List<DataFileReader> pool = new ArrayList<>(List.of(clean));

        // aggregate after absorbing clean: (90+10)/(10+90) = 100/100 = 1.0 > 0.5 → absorbed
        MerkleDbCompactionCoordinator.absorbIntoGroup("test", group, pool, stats, 0.5, Long.MAX_VALUE);

        assertEquals(2, group.size());
        assertTrue(group.contains(dirty));
        assertTrue(group.contains(clean));
        assertTrue(pool.isEmpty(), "Absorbed file should be removed from pool");
    }

    @Test
    void testAbsorbIntoGroupSkipsFileThatWouldBreachRatio() {
        final DataFileReader dirty = mockFileReader(1, 0, 100, 1000);
        final DataFileReader clean = mockFileReader(2, 0, 100, 500);

        // dirty: 40 alive, 60 dead → d/a = 1.5
        // clean: 95 alive, 5 dead → d/a = 0.053
        // absorb clean: (60+5)/(40+95) = 65/135 = 0.48 → not > 0.5 → skip
        final IndexedGarbageFileStats stats = buildStats(new StatsEntry(dirty, 40), new StatsEntry(clean, 95));

        final List<DataFileReader> group = new ArrayList<>(List.of(dirty));
        final List<DataFileReader> pool = new ArrayList<>(List.of(clean));

        MerkleDbCompactionCoordinator.absorbIntoGroup("test", group, pool, stats, 0.5, Long.MAX_VALUE);

        assertEquals(1, group.size(), "Clean file should not be absorbed");
        assertEquals(1, pool.size(), "Pool should be unchanged");
    }

    @Test
    void testAbsorbIntoGroupRemovesFromSharedPool() {
        final DataFileReader dirty = mockFileReader(1, 0, 100, 1000);
        final DataFileReader small1 = mockFileReader(2, 0, 20, 100);
        final DataFileReader small2 = mockFileReader(3, 0, 20, 100);

        // dirty: 10 alive, 90 dead → huge budget
        // small1: 15 alive, 5 dead → d/a = 0.33
        // small2: 18 alive, 2 dead → d/a = 0.11
        final IndexedGarbageFileStats stats =
                buildStats(new StatsEntry(dirty, 10), new StatsEntry(small1, 15), new StatsEntry(small2, 18));

        final List<DataFileReader> group1 = new ArrayList<>(List.of(dirty));
        final List<DataFileReader> pool = new ArrayList<>(List.of(small1, small2));

        MerkleDbCompactionCoordinator.absorbIntoGroup("test", group1, pool, stats, 0.5, Long.MAX_VALUE);

        // Both should be absorbed into group1
        assertEquals(3, group1.size());
        assertTrue(pool.isEmpty(), "All files absorbed from pool");
    }

    @Test
    void testAbsorbIntoGroupSkipsFileThatWouldBreachSizeCap() {
        // dirty: 10 alive, 90 dead, size=200 → projected alive = 20
        // clean1: 80 alive, 20 dead, size=500 → projected alive = 400
        // clean2: 90 alive, 10 dead, size=100 → projected alive = 90
        //
        // Size cap = 200
        // Group starts with dirty: projectedSize = 20, headroom = 180
        // clean1: projected alive = 400 > headroom → SKIP (size cap)
        // clean2: projected alive = 90 < headroom → check ratio:
        //   aggregate: (90+10)/(10+90) = 100/100 = 1.0 > 0.01 → absorb
        final DataFileReader dirty = mockFileReader(1, 0, 100, 200);
        final DataFileReader clean1 = mockFileReader(2, 0, 100, 500);
        final DataFileReader clean2 = mockFileReader(3, 0, 100, 100);

        final IndexedGarbageFileStats stats =
                buildStats(new StatsEntry(dirty, 10), new StatsEntry(clean1, 80), new StatsEntry(clean2, 90));

        final List<DataFileReader> group = new ArrayList<>(List.of(dirty));
        // Pool sorted by d/a descending: clean1 (d/a=0.25) before clean2 (d/a=0.11)
        final List<DataFileReader> pool = new ArrayList<>(List.of(clean1, clean2));

        MerkleDbCompactionCoordinator.absorbIntoGroup("test", group, pool, stats, 0.01, 200);

        assertEquals(2, group.size(), "Only clean2 should be absorbed (clean1 exceeds size cap)");
        assertTrue(group.contains(dirty));
        assertTrue(group.contains(clean2));
        assertFalse(group.contains(clean1));
        assertEquals(1, pool.size(), "clean1 should remain in pool");
    }

    @Test
    void testAbsorbIntoGroupNoAbsorptionWhenProjectedSizeAlreadyAtCap() {
        // dirty: 50 alive, 50 dead, size=1000 → projected alive = 500
        // clean: 90 alive, 10 dead, size=100 → projected alive = 90
        // Size cap = 500 → group's projected size already at cap → no absorption
        final DataFileReader dirty = mockFileReader(1, 0, 100, 1000);
        final DataFileReader clean = mockFileReader(2, 0, 100, 100);

        final IndexedGarbageFileStats stats = buildStats(new StatsEntry(dirty, 50), new StatsEntry(clean, 90));

        final List<DataFileReader> group = new ArrayList<>(List.of(dirty));
        final List<DataFileReader> pool = new ArrayList<>(List.of(clean));

        MerkleDbCompactionCoordinator.absorbIntoGroup("test", group, pool, stats, 0.01, 500);

        assertEquals(1, group.size(), "No absorption when already at size cap");
        assertEquals(1, pool.size(), "Pool unchanged");
    }

    @Test
    void testCompactionTaskResetsCompactionInProgressFlagOnFailure() throws InterruptedException, IOException {
        final DataFileReader file = mockFileReader(1, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(file, 20)));

        final CountDownLatch taskDone = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            throw new IOException("simulated failure");
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // Flag should have been set then reset in finally
        verify(file).setCompactionInProgress();
        verify(file).resetCompactionInProgress();
    }

    @Test
    void testIsCompactionRunningDetectsGarbageCompactionTask() throws InterruptedException, IOException {
        final DataFileReader level0File = mockFileReader(1, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File, 20)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(2, TimeUnit.SECONDS);
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction didn't start");

        assertTrue(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should return true while a garbage compaction task is active");

        releaseTask.countDown();
        coordinator.awaitForCurrentCompactionsToComplete(2000);

        assertFalse(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should return false after all tasks complete");
    }

    @Test
    void testIsCompactionRunningDetectsConsolidationTask() throws InterruptedException, IOException {
        // 12 small clean files → no garbage tasks, only consolidation
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> smallFiles = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024);
            smallFiles.add(f);
            statsEntries.add(new StatsEntry(f, 100)); // all alive → d/a = 0.0
        }

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(smallFiles);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(2, TimeUnit.SECONDS);
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Consolidation task didn't start");

        assertTrue(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should return true while a consolidation task is active");

        releaseTask.countDown();
        coordinator.awaitForCurrentCompactionsToComplete(2000);

        assertFalse(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should return false after consolidation completes");
    }

    @Test
    void testIsCompactionRunningReturnsFalseWhenNoTasksSubmitted() {
        assertFalse(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should return false when no tasks have been submitted");
    }

    @Test
    void testIsCompactionRunningDoesNotCrossStores() throws InterruptedException, IOException {
        final DataFileReader level0File = mockFileReader(1, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(level0File, 20)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            taskStarted.countDown();
            releaseTask.await(2, TimeUnit.SECONDS);
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction didn't start");

        assertTrue(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "isCompactionRunning should be true for the store with an active task");
        assertFalse(
                coordinator.isCompactionRunning(OBJECT_KEY_TO_PATH),
                "isCompactionRunning should be false for a different store");

        releaseTask.countDown();
        coordinator.awaitForCurrentCompactionsToComplete(2000);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    @SuppressWarnings("unchecked")
    private void publishScanStats(final String storeName, final IndexedGarbageFileStats stats) {
        try {
            final var field = MerkleDbCompactionCoordinator.class.getDeclaredField("scanStatsByStore");
            field.setAccessible(true);
            final var map = (Map<String, IndexedGarbageFileStats>) field.get(coordinator);
            map.put(storeName, stats);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record StatsEntry(DataFileReader reader, long aliveItems) {}

    /**
     * Builds an {@link IndexedGarbageFileStats} from the given entries. Files are assumed to
     * have contiguous indices starting from the first entry's index.
     */
    private static IndexedGarbageFileStats buildStats(final StatsEntry... entries) {
        if (entries.length == 0) {
            return new IndexedGarbageFileStats(0, new GarbageFileStats[0]);
        }

        int minIndex = Integer.MAX_VALUE;
        int maxIndex = Integer.MIN_VALUE;
        for (final StatsEntry e : entries) {
            minIndex = Math.min(minIndex, e.reader.getIndex());
            maxIndex = Math.max(maxIndex, e.reader.getIndex());
        }

        final GarbageFileStats[] arr = new GarbageFileStats[maxIndex - minIndex + 1];
        for (final StatsEntry e : entries) {
            arr[e.reader.getIndex() - minIndex] = new GarbageFileStats(e.reader);
            arr[e.reader.getIndex() - minIndex].incrementAliveItemsBy(e.aliveItems);
        }
        return new IndexedGarbageFileStats(minIndex, arr);
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

    private MerkleDbConfig configWithConsolidation(int maxInputSizeMB, int minFileCount) {
        final MerkleDbConfig d = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        return new MerkleDbConfig(
                d.initialCapacity(),
                d.maxNumOfKeys(),
                d.hashesRamToDiskThreshold(),
                d.hashStoreRamBufferSize(),
                d.hashChunkCacheThreshold(),
                d.hashStoreRamOffHeapBuffers(),
                d.longListChunkSize(),
                d.longListReservedBufferSize(),
                d.compactionThreads(),
                d.gcRateThreshold(),
                d.maxCompactedFileSizeInMB(),
                d.maxCompactionLevel(),
                d.iteratorInputBufferBytes(),
                d.reconnectKeyLeakMitigationEnabled(),
                d.indexRebuildingEnforced(),
                d.maxCompactionLevel(),
                d.tablesToRepairHdhm(),
                d.percentHalfDiskHashMapFlushThreads(),
                d.numHalfDiskHashMapFlushThreads(),
                d.leafRecordCacheSize(),
                d.maxFileChannelsPerFileReader(),
                d.maxThreadsPerFileChannel(),
                d.useDiskIndices(),
                maxInputSizeMB,
                minFileCount);
    }
}
