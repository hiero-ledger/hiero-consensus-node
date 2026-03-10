// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyDoesNotThrow;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.merkledb.files.DataFileCompactor.ID_TO_HASH_CHUNK;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileMetadata;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.GarbageScanner;
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

    @BeforeEach
    void setUp() {
        coordinator = new MerkleDbCompactionCoordinator("test", CONFIGURATION.getConfigData(MerkleDbConfig.class));
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
        when(scanner.scan()).thenAnswer(invocation -> {
            scanStarted.countDown();
            releaseScan.await(5, TimeUnit.SECONDS);
            return Map.of();
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
        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        final DataFileReader level0File = mockFileReader(1, 0);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            factoryCalls.incrementAndGet();
            return compactor;
        };

        // No scan results have been published — tasks will be submitted but will no-op
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));

        // Wait for submitted task to complete
        coordinator.awaitForCurrentCompactionsToComplete(2000);

        // Factory should never be called since the task sees no scan results and exits
        assertEquals(0, factoryCalls.get(), "Factory should not be called when no scan results exist");
        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
    }

    @Test
    void testSubmitCompactionTasksEvaluatesAtExecutionTime() throws InterruptedException, IOException {
        final DataFileReader level0File1 = mockFileReader(1, 0);
        final DataFileReader level0File2 = mockFileReader(2, 0);
        final DataFileReader level2File1 = mockFileReader(3, 2);
        final DataFileReader level2File2 = mockFileReader(4, 2);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles())
                .thenReturn(List.of(level0File1, level0File2, level2File1, level2File2));

        // Publish scan results: both levels have high garbage
        publishScanResult(
                ID_TO_HASH_CHUNK, Map.of(0, List.of(level0File1, level0File2), 2, List.of(level2File1, level2File2)));

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
        final DataFileCompactor taskCompactor2 = mock(DataFileCompactor.class);
        when(taskCompactor2.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            synchronized (compactedTargetLevels) {
                compactedTargetLevels.add(invocation.getArgument(1));
            }
            compactionsDone.countDown();
            return true;
        });

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            final int call = factoryCalls.getAndIncrement();
            return (call == 0) ? taskCompactor1 : taskCompactor2;
        };

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));

        assertTrue(compactionsDone.await(2, TimeUnit.SECONDS), "Compaction tasks were not submitted");
        synchronized (compactedTargetLevels) {
            // source level 0 → target 1, source level 2 → target 3
            assertEquals(Set.of(1, 3), new HashSet<>(compactedTargetLevels), "Target levels should be sourceLevel + 1");
        }
    }

    @Test
    void testSubmitCompactionTasksDoesNotDuplicateSameLevelTasks() throws InterruptedException, IOException {
        final DataFileReader level0File1 = mockFileReader(1, 0);
        final DataFileReader level0File2 = mockFileReader(2, 0);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File1, level0File2));

        publishScanResult(ID_TO_HASH_CHUNK, Map.of(0, List.of(level0File1, level0File2)));

        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final DataFileCompactor taskCompactor = mock(DataFileCompactor.class);
        when(taskCompactor.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            taskStarted.countDown();
            releaseTask.await(5, TimeUnit.SECONDS);
            return true;
        });

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            factoryCalls.incrementAndGet();
            return taskCompactor;
        };

        // First call: submits a task for level 0
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS), "Compaction task wasn't started");

        // Second call: level 0 is already submitted, should not submit another
        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));

        // Only one compactSingleLevel call should have been made (one task)
        verify(taskCompactor, times(1)).compactSingleLevel(anyList(), anyInt());

        releaseTask.countDown();
    }

    @Test
    void testTaskNoOpsWhenThresholdsNotExceeded() throws InterruptedException, IOException {
        final DataFileReader level0File = mockFileReader(1, 0);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File));

        publishScanResult(ID_TO_HASH_CHUNK, Map.of());

        final DataFileCompactor compactor = mock(DataFileCompactor.class);
        final Supplier<DataFileCompactor> factory = () -> compactor;

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));

        coordinator.awaitForCurrentCompactionsToComplete(2000);

        verify(compactor, never()).compactSingleLevel(anyList(), anyInt());
        assertFalse(
                coordinator.isCompactionRunning(ID_TO_HASH_CHUNK),
                "No compaction should be running after task completes");
    }

    // ========================================================================
    // Pause / resume / stop tests
    // ========================================================================

    @Test
    void testPauseCompactionAndRunPausesAllActiveCompactorsAcrossLevels() throws IOException, InterruptedException {
        final DataFileReader level0File = mockFileReader(1, 0);
        final DataFileReader level2File = mockFileReader(2, 2);

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(level0File, level2File));

        publishScanResult(ID_TO_HASH_CHUNK, Map.of(0, List.of(level0File), 2, List.of(level2File)));

        final CountDownLatch tasksStarted = new CountDownLatch(2);
        final CountDownLatch releaseTasks = new CountDownLatch(1);
        final DataFileCompactor taskCompactor1 = mock(DataFileCompactor.class);
        when(taskCompactor1.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            tasksStarted.countDown();
            releaseTasks.await(5, TimeUnit.SECONDS);
            return true;
        });
        final DataFileCompactor taskCompactor2 = mock(DataFileCompactor.class);
        when(taskCompactor2.compactSingleLevel(anyList(), anyInt())).thenAnswer(invocation -> {
            tasksStarted.countDown();
            releaseTasks.await(5, TimeUnit.SECONDS);
            return true;
        });

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            final int call = factoryCalls.getAndIncrement();
            return (call == 0) ? taskCompactor1 : taskCompactor2;
        };

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));
        assertTrue(tasksStarted.await(1, TimeUnit.SECONDS), "Compaction tasks didn't start");

        coordinator.pauseCompactionAndRun(() -> {});

        verify(taskCompactor1).pauseCompaction();
        verify(taskCompactor2).pauseCompaction();
        verify(taskCompactor1).resumeCompaction();
        verify(taskCompactor2).resumeCompaction();

        releaseTasks.countDown();
    }

    @Test
    void testStopAndDisablePreventsNewTasks() throws InterruptedException {
        coordinator.stopAndDisableBackgroundCompaction();
        assertFalse(coordinator.isCompactionEnabled());

        final DataFileCollection fileCollection = mock(DataFileCollection.class);
        final DataFileReader file = mockFileReader(1, 0);
        when(fileCollection.getAllCompletedFiles()).thenReturn(List.of(file));

        final AtomicInteger factoryCalls = new AtomicInteger();
        final Supplier<DataFileCompactor> factory = () -> {
            factoryCalls.incrementAndGet();
            return mock(DataFileCompactor.class);
        };

        coordinator.submitCompactionTasks(ID_TO_HASH_CHUNK, factory, CONFIGURATION.getConfigData(MerkleDbConfig.class));

        // Nothing should have been submitted
        assertFalse(coordinator.isCompactionRunning(ID_TO_HASH_CHUNK));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private void publishScanResult(final String storeName, final Map<Integer, List<DataFileReader>> result) {
        final GarbageScanner scanner = mock(GarbageScanner.class);
        when(scanner.scan()).thenReturn(result);
        coordinator.submitScanIfNotRunning(storeName, scanner);
        assertEventuallyDoesNotThrow(
                () -> verify(scanner).scan(), Duration.ofSeconds(1), "Scanner task didn't complete");
    }

    private DataFileReader mockFileReader(final int index, final int level) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getIndex()).thenReturn(index);
        when(metadata.getCompactionLevel()).thenReturn(level);

        final DataFileReader fileReader = mock(DataFileReader.class);
        when(fileReader.getIndex()).thenReturn(index);
        when(fileReader.getMetadata()).thenReturn(metadata);
        return fileReader;
    }
}
