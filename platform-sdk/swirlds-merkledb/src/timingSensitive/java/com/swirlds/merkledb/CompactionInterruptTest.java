// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.runTaskAndCleanThreadLocals;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The idea of these tests is to make sure that the merging method when run in a background thread can be interrupted
 * and stops correctly. Even when it is blocked paused waiting on snapshot process.
 */
class CompactionInterruptTest {

    /** This needs to be big enough so that the snapshot is slow enough that we can do a merge at the same time */
    private static final int COUNT = 1_000_000;

    /**
     * Temporary directory provided by JUnit
     */
    @SuppressWarnings("unused")
    @TempDir
    Path tmpFileDir;

    /*
     * RUN THE TEST IN A BACKGROUND THREAD. We do this so that we can kill the thread at the end of the test which will
     * clean up all thread local caches held.
     */
    @Test
    void startMergeThenInterrupt() throws Exception {
        runTaskAndCleanThreadLocals(this::startMergeThenInterruptImpl);
    }

    /**
     * Run a test to do a compaction, and then call stopAndDisableBackgroundCompaction(). The expected result is the merging thread
     * should be interrupted and end quickly.
     */
    boolean startMergeThenInterruptImpl() throws IOException, InterruptedException {
        final Path storeDir = tmpFileDir.resolve("startMergeThenInterruptImpl");
        String tableName = "mergeThenInterrupt";
        final MerkleDbDataSource dataSource =
                TestType.variable_variable.dataType().createDataSource(storeDir, tableName, COUNT, 0, false, true);
        final MerkleDbCompactionCoordinator coordinator = dataSource.getCompactionCoordinator();

        try {
            // create some internal and leaf nodes in batches
            createData(dataSource);
            coordinator.enableBackgroundCompaction();
            // start compaction
            coordinator.compactIfNotRunningYet("hashStoreDisk", dataSource.newHashStoreDiskCompactor());
            coordinator.compactIfNotRunningYet("keyToPath", dataSource.newKeyToPathCompactor());
            coordinator.compactIfNotRunningYet("pathToKeyValue", dataSource.newPathToKeyValueCompactor());
            // wait a small-time for merging to start
            MILLISECONDS.sleep(20);
            stopCompactionAndVerifyItsStopped(tableName, coordinator);
        } finally {
            dataSource.close();
        }
        return true;
    }

    /**
     * RUN THE TEST IN A BACKGROUND THREAD. We do this so that we can kill the thread at the end of the test which will
     * clean up all thread local caches held.
     * Different delays in the test provide slightly different results in terms of how exactly compaction fails.
     * In one case it's {@link InterruptedException}, in the other case it's {@link ClosedByInterruptException}.
     * Both are acceptable.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 50})
    void startMergeWhileSnapshottingThenInterrupt(int delayMs) throws Exception {
        runTaskAndCleanThreadLocals(() -> startMergeWhileSnapshottingThenInterruptImpl(delayMs));
    }

    /**
     * Run a test to do a merge while in the middle of snapshotting, and then call stopAndDisableBackgroundCompaction() and close
     * the database. The expected result is the merging thread should be interrupted and end immediately and not be
     * blocked by the snapshotting. Check to make sure the database closes immediately and is not blocked waiting for
     * merge thread to exit. DataFileCommon.waitIfMergingPaused() used to eat interrupted exceptions that would block
     * the merging thread from a timely exit and fail this test.
     */
    boolean startMergeWhileSnapshottingThenInterruptImpl(int delayMs) throws IOException, InterruptedException {
        final Path storeDir = tmpFileDir.resolve("startMergeWhileSnapshottingThenInterruptImpl");
        String tableName = "mergeWhileSnapshotting";
        final MerkleDbDataSource dataSource =
                TestType.variable_variable.dataType().createDataSource(storeDir, tableName, COUNT, 0, false, true);
        final MerkleDbCompactionCoordinator coordinator = dataSource.getCompactionCoordinator();

        final ExecutorService exec = Executors.newCachedThreadPool();
        try {
            // create some internal and leaf nodes in batches
            createData(dataSource);
            coordinator.enableBackgroundCompaction();
            // create a snapshot
            final Path snapshotDir = tmpFileDir.resolve("startMergeWhileSnapshottingThenInterruptImplSnapshot");
            exec.submit(() -> {
                dataSource.snapshot(snapshotDir);
                return null;
            });

            ThreadPoolExecutor compactingExecutor =
                    (ThreadPoolExecutor) MerkleDbCompactionCoordinator.getCompactionExecutor(
                            CONFIGURATION.getConfigData(MerkleDbConfig.class));
            // we should take into account previous test runs
            long initTaskCount = compactingExecutor.getTaskCount();
            // start compaction for all three storages
            coordinator.compactIfNotRunningYet("hashStoreDisk", dataSource.newHashStoreDiskCompactor());
            coordinator.compactIfNotRunningYet("keyToPath", dataSource.newKeyToPathCompactor());
            coordinator.compactIfNotRunningYet("pathToKeyValue", dataSource.newPathToKeyValueCompactor());

            assertEventuallyEquals(
                    initTaskCount + 3L,
                    compactingExecutor::getTaskCount,
                    Duration.ofMillis(20),
                    "Unexpected number of tasks " + compactingExecutor.getTaskCount());
            // wait a small-time for merging to start (or don't wait at all)
            Thread.sleep(delayMs);
            stopCompactionAndVerifyItsStopped(tableName, coordinator);
        } finally {
            dataSource.close();
            exec.shutdown();
            assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "Should not timeout");
        }
        return true;
    }

    private static void stopCompactionAndVerifyItsStopped(String tableName, MerkleDbCompactionCoordinator compactor) {
        ThreadPoolExecutor compactingExecutor = (ThreadPoolExecutor)
                MerkleDbCompactionCoordinator.getCompactionExecutor(CONFIGURATION.getConfigData(MerkleDbConfig.class));
        long initCount = compactingExecutor.getCompletedTaskCount();

        // getting access to the guts of the compactor to check the state of the futures
        final Future<Boolean> hashStoreDiskFuture = compactor.futuresByName.get("hashStoreDisk");
        final Future<Boolean> pathToKeyValueFuture = compactor.futuresByName.get("pathToKeyValue");
        final Future<Boolean> objectKeyToPathFuture = compactor.futuresByName.get("keyToPath");

        // stopping the compaction
        compactor.stopAndDisableBackgroundCompaction();

        assertFalse(compactor.isCompactionEnabled(), "compactionEnabled should be false");

        assertFutureCancelled(hashStoreDiskFuture, "hashStoreDiskFuture should have been cancelled");
        assertFutureCancelled(pathToKeyValueFuture, "pathToKeyValueFuture should have been cancelled");
        assertFutureCancelled(objectKeyToPathFuture, "objectKeyToPathFuture should have been cancelled");
        assertTrue(compactor.futuresByName.isEmpty(), "compactionFuturesByName should be empty");
        assertEventuallyEquals(
                0, () -> compactingExecutor.getQueue().size(), Duration.ofMillis(100), "The queue should be empty");
        long expectedTaskCount = initCount + 3;
        assertEventuallyEquals(
                expectedTaskCount,
                compactingExecutor::getCompletedTaskCount,
                Duration.ofMillis(100),
                "Unexpected number of completed tasks - %s, expected - %s"
                        .formatted(compactingExecutor.getCompletedTaskCount(), expectedTaskCount));
    }

    private static void assertFutureCancelled(Future<Boolean> hashStoreDiskFuture, String message) {
        assertEventuallyTrue(hashStoreDiskFuture::isCancelled, Duration.ofMillis(10), message);
    }

    private void createData(final MerkleDbDataSource dataSource) throws IOException {
        final int count = COUNT / 10;
        for (int batch = 0; batch < 10; batch++) {
            final int start = batch * count;
            final int end = start + count;
            final int lastLeafPath = (COUNT + end) - 1;
            final KeySerializer keySerializer =
                    TestType.variable_variable.dataType().getKeySerializer();
            final ValueSerializer valueSerializer =
                    TestType.variable_variable.dataType().getValueSerializer();
            dataSource.saveRecords(
                    COUNT,
                    lastLeafPath,
                    IntStream.range(start, end).mapToObj(MerkleDbDataSourceTest::createVirtualInternalRecord),
                    IntStream.range(COUNT + start, COUNT + end)
                            .mapToObj(i -> TestType.variable_variable.dataType().createVirtualLeafRecord(i))
                            .map(r -> r.toBytes(keySerializer, valueSerializer)),
                    Stream.empty());
        }
    }
}
