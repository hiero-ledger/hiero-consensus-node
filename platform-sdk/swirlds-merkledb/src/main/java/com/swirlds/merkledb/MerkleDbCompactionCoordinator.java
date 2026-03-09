// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.MerkleDbDataSource.MERKLEDB_COMPONENT;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.io.utility.IORunnable;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.GarbageScanner;
import com.swirlds.merkledb.files.GarbageScanner.GarbageFileStats;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;

/**
 * Coordinates compaction tasks for a {@link MerkleDbDataSource}. Manages two kinds of background
 * tasks:
 *
 * <ul>
 *   <li><b>Scanner tasks</b> — traverse the in-memory index and compute per-file garbage
 *       statistics. At most one scanner per store runs at any time. Scanners are read-only and
 *       do not need to be paused for snapshots. Results are cached in {@link #scanResultsByStore}
 *       and shared across all compaction tasks for the same store.</li>
 *   <li><b>Compaction tasks</b> — one per level per store. Each task evaluates cached scan
 *       results at execution time (not at submission time) to decide whether compaction is
 *       needed. This ensures the evaluation uses a fresh file list even if the task waited in
 *       the queue while other levels were being compacted. Compaction tasks are paused during
 *       snapshots.</li>
 * </ul>
 *
 * <p>All tasks run on a shared thread pool. The pool size is configured via
 * {@link MerkleDbConfig#compactionThreads()}.
 */
class MerkleDbCompactionCoordinator {

    private static final Logger logger = LogManager.getLogger(MerkleDbCompactionCoordinator.class);

    // Timeout to wait for all currently running compaction tasks to stop during compactor shutdown
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 60_000;

    /**
     * An executor service to run compaction tasks. Accessed using {@link #getCompactionExecutor(MerkleDbConfig)}.
     */
    private static ExecutorService compactionExecutor = null;

    /**
     * This method is invoked from a non-static method and uses the provided configuration.
     * Consequently, the compaction executor will be initialized using the configuration provided
     * by the first instance of MerkleDbCompactionCoordinator class that calls the relevant
     * non-static method. Subsequent calls will reuse the same executor, regardless of any new
     * configurations provided.
     * FUTURE WORK: it can be moved to MerkleDb.
     */
    static synchronized ExecutorService getCompactionExecutor(final @NonNull MerkleDbConfig merkleDbConfig) {
        requireNonNull(merkleDbConfig);

        if (compactionExecutor == null) {
            compactionExecutor = new ThreadPoolExecutor(
                    merkleDbConfig.compactionThreads(),
                    merkleDbConfig.compactionThreads(),
                    50L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadConfiguration(getStaticThreadManager())
                            .setThreadGroup(new ThreadGroup("Compaction"))
                            .setComponent(MERKLEDB_COMPONENT)
                            .setThreadName("Compacting")
                            .setExceptionHandler((t, ex) ->
                                    logger.error(EXCEPTION.getMarker(), "Uncaught exception during merging", ex))
                            .buildFactory());
        }
        return compactionExecutor;
    }

    // Synchronized on this
    private boolean compactionEnabled = false;

    /**
     * Active compactors by task key (e.g. "HashStoreDisk_compact_0"). Synchronized on this.
     * Only populated when a compaction task has evaluated scan results, decided to compact,
     * and created a DataFileCompactor. Used for pause/resume during snapshots and interrupt
     * during shutdown.
     * */
    final Map<String, DataFileCompactor> compactorsByName = new HashMap<>(16);

    /**
     * Submitted compaction task keys (both queued and running) and  active scanner tasks. Synchronized on this.
     * Used to prevent duplicate task submissions. A superset of the keys in
     * compactorsByName — a task is in this set from submission until its finally block.
     * Tasks of two types can be safely combined in one set as their names are unique.
     */
    private final Set<String> tasks = new HashSet<>(20);

    /**
     * Latest scan results per store name. Written by scanner tasks, read by compaction tasks
     * when they evaluate candidates at execution time. Keys are store names (e.g. "HashStoreDisk").
     */
    private final Map<String, Map<Integer, GarbageFileStats>> scanResultsByStore = new ConcurrentHashMap<>(4);

    @NonNull
    private final MerkleDbConfig merkleDbConfig;

    /**
     * Creates a new instance of {@link MerkleDbCompactionCoordinator}.
     *
     * @param tableName      the name of the table
     * @param merkleDbConfig platform config for MerkleDbDataSource
     */
    public MerkleDbCompactionCoordinator(@NonNull String tableName, @NonNull MerkleDbConfig merkleDbConfig) {
        requireNonNull(tableName);
        requireNonNull(merkleDbConfig);
        this.merkleDbConfig = merkleDbConfig;
    }

    /**
     * Enables background compaction.
     */
    synchronized void enableBackgroundCompaction() {
        compactionEnabled = true;
    }

    /**
     * Pauses compaction of all active data file compactors while running the provided action.
     * Compaction may not stop immediately, but as soon as the compaction process needs to update
     * data source state (which is critical for snapshots, e.g. update an index), it will be
     * blocked until the action completes.
     *
     * <p>Scanner tasks are not paused because they are read-only. Compaction tasks that have been
     * submitted but have not yet created a compactor (still evaluating or queued) are also
     * unaffected — they will encounter the lock when they start writing.
     *
     * @param action action to run while compaction is paused
     */
    synchronized void pauseCompactionAndRun(IORunnable action) throws IOException {
        for (final DataFileCompactor compactor : compactorsByName.values()) {
            compactor.pauseCompaction();
        }
        try {
            action.run();
        } finally {
            for (final DataFileCompactor compactor : compactorsByName.values()) {
                compactor.resumeCompaction();
            }
        }
    }
    /**
     * Stops all compactions in progress and disables background compaction. All subsequent calls
     * to compacting methods will be ignored until {@link #enableBackgroundCompaction()} is called.
     * Scanner tasks are not interrupted (they are read-only and will finish harmlessly).
     *
     * <p>Queued compaction tasks that have not yet started will check {@code compactionEnabled}
     * when they begin execution and exit immediately.
     */
    synchronized void stopAndDisableBackgroundCompaction() {
        // Make sure no new compaction tasks are scheduled
        compactionEnabled = false;
        // Interrupt all running compaction tasks, if any
        for (final DataFileCompactor compactor : compactorsByName.values()) {
            compactor.interruptCompaction();
        }
        awaitForCurrentCompactionsToComplete(SHUTDOWN_TIMEOUT_MILLIS);
        // If some tasks are still running, there is nothing else to do than to log it
        if (!tasks.isEmpty()) {
            logger.warn(MERKLE_DB.getMarker(), "Timed out waiting to stop all compactions tasks");
        }
    }

    /**
     * Waits for all currently submitted compaction tasks to complete (both queued and actively
     * running).
     *
     * @param timeoutMillis maximum timeout to wait for compaction tasks to complete
     *                      (0 for indefinite wait)
     */
    synchronized void awaitForCurrentCompactionsToComplete(long timeoutMillis) {
        final long deadline = timeoutMillis > 0 ? System.currentTimeMillis() + timeoutMillis : Long.MAX_VALUE;
        while (!tasks.isEmpty()) {
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;

            try {
                wait(remaining);
            } catch (InterruptedException e) {
                logger.warn(MERKLE_DB.getMarker(), "Interrupted while waiting for compaction tasks to complete", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Submits a scanner task for the given store, if one is not already running. The scanner
     * traverses the in-memory index and stores the results in {@link #scanResultsByStore},
     * where compaction tasks read them at execution time.
     *
     * @param storeName store name (e.g. {@link DataFileCompactor#HASH_STORE_DISK})
     * @param scanner   the scanner task to run
     */
    synchronized void submitScanIfNotRunning(final String storeName, final GarbageScanner scanner) {
        if (!compactionEnabled) {
            return;
        }

        final String scanTaskKey = scanTaskKey(storeName);
        if (tasks.contains(scanTaskKey)) {
            return;
        }

        tasks.add(scanTaskKey);
        getCompactionExecutor(merkleDbConfig).submit(new ScannerTask(scanTaskKey, storeName, scanner));
    }

    /**
     * Discovers which compaction levels have files and submits a compaction task for each level
     * that does not already have a submitted or running task. Each task evaluates cached scan
     * results at execution time to decide whether compaction is warranted.
     *
     * <p>This method is called from the flush handler after each flush. It is lightweight — it
     * reads the file list, groups by level, and submits tasks. The evaluation and
     * compaction work happens asynchronously when the task executes.
     *
     * @param storeName        store name (e.g. {@link DataFileCompactor#HASH_STORE_DISK})
     * @param fileCollection   the file collection for this store
     * @param compactorFactory creates a fresh {@link DataFileCompactor} per compaction task
     * @param config           MerkleDb config with threshold parameters
     */
    synchronized void submitCompactionTasks(
            final String storeName,
            final DataFileCollection fileCollection,
            final Supplier<DataFileCompactor> compactorFactory,
            final MerkleDbConfig config) {
        if (!compactionEnabled) {
            return;
        }

        // Discover levels from the current file list
        final Set<Integer> levels = fileCollection.getAllCompletedFiles().stream()
                .map(f -> f.getMetadata().getCompactionLevel())
                .collect(Collectors.toSet());

        final ExecutorService executor = getCompactionExecutor(merkleDbConfig);
        for (final int level : levels) {
            final String taskKey = compactionTaskKey(storeName, level);
            if (tasks.contains(taskKey)) {
                // A task for this store and level is already queued or running
                continue;
            }

            tasks.add(taskKey);
            executor.submit(new CompactionTask(taskKey, storeName, level, fileCollection, compactorFactory, config));
        }
    }

    /**
     * Checks if any compaction task is currently submitted or running for the given store.
     * This checks all levels — if any level has a queued or active task, this returns
     * {@code true}.
     *
     * @param storeName store name (e.g. {@link DataFileCompactor#OBJECT_KEY_TO_PATH})
     * @return {@code true} if any compaction for this store is submitted or running
     */
    synchronized boolean isCompactionRunning(final String storeName) {
        final String prefix = storeName + "_compact_";
        for (final String key : tasks) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean isCompactionEnabled() {
        return compactionEnabled;
    }

    private static String scanTaskKey(final String storeName) {
        return storeName + "_scan";
    }

    private static String compactionTaskKey(final String storeName, final int level) {
        return storeName + "_compact_" + level;
    }

    /**
     * Background task that traverses the in-memory index and computes per-file garbage statistics.
     * Results are stored in {@link #scanResultsByStore} for compaction tasks to consume.
     */
    private class ScannerTask implements Callable<Map<Integer, GarbageFileStats>> {

        private final String taskKey;
        private final String storeName;
        private final GarbageScanner scanner;

        ScannerTask(final String taskKey, final String storeName, final GarbageScanner scanner) {
            this.taskKey = taskKey;
            this.storeName = storeName;
            this.scanner = scanner;
        }

        @Override
        public Map<Integer, GarbageFileStats> call() {
            try {
                final Map<Integer, GarbageFileStats> result = scanner.scan();
                scanResultsByStore.put(storeName, result);
                return result;
            } catch (Exception e) {
                logger.error(EXCEPTION.getMarker(), "[{}] Garbage scan failed", taskKey, e);
            } finally {
                synchronized (MerkleDbCompactionCoordinator.this) {
                    tasks.remove(taskKey);
                    MerkleDbCompactionCoordinator.this.notifyAll();
                }
            }
            return Map.of();
        }
    }

    /**
     * Background task that evaluates cached scan results for a single level and compacts if
     * thresholds are exceeded. The evaluation happens at execution time, not at submission
     * time, so the task always uses the most recent scan results and file list — even if it
     * waited in the queue while other compactions ran.
     *
     * <p>If no scan results are available yet (e.g. the scanner hasn't completed), the task
     * is a no-op. The next flush will submit a new task for this level.
     */
    private class CompactionTask implements Callable<Boolean> {

        private final String taskKey;
        private final String storeName;
        private final int sourceLevel;
        private final DataFileCollection fileCollection;
        private final Supplier<DataFileCompactor> compactorFactory;
        private final MerkleDbConfig config;

        CompactionTask(
                @NonNull final String taskKey,
                @NonNull final String storeName,
                final int sourceLevel,
                @NonNull final DataFileCollection fileCollection,
                @NonNull final Supplier<DataFileCompactor> compactorFactory,
                @NonNull final MerkleDbConfig config) {
            this.taskKey = taskKey;
            this.storeName = storeName;
            this.sourceLevel = sourceLevel;
            this.fileCollection = fileCollection;
            this.compactorFactory = compactorFactory;
            this.config = config;
        }

        @Override
        public Boolean call() {
            try {
                // Exit early if compaction was disabled while this task was queued
                if (!isCompactionEnabled()) {
                    return false;
                }

                // Read cached scan results
                final Map<Integer, GarbageFileStats> scanResult = scanResultsByStore.get(storeName);
                if (scanResult == null) {
                    // No scan results available yet — scanner hasn't completed. This is
                    // normal during early startup. The next flush will submit a new task.
                    return false;
                }
                // Evaluate candidates for this level using fresh file list
                final List<DataFileReader> allFiles = fileCollection.getAllCompletedFiles();
                final Map<Integer, List<DataFileReader>> candidatesByLevel =
                        GarbageScanner.evaluateCompactionCandidates(
                                scanResult,
                                allFiles,
                                config.garbageThreshold(),
                                config.maxCompactionDataPerLevelInKB());

                final List<DataFileReader> filesToCompact = candidatesByLevel.get(sourceLevel);
                if (filesToCompact == null || filesToCompact.isEmpty()) {
                    // Nothing to compact at this level — thresholds not exceeded
                    return false;
                }

                // Create a compactor and register it for pause/resume/interrupt
                final DataFileCompactor compactor = compactorFactory.get();
                synchronized (MerkleDbCompactionCoordinator.this) {
                    if (!isCompactionEnabled()) {
                        return false;
                    }
                    compactorsByName.put(taskKey, compactor);
                }

                final int targetLevel = Math.min(sourceLevel + 1, config.maxCompactionLevel());
                return compactor.compactSingleLevel(filesToCompact, targetLevel);

            } catch (final InterruptedException | ClosedByInterruptException e) {
                logger.info(MERKLE_DB.getMarker(), "Interrupted while compacting [{}], this is allowed", taskKey);
            } catch (Exception e) {
                // It is important that we capture all exceptions here, otherwise a single exception
                // will stop all future merges from happening
                logger.error(EXCEPTION.getMarker(), "[{}] Compaction failed", taskKey, e);
            } finally {
                synchronized (MerkleDbCompactionCoordinator.this) {
                    compactorsByName.remove(taskKey);
                    tasks.remove(taskKey);
                    MerkleDbCompactionCoordinator.this.notifyAll();
                }
            }
            return false;
        }
    }
}
