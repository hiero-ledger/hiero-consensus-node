// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.merkledb.MerkleDbDataSource.ID_TO_HASH_CHUNK;
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
                defaultConfig.gcRateLowerThreshold(),
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
                defaultConfig.useDiskIndices());
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
            releaseTask.await(5, TimeUnit.SECONDS);
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
    // splitIntoGroups tests
    // ========================================================================

    @Test
    void testSplitIntoGroupsSingleGroup() {
        // Three files, projected alive: 300, 200, 400 = 900 total
        // Cap = 1000 → all fit in one group
        final DataFileReader f1 = mockFileReader(1, 0, 100, 600);
        final DataFileReader f2 = mockFileReader(2, 0, 100, 400);
        final DataFileReader f3 = mockFileReader(3, 0, 100, 800);

        final IndexedGarbageFileStats stats =
                buildStats(new StatsEntry(f1, 50), new StatsEntry(f2, 50), new StatsEntry(f3, 50));

        final List<List<DataFileReader>> groups =
                MerkleDbCompactionCoordinator.splitIntoGroups(List.of(f1, f2, f3), 1000, stats);

        assertEquals(1, groups.size());
        assertEquals(List.of(f1, f2, f3), groups.getFirst());
    }

    @Test
    void testSplitIntoGroupsMultipleGroups() {
        // f1: 50% alive, size=600 → projected = 300
        // f2: 50% alive, size=400 → projected = 200
        // f3: 50% alive, size=800 → projected = 400
        // Cap = 500 → f1+f2(500) fits; adding f3 = 900 → split
        final DataFileReader f1 = mockFileReader(1, 0, 100, 600);
        final DataFileReader f2 = mockFileReader(2, 0, 100, 400);
        final DataFileReader f3 = mockFileReader(3, 0, 100, 800);

        final IndexedGarbageFileStats stats =
                buildStats(new StatsEntry(f1, 50), new StatsEntry(f2, 50), new StatsEntry(f3, 50));

        final List<List<DataFileReader>> groups =
                MerkleDbCompactionCoordinator.splitIntoGroups(List.of(f1, f2, f3), 500, stats);

        assertEquals(2, groups.size());
        assertEquals(List.of(f1, f2), groups.get(0));
        assertEquals(List.of(f3), groups.get(1));
    }

    @Test
    void testSplitIntoGroupsOversizedFileGetsOwnGroup() {
        // Single file projected alive (500) exceeds cap (100)
        // Must still be included — at least one file per group
        final DataFileReader big = mockFileReader(1, 0, 100, 1000);

        final IndexedGarbageFileStats stats = buildStats(new StatsEntry(big, 50));

        final List<List<DataFileReader>> groups =
                MerkleDbCompactionCoordinator.splitIntoGroups(List.of(big), 100, stats);

        assertEquals(1, groups.size());
        assertEquals(List.of(big), groups.getFirst());
    }

    @Test
    void testSplitIntoGroupsDisabledWhenCapIsMaxValue() {
        final DataFileReader f1 = mockFileReader(1, 0, 100, 1000);
        final DataFileReader f2 = mockFileReader(2, 0, 100, 1000);

        final IndexedGarbageFileStats stats = buildStats(new StatsEntry(f1, 50), new StatsEntry(f2, 50));

        // Long.MAX_VALUE → no splitting
        final List<List<DataFileReader>> groups =
                MerkleDbCompactionCoordinator.splitIntoGroups(List.of(f1, f2), Long.MAX_VALUE, stats);

        assertEquals(1, groups.size());
        assertEquals(2, groups.getFirst().size());
    }

    @Test
    void testSplitIntoGroupsEmptyCandidates() {
        final IndexedGarbageFileStats stats = buildStats();

        final List<List<DataFileReader>> groups = MerkleDbCompactionCoordinator.splitIntoGroups(List.of(), 1000, stats);

        assertTrue(groups.isEmpty());
    }

    // ========================================================================
    // Lower threshold tests
    // ========================================================================

    @Test
    void testLowerThresholdFilesIncludedWhenCompactionTriggered() throws InterruptedException, IOException {
        // triggerFile: 20 alive, 80 dead → d/a = 4.0 > 0.5 (trigger threshold) → triggers compaction
        // lowerFile: 70 alive, 30 dead → d/a = 0.43 > 0.3 (lower threshold) → included
        // cleanFile: 90 alive, 10 dead → d/a = 0.11 < 0.3 (lower threshold) → excluded
        final DataFileReader triggerFile = mockFileReader(1, 0, 100, 1000);
        final DataFileReader lowerFile = mockFileReader(2, 0, 100, 500);
        final DataFileReader cleanFile = mockFileReader(3, 0, 100, 500);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(triggerFile, lowerFile, cleanFile));

        publishScanStats(
                ID_TO_HASH_CHUNK,
                buildStats(
                        new StatsEntry(triggerFile, 20), new StatsEntry(lowerFile, 70), new StatsEntry(cleanFile, 90)));

        final CountDownLatch compactionDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final List<DataFileReader> files = invocation.getArgument(0);
            synchronized (compactedFiles) {
                compactedFiles.addAll(files);
            }
            compactionDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(compactionDone.await(2, TimeUnit.SECONDS), "Compaction task was not submitted");
        synchronized (compactedFiles) {
            assertTrue(compactedFiles.contains(triggerFile), "Trigger file must be compacted");
            assertTrue(compactedFiles.contains(lowerFile), "Lower-threshold file must be compacted");
            assertFalse(compactedFiles.contains(cleanFile), "Clean file must NOT be compacted");
        }
    }

    @Test
    void testNoCompactionWhenNoFileExceedsTriggerThreshold() throws InterruptedException, IOException {
        // lowerFile1: 70 alive, 30 dead → d/a = 0.43 > 0.3 (lower threshold) but < 0.5 (trigger)
        // lowerFile2: 75 alive, 25 dead → d/a = 0.33 > 0.3 (lower threshold) but < 0.5 (trigger)
        // No file exceeds trigger threshold → no compaction
        final DataFileReader lowerFile1 = mockFileReader(1, 0, 100, 500);
        final DataFileReader lowerFile2 = mockFileReader(2, 0, 100, 500);

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(lowerFile1, 70), new StatsEntry(lowerFile2, 75)));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);
        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testLowerThresholdFilesRespectSizeCap() throws InterruptedException, IOException {
        // Use a config with a small size cap to force splitting
        final MerkleDbConfig smallCapConfig = new MerkleDbConfig(
                config.initialCapacity(),
                config.maxNumOfKeys(),
                config.hashesRamToDiskThreshold(),
                config.hashStoreRamBufferSize(),
                config.hashChunkCacheThreshold(),
                config.hashStoreRamOffHeapBuffers(),
                config.longListChunkSize(),
                config.longListReservedBufferSize(),
                config.compactionThreads(),
                config.gcRateThreshold(),
                config.gcRateLowerThreshold(),
                1, // 1 MB cap — forces splitting
                config.maxCompactionLevel(),
                config.iteratorInputBufferBytes(),
                config.reconnectKeyLeakMitigationEnabled(),
                config.indexRebuildingEnforced(),
                config.goodAverageBucketEntryCount(),
                config.tablesToRepairHdhm(),
                config.percentHalfDiskHashMapFlushThreads(),
                config.numHalfDiskHashMapFlushThreads(),
                config.leafRecordCacheSize(),
                config.maxFileChannelsPerFileReader(),
                config.maxThreadsPerFileChannel(),
                config.useDiskIndices());

        // triggerFile: 20 alive, 80 dead, size 2MB → projected alive ~400KB → triggers compaction
        // lowerFile1: 60 alive, 40 dead, size 2MB → d/a=0.67 > 0.3, projected alive ~1.2MB
        // lowerFile2: 70 alive, 30 dead, size 2MB → d/a=0.43 > 0.3, projected alive ~1.4MB
        // With 1MB cap, these should be split into multiple groups
        final DataFileReader triggerFile = mockFileReader(1, 0, 100, 2_000_000);
        final DataFileReader lowerFile1 = mockFileReader(2, 0, 100, 2_000_000);
        final DataFileReader lowerFile2 = mockFileReader(3, 0, 100, 2_000_000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(triggerFile, lowerFile1, lowerFile2));

        publishScanStats(
                ID_TO_HASH_CHUNK,
                buildStats(
                        new StatsEntry(triggerFile, 20),
                        new StatsEntry(lowerFile1, 60),
                        new StatsEntry(lowerFile2, 70)));

        final CountDownLatch compactionsDone = new CountDownLatch(2);
        final AtomicInteger taskCount = new AtomicInteger();
        final DataFileCompactor compactor1 = mock(DataFileCompactor.class);
        when(compactor1.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            compactionsDone.countDown();
            return true;
        });
        when(compactor1.getDataFileCollection()).thenReturn(fileCollection);

        final DataFileCompactor compactor2 = mock(DataFileCompactor.class);
        when(compactor2.compactSingleLevel(anyList(), anyInt())).thenAnswer(_ -> {
            compactionsDone.countDown();
            return true;
        });
        when(compactor2.getDataFileCollection()).thenReturn(fileCollection);

        final Supplier<DataFileCompactor> factory = () -> {
            final int call = taskCount.getAndIncrement();
            return (call == 0) ? compactor1 : compactor2;
        };

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, smallCapConfig);

        assertTrue(compactionsDone.await(2, TimeUnit.SECONDS), "Expected at least 2 compaction tasks due to size cap");
        assertTrue(taskCount.get() >= 2, "Should have created multiple groups due to size cap");
    }

    @Test
    void testLowerThresholdOnlyAppliesAtTriggeredLevel() throws InterruptedException, IOException {
        // Level 0: triggerFile with d/a > 0.5 → triggers compaction at level 0
        // Level 1: lowerFile with d/a = 0.43 > 0.3 but < 0.5 → should NOT be compacted
        //          because level 1 was not triggered (no file at level 1 exceeds trigger threshold)
        final DataFileReader level0Trigger = mockFileReader(1, 0, 100, 1000);
        final DataFileReader level1Lower = mockFileReader(2, 1, 100, 500);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0Trigger, level1Lower));

        publishScanStats(
                ID_TO_HASH_CHUNK,
                buildStats(
                        new StatsEntry(level0Trigger, 20), // d/a = 4.0 → triggers level 0
                        new StatsEntry(level1Lower, 70))); // d/a = 0.43 → above lower but not trigger

        final CountDownLatch compactionDone = new CountDownLatch(1);
        final List<Integer> compactedLevels = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            synchronized (compactedLevels) {
                compactedLevels.add(invocation.getArgument(1));
            }
            compactionDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(compactionDone.await(2, TimeUnit.SECONDS), "Compaction task was not submitted");
        coordinator.awaitForCurrentCompactionsToComplete(2000);
        synchronized (compactedLevels) {
            // Only level 0 → target level 1 should be compacted
            assertEquals(
                    List.of(1),
                    compactedLevels,
                    "Only level 0 (target 1) should be compacted; level 1 was not triggered");
        }
    }

    @Test
    void testFilesAtExactlyLowerThresholdAreExcluded() throws InterruptedException, IOException {
        // triggerFile: 20 alive, 80 dead → d/a = 4.0 → triggers
        // borderlineFile: 77 alive, 23 dead → d/a = 23/77 ≈ 0.2987 → at or just below 0.3 → excluded
        final DataFileReader triggerFile = mockFileReader(1, 0, 100, 1000);
        final DataFileReader borderlineFile = mockFileReader(2, 0, 100, 500);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(triggerFile, borderlineFile));

        publishScanStats(
                ID_TO_HASH_CHUNK, buildStats(new StatsEntry(triggerFile, 20), new StatsEntry(borderlineFile, 77)));

        final CountDownLatch compactionDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final List<DataFileReader> files = invocation.getArgument(0);
            synchronized (compactedFiles) {
                compactedFiles.addAll(files);
            }
            compactionDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(compactionDone.await(2, TimeUnit.SECONDS), "Compaction task was not submitted");
        synchronized (compactedFiles) {
            assertTrue(compactedFiles.contains(triggerFile), "Trigger file must be compacted");
            assertFalse(
                    compactedFiles.contains(borderlineFile),
                    "File at exactly the lower threshold boundary should be excluded (strict >)");
        }
    }

    @Test
    void testMultipleTriggerAndLowerFilesAtSameLevel() throws InterruptedException, IOException {
        // Two trigger files and two lower-eligible files at same level
        final DataFileReader trigger1 = mockFileReader(1, 0, 100, 500);
        final DataFileReader trigger2 = mockFileReader(2, 0, 100, 500);
        final DataFileReader lower1 = mockFileReader(3, 0, 100, 500);
        final DataFileReader lower2 = mockFileReader(4, 0, 100, 500);
        final DataFileReader clean = mockFileReader(5, 0, 100, 500);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(trigger1, trigger2, lower1, lower2, clean));

        publishScanStats(
                ID_TO_HASH_CHUNK,
                buildStats(
                        new StatsEntry(trigger1, 20), // d/a = 4.0 → trigger
                        new StatsEntry(trigger2, 30), // d/a = 2.33 → trigger
                        new StatsEntry(lower1, 70), // d/a = 0.43 → lower eligible
                        new StatsEntry(lower2, 75), // d/a = 0.33 → lower eligible (just above 0.3)
                        new StatsEntry(clean, 95))); // d/a = 0.053 → below lower threshold

        final CountDownLatch compactionDone = new CountDownLatch(1);
        final List<DataFileReader> compactedFiles = new ArrayList<>();
        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        when(compactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final List<DataFileReader> files = invocation.getArgument(0);
            synchronized (compactedFiles) {
                compactedFiles.addAll(files);
            }
            compactionDone.countDown();
            return true;
        });
        when(compactor.getDataFileCollection()).thenReturn(fileCollection);

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, () -> compactor, config);

        assertTrue(compactionDone.await(2, TimeUnit.SECONDS), "Compaction task was not submitted");
        synchronized (compactedFiles) {
            assertEquals(4, compactedFiles.size(), "Should include 2 trigger + 2 lower-eligible files");
            assertTrue(compactedFiles.contains(trigger1));
            assertTrue(compactedFiles.contains(trigger2));
            assertTrue(compactedFiles.contains(lower1));
            assertTrue(compactedFiles.contains(lower2));
            assertFalse(compactedFiles.contains(clean), "Clean file must be excluded");
        }
    }

    // ========================================================================
    // Compaction task edge case tests
    // ========================================================================

    @Test
    void testCompactionTaskResetsCompactionInProgressFlagOnFailure() throws InterruptedException, IOException {
        final DataFileReader file = mockFileReader(1, 0, 100, 1000);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

        publishScanStats(ID_TO_HASH_CHUNK, buildStats(new StatsEntry(file, 20)));

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
}
