// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.runTaskAndCleanThreadLocals;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.test.fixtures.TestType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration tests to verify that compaction tasks can be interrupted and stop correctly when
 * {@link MerkleDbCompactionCoordinator#stopAndDisableBackgroundCompaction()} is called. This
 * includes cases where compaction is interrupted mid-flight and cases where compaction is
 * interrupted while paused for a snapshot.
 *
 * <p>With the V3 compaction API, individual {@link DataFileCompactor} instances are created
 * internally by the coordinator and are not accessible from the test. Assertions are therefore
 * limited to coordinator-level state: {@code compactionEnabled}, {@code compactorsByName},
 * executor queue, and per-store {@code isCompactionRunning()} checks.
 */
class CompactionInterruptTest {

    /** This needs to be big enough so that the snapshot is slow enough that we can do a merge at the same time */
    private static final int COUNT = 2_000_000;

    /**
     * Temporary directory provided by JUnit
     */
    @SuppressWarnings("unused")
    @TempDir
    Path tmpFileDir;

    /*
     * RUN THE TEST IN A BACKGROUND THREAD. We do this so that we can kill the thread at the end of
     * the test which will clean up all thread local caches held.
     */
    @Test
    void startMergeThenInterrupt() throws Exception {
        runTaskAndCleanThreadLocals(this::startMergeThenInterruptImpl);
    }

    /**
     * Trigger compaction for all three stores, then call stopAndDisableBackgroundCompaction().
     * The expected result is that all tasks are cleaned up and the coordinator reaches a
     * quiescent state quickly.
     */
    boolean startMergeThenInterruptImpl() throws IOException, InterruptedException {
        final Path storeDir = tmpFileDir.resolve("startMergeThenInterruptImpl");
        final String tableName = "mergeThenInterrupt";
        final MerkleDbDataSource dataSource = TestType.variable_variable
                .dataType()
                .createDataSource(CONFIGURATION, storeDir, tableName, COUNT, false, false);
        final MerkleDbCompactionCoordinator coordinator = dataSource.getCompactionCoordinator();

        try {
            // Create data in batches so that files accumulate content worth compacting
            createData(dataSource);
            coordinator.enableBackgroundCompaction();

            final ThreadPoolExecutor compactingExecutor =
                    (ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                            CONFIGURATION.getConfigData(MerkleDbConfig.class));
            final long initialCompletedTaskCount = compactingExecutor.getTaskCount();
            // start compaction
            final DataFileCompactor hashStoreDiskCompactor = dataSource.newHashChunkStoreCompactor();
            coordinator.compactIfNotRunningYet("idToHashChunk", hashStoreDiskCompactor);
            final DataFileCompactor keyToPathCompactor = dataSource.newKeyToPathCompactor();
            coordinator.compactIfNotRunningYet("keyToPath", keyToPathCompactor);
            final DataFileCompactor pathToKeyValueCompactor = dataSource.newKeyValueStoreCompactor();
            coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValueCompactor);
            // wait a small-time for merging to start
            MILLISECONDS.sleep(20);
            stopCompactionAndVerifyItsStopped(
                    coordinator,
                    compactingExecutor,
                    initialCompletedTaskCount,
                    hashStoreDiskCompactor,
                    keyToPathCompactor,
                    pathToKeyValueCompactor);
        } finally {
            dataSource.close();
        }
        return true;
    }

    /**
     * RUN THE TEST IN A BACKGROUND THREAD. Different delays provide slightly different interrupt
     * timing — sometimes the interrupt hits early in compaction, sometimes later, and sometimes
     * while paused for the snapshot. In one case the result is an {@link InterruptedException},
     * in another it may be a {@link java.nio.channels.ClosedByInterruptException}. Both are
     * acceptable.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 50, 200, 1000, 4000})
    void startMergeWhileSnapshottingThenInterrupt(int delayMs) throws Exception {
        runTaskAndCleanThreadLocals(() -> startMergeWhileSnapshottingThenInterruptImpl(delayMs));
    }

    /**
     * Trigger compaction while in the middle of snapshotting, and then call
     * stopAndDisableBackgroundCompaction() and close the database. The expected result is all
     * tasks are interrupted and the database closes promptly without being blocked by the
     * snapshot or compaction.
     */
    boolean startMergeWhileSnapshottingThenInterruptImpl(int delayMs) throws IOException, InterruptedException {
        final Path storeDir = tmpFileDir.resolve("startMergeWhileSnapshottingThenInterruptImpl");
        final String tableName = "mergeWhileSnapshotting";
        final MerkleDbDataSource dataSource = TestType.variable_variable
                .dataType()
                .createDataSource(CONFIGURATION, storeDir, tableName, COUNT, false, false);
        final MerkleDbCompactionCoordinator coordinator = dataSource.getCompactionCoordinator();

        final ExecutorService exec = Executors.newCachedThreadPool();
        try {
            // Create data in batches so that files accumulate content worth compacting
            createData(dataSource);
            coordinator.enableBackgroundCompaction();

            // Start a snapshot in the background
            final Path snapshotDir = tmpFileDir.resolve("startMergeWhileSnapshottingThenInterruptImplSnapshot");
            exec.submit(() -> {
                dataSource.snapshot(snapshotDir);
                return null;
            });

            final ThreadPoolExecutor compactingExecutor =
                    (ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                            CONFIGURATION.getConfigData(MerkleDbConfig.class));
            final long initialCompletedTaskCount = compactingExecutor.getTaskCount();
            // we should take into account previous test runs
            long initTaskCount = compactingExecutor.getTaskCount();
            // start compaction for all three storages
            final DataFileCompactor hashStoreDiskCompactor = dataSource.newHashChunkStoreCompactor();
            coordinator.compactIfNotRunningYet("idToHashChunk", hashStoreDiskCompactor);
            final DataFileCompactor keyToPathCompactor = dataSource.newKeyToPathCompactor();
            coordinator.compactIfNotRunningYet("keyToPath", keyToPathCompactor);
            final DataFileCompactor pathToKeyValueCompactor = dataSource.newKeyValueStoreCompactor();
            coordinator.compactIfNotRunningYet("pathToKeyValue", pathToKeyValueCompactor);

            assertEventuallyEquals(
                    initTaskCount + 3L,
                    compactingExecutor::getTaskCount,
                    Duration.ofMillis(20),
                    "Unexpected number of tasks " + compactingExecutor.getTaskCount());
            // wait a small-time for merging to start (or don't wait at all)
            Thread.sleep(delayMs);
            stopCompactionAndVerifyItsStopped(
                    coordinator,
                    compactingExecutor,
                    initialCompletedTaskCount,
                    hashStoreDiskCompactor,
                    keyToPathCompactor,
                    pathToKeyValueCompactor);
        } finally {
            dataSource.close();
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "Should not timeout");
        }
        return true;
    }

    /**
     * Stops compaction and verifies that the coordinator reaches a clean quiescent state.
     *
     * <p>Шndividual compactor instances are created internally by the
     * coordinator — the test cannot inspect them directly. We verify coordinator-level
     * invariants instead:
     * <ul>
     *   <li>{@code compactionEnabled} is false</li>
     *   <li>{@code compactorsByName} is empty (all tasks finished or were interrupted)</li>
     *   <li>No per-store compaction is detected as running</li>
     *   <li>Executor queue is drained</li>
     * </ul>
     */
    private static void stopCompactionAndVerifyItsStopped(
            final MerkleDbCompactionCoordinator coordinator,
            final ThreadPoolExecutor compactingExecutor,
            final long initialCompletedTaskCount,
            final DataFileCompactor... compactors) {
        for (final DataFileCompactor compactor : compactors) {
            assertEventuallyTrue(
                    () -> compactor.isCompactionRunning() || compactor.isCompactionComplete(),
                    Duration.ofMillis(10),
                    "compactor should be complete or running");
        }

        // stopping the compaction
        coordinator.stopAndDisableBackgroundCompaction();

        assertFalse(coordinator.isCompactionEnabled(), "compactionEnabled should be false");

        for (final DataFileCompactor compactor : compactors) {
            assertTrue(
                    compactor.isCompactionComplete() || !compactor.notInterrupted(),
                    "compactor should be complete or interrupted");
        }

        synchronized (coordinator) {
            assertTrue(coordinator.compactorsByName.isEmpty(), "compactorsByName should be empty");
        }
        assertEventuallyEquals(
                0, () -> compactingExecutor.getQueue().size(), Duration.ofMillis(100), "The queue should be empty");
        long expectedTaskCount = initialCompletedTaskCount + compactors.length;
        assertEventuallyEquals(
                expectedTaskCount,
                compactingExecutor::getCompletedTaskCount,
                Duration.ofMillis(100),
                "Unexpected number of completed tasks - %s, expected - %s"
                        .formatted(compactingExecutor.getCompletedTaskCount(), expectedTaskCount));
    }

    private void createData(final MerkleDbDataSource dataSource) throws IOException {
        final int count = COUNT / 10;
        for (int batch = 0; batch < 10; batch++) {
            final int start = batch * count;
            final int end = start + count;
            final int lastLeafPath = (COUNT + end) - 1;
            dataSource.saveRecords(
                    COUNT,
                    lastLeafPath,
                    createHashChunkStream(start, end - 1, i -> i, dataSource.getHashChunkHeight()),
                    IntStream.range(COUNT + start, COUNT + end)
                            .mapToObj(i -> TestType.variable_variable.dataType().createVirtualLeafRecord(i)),
                    Stream.empty(),
                    false);
        }
    }
}
