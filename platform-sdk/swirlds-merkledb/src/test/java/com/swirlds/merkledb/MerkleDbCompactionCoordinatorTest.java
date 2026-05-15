// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.MerkleDbDataSource.ID_TO_HASH_CHUNK;
import static com.swirlds.merkledb.MerkleDbDataSource.OBJECT_KEY_TO_PATH;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyDoesNotThrow;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024); // 5 MB
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
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
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
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024); // 5 MB
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }
        final DataFileReader large1 = mockFileReader(11, 1, 100, 100 * 1024 * 1024); // 100 MB
        final DataFileReader large2 = mockFileReader(12, 1, 100, 100 * 1024 * 1024); // 100 MB
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
        final DataFileReader dirty1 = mockFileReader(1, 2, 100, 5 * 1024 * 1024);
        final DataFileReader dirty2 = mockFileReader(2, 3, 100, 5 * 1024 * 1024);
        files.add(dirty1);
        files.add(dirty2);
        statsEntries.add(new StatsEntry(dirty1, 20)); // d/a = 4.0
        statsEntries.add(new StatsEntry(dirty2, 20)); // d/a = 4.0

        // 10 clean files (d/a = 0.0)
        for (int i = 3; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
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
            final DataFileReader f = mockFileReader(i, 1, 100, 1024); // 1 KB — tiny
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100)); // all alive
        }

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testConsolidationSkipsLevelZeroFiles() throws InterruptedException, IOException {
        // 15 small clean files at level 0 — enough to trigger consolidation by count,
        // but level 0 should be excluded from consolidation entirely
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024); // 5 MB, level 0
            statsEntries.add(new StatsEntry(f, 100)); // all alive → d/a = 0.0
        }

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // No tasks should be submitted — all files are level 0
        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testConsolidationSkipsLevelZeroButProcessesHigherLevels() throws InterruptedException, IOException {
        // Mix of level 0 and level 1 small files — only level 1 should be consolidated
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> files = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();

        // 15 files at level 0 (should be skipped)
        for (int i = 1; i <= 15; i++) {
            final DataFileReader f = mockFileReader(i, 0, 100, 5 * 1024 * 1024);
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }

        // 12 files at level 1 (should be consolidated)
        for (int i = 16; i <= 27; i++) {
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
            files.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }

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

        // Only level 1 files should be consolidated (12 files)
        assertEquals(12, compactedFiles.size(), "Only level 1 files should be consolidated");
        for (final DataFileReader f : compactedFiles) {
            assertEquals(1, f.getMetadata().getCompactionLevel(), "All consolidated files should be level 1");
        }
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
    void testCompactionTaskDoesNotResetFlagOnSuccess() throws InterruptedException, IOException {
        // After successful compaction, the flag must stay true — resetting it would
        // allow a concurrent task's CAS to succeed on a file that's already been deleted.
        final DataFileReader file = mockFileReader(1, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(file, 20)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenReturn(true);
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // Flag was set but must NOT be reset after success
        verify(file).setCompactionInProgress();
        verify(file, never()).resetCompactionInProgress();
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
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
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

    @Test
    void testSetCompactionInProgressCASPreventsDoubleAssignment() throws InterruptedException, IOException {
        // A file that appears in both a garbage task and a consolidation task from different
        // submitCompactionTasks calls. The CAS on setCompactionInProgress ensures only the
        // first task to execute claims the file.
        final DataFileReader sharedFile = mockFileReader(1, 1, 100, 5 * 1024 * 1024);

        // Use a real AtomicBoolean to simulate CAS behavior
        final AtomicBoolean flag = new AtomicBoolean(false);
        when(sharedFile.setCompactionInProgress()).thenAnswer(_ -> flag.compareAndSet(false, true));
        when(sharedFile.isCompactionInProgress()).thenAnswer(_ -> flag.get());

        // 20 alive out of 100 → d/a = 4.0 → eligible for garbage compaction
        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(sharedFile, 20)));

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(sharedFile));

        // First task: grabs the file via CAS
        final CountDownLatch task1Started = new CountDownLatch(1);
        final CountDownLatch task1Release = new CountDownLatch(1);
        final List<DataFileReader> task1Files = new ArrayList<>();
        final DataFileCompactor compactor1 = mock(DataFileCompactor.class);
        when(compactor1.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            task1Files.addAll(invocation.getArgument(0));
            task1Started.countDown();
            task1Release.await(2, TimeUnit.SECONDS);
            return true;
        });
        when(compactor1.getDataFileCollection()).thenReturn(fileCollection);

        // Submit first task
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor1, config);
        assertTrue(task1Started.await(1, TimeUnit.SECONDS), "First task should start");

        // File is now flagged via CAS — second task should not be able to claim it
        assertTrue(flag.get(), "File should be flagged after first task starts");

        // Submit second task (simulating a second submitCompactionTasks call)
        final List<DataFileReader> task2Files = new ArrayList<>();
        final DataFileCompactor compactor2 = mock(DataFileCompactor.class);
        when(compactor2.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            task2Files.addAll(invocation.getArgument(0));
            return true;
        });
        when(compactor2.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor2, config);
        task1Release.countDown();
        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // First task should have the file, second task should have been filtered out
        assertEquals(1, task1Files.size(), "First task should have claimed the file");
        assertTrue(task2Files.isEmpty(), "Second task should not have claimed the file");
    }

    @Test
    void testSubmitCompactionTasksSkipsFlaggedFilesInStaleScanResults() throws InterruptedException, IOException {
        // Simulates stale cached scan results referencing a file whose compactionInProgress
        // flag was set by a previous task. The coordinator should skip flagged files during
        // phase 1 filtering, even though the scanner already ran and included them.
        final DataFileReader flaggedFile = mockFileReader(1, 0, 100, 1000);
        final DataFileReader normalFile = mockFileReader(2, 0, 100, 1000);

        // Override CAS to simulate a file already claimed by a previous task
        when(flaggedFile.isCompactionInProgress()).thenReturn(true);
        when(flaggedFile.setCompactionInProgress()).thenReturn(false);

        // Both files are dirty (d/a = 4.0), but flaggedFile should be excluded
        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(flaggedFile, 20), new StatsEntry(normalFile, 20)));

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(normalFile));

        final CountDownLatch taskDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            compactedFiles.addAll(invocation.getArgument(0));
            taskDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(taskDone.await(2, TimeUnit.SECONDS), "Compaction task should complete");

        assertTrue(compactedFiles.contains(normalFile), "Normal file should be compacted");
        assertFalse(compactedFiles.contains(flaggedFile), "Flagged file should be excluded from stale scan results");
    }

    @Test
    void testSubmitCompactionTasksSkipsFlaggedFilesFromRemainingPool() throws InterruptedException, IOException {
        // A flagged clean file should not appear in the remaining pool for phase 2 absorption
        final DataFileReader dirtyFile = mockFileReader(1, 0, 100, 1000);
        final DataFileReader flaggedClean = mockFileReader(2, 0, 100, 500);
        final DataFileReader normalClean = mockFileReader(3, 0, 100, 500);

        when(flaggedClean.isCompactionInProgress()).thenReturn(true);
        when(flaggedClean.setCompactionInProgress()).thenReturn(false);

        publishScanStats(
                ID_TO_HASH_CHUNK,
                buildStats(
                        new StatsEntry(dirtyFile, 10), // d/a = 9.0 → eligible
                        new StatsEntry(flaggedClean, 90), // d/a = 0.11 → remaining, but flagged
                        new StatsEntry(normalClean, 90))); // d/a = 0.11 → remaining, available

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(dirtyFile, normalClean));

        final CountDownLatch taskDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            compactedFiles.addAll(invocation.getArgument(0));
            taskDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(taskDone.await(2, TimeUnit.SECONDS), "Compaction task should complete");

        assertTrue(compactedFiles.contains(dirtyFile), "Dirty file should be compacted");
        assertTrue(compactedFiles.contains(normalClean), "Normal clean file should be absorbed");
        assertFalse(compactedFiles.contains(flaggedClean), "Flagged clean file must not be absorbed");
    }

    @Test
    void testConsolidationSkipsWhenFilesDropBelowMinCountAtExecution() throws InterruptedException, IOException {
        // 12 small clean files at level 1 — enough for consolidation (minFileCount = 10).
        // At execution time, only 3 files survive the stale-file filter, simulating the
        // race where garbage compaction consumed the other 9 between submission and execution.
        // The task should skip: 3 survivors < consolidationMinFileCount (10).
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> allFiles = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
            allFiles.add(f);
            statsEntries.add(new StatsEntry(f, 100)); // all alive → d/a = 0.0
        }

        // At execution time, only the first 3 files are still in the collection —
        // the other 9 were consumed by a concurrent garbage compaction task
        final List<DataFileReader> survivingFiles = List.copyOf(allFiles.subList(0, 3));

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(survivingFiles);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(statsEntries.toArray(StatsEntry[]::new)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, consolidationConfig);

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // Consolidation should be skipped — compactSingleLevel never called
        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());

        // The 3 surviving files had their CAS flag set during filtering
        for (final DataFileReader f : survivingFiles) {
            verify(f).setCompactionInProgress();
        }
        // Flags must be reset since the task did not succeed
        for (final DataFileReader f : allFiles) {
            verify(f).resetCompactionInProgress();
        }
    }

    @Test
    void testConsolidationProceedsWhenEnoughFilesSurviveFiltering() throws InterruptedException, IOException {
        // 12 small clean files at level 1. At execution time, 10 survive the stale-file
        // filter (2 consumed by concurrent garbage compaction). 10 >= minFileCount → proceed.
        final MerkleDbConfig consolidationConfig = configWithConsolidation(50, 10);
        final List<DataFileReader> allFiles = new ArrayList<>();
        final List<StatsEntry> statsEntries = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final DataFileReader f = mockFileReader(i, 1, 100, 5 * 1024 * 1024);
            allFiles.add(f);
            statsEntries.add(new StatsEntry(f, 100));
        }

        // 10 of 12 files survive
        final List<DataFileReader> survivingFiles = List.copyOf(allFiles.subList(0, 10));

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(survivingFiles);

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

        assertTrue(taskDone.await(2, TimeUnit.SECONDS), "Consolidation task should proceed");

        assertEquals(10, compactedFiles.size(), "All 10 surviving files should be consolidated");
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
        when(reader.setCompactionInProgress()).thenReturn(true);
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
