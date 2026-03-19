// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.MerkleDbDataSource.MERKLEDB_COMPONENT;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.io.utility.IORunnable;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.merkledb.files.GarbageScanner;
import com.swirlds.merkledb.files.GarbageScanner.IndexedGarbageFileStats;
import com.swirlds.merkledb.files.GarbageScanner.ScanResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
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
 *   <li><b>Compaction tasks</b> — multiple tasks per level per store. The coordinator partitions
 *       eligible files into chunks bounded by projected output size
 *       ({@link MerkleDbConfig#maxCompactionDataPerLevelInKB()}), and submits each chunk as an
 *       independent task. Tasks at different levels and within the same level run concurrently.
 *       New chunks for a level are only submitted once ALL previous tasks for that level have
 *       completed. Compaction tasks are paused during snapshots.</li>
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
     * Active compactors by task key (e.g. "IdToHashChunk_compact_0_1"). Synchronized on this.
     * Only populated when a compaction task has created a DataFileCompactor and is actively
     * compacting. Used for pause/resume during snapshots and interrupt during shutdown.
     */
    final Map<String, DataFileCompactor> compactorsByName = new HashMap<>(16);

    /**
     * All active task keys — both scanner tasks and compaction tasks. Synchronized on this.
     * Used for {@link #awaitForCurrentCompactionsToComplete(long)} and
     * {@link #isCompactionRunning(String)}.
     */
    private final Set<String> tasks = new HashSet<>(20);

    /**
     * Number of outstanding (queued + running) compaction tasks per level key.
     * Key format: "storeName_compact_level" (e.g. "IdToHashChunk_compact_0").
     * New tasks for a level are only submitted when the count reaches zero, ensuring
     * all chunks from the previous cycle finish before a fresh scan result is consumed.
     * Synchronized on this.
     */
    private final Map<String, Integer> compactionTaskCounts = new HashMap<>(16);

    /**
     * Latest scan results per store name. Written by scanner tasks, read by
     * {@link #submitCompactionTasks} to partition candidates into chunks.
     * Keys are store names (e.g. "IdToHashChunk").
     */
    private final Map<String, ScanResult> scanResultsByStore = new ConcurrentHashMap<>(4);

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
     * submitted but have not yet created a compactor (still queued) are also unaffected — they
     * will encounter the lock when they start writing.
     *
     * @param action action to run while compaction is paused
     */
    synchronized void pauseCompactionAndRun(IORunnable action) throws IOException {
        try {
            for (final DataFileCompactor compactor : compactorsByName.values()) {
                compactor.pauseCompaction();
            }
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
        compactionEnabled = false;
        for (final DataFileCompactor compactor : compactorsByName.values()) {
            compactor.interruptCompaction();
        }
        awaitForCurrentCompactionsToComplete(SHUTDOWN_TIMEOUT_MILLIS);
        if (!tasks.isEmpty()) {
            logger.warn(MERKLE_DB.getMarker(), "Timed out waiting to stop all compaction tasks");
        }
    }

    /**
     * Waits for all currently submitted tasks to complete (both queued and actively running,
     * including both scanner and compaction tasks).
     *
     * @param timeoutMillis maximum timeout to wait for tasks to complete (0 for indefinite wait)
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
     * where {@link #submitCompactionTasks} reads them to partition work.
     *
     * @param storeName store name (e.g. {@link MerkleDbDataSource#ID_TO_HASH_CHUNK})
     * @param scanner   the scanner to run
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
     * Partitions compaction candidates from the latest scan results into chunks bounded by
     * projected output size, and submits each chunk as an independent compaction task.
     *
     * <p>For each level present in the scan results, new tasks are only submitted when ALL
     * tasks from the previous submission for that level have completed (counter reaches zero).
     * This prevents overlapping file sets between old and new tasks.
     *
     * <p>This method is called from the flush handler after each flush. It is lightweight — it
     * reads cached scan results, partitions by projected output size, and submits tasks.
     *
     * @param storeName        store name (e.g. {@link MerkleDbDataSource#ID_TO_HASH_CHUNK})
     * @param compactorFactory creates a fresh {@link DataFileCompactor} per compaction task
     * @param config           MerkleDb config with size cap and level cap parameters
     */
    synchronized void submitCompactionTasks(
            final String storeName, final Supplier<DataFileCompactor> compactorFactory, final MerkleDbConfig config) {
        if (!compactionEnabled) {
            return;
        }

        final ScanResult scanResult = scanResultsByStore.get(storeName);
        if (scanResult == null || scanResult.candidatesByLevel().isEmpty()) {
            return;
        }

        final long maxProjectedBytes = config.maxCompactionDataPerLevelInKB() * KIBIBYTES_TO_BYTES;
        final ExecutorService executor = getCompactionExecutor(merkleDbConfig);

        for (final Map.Entry<Integer, List<DataFileReader>> entry :
                scanResult.candidatesByLevel().entrySet()) {
            final int level = entry.getKey();
            final String levelKey = compactionTaskKey(storeName, level);

            // Only submit new chunks when ALL previous tasks for this level have finished
            if (compactionTaskCounts.getOrDefault(levelKey, 0) > 0) {
                continue;
            }

            final List<DataFileReader> candidates = entry.getValue();
            if (candidates.isEmpty()) {
                continue;
            }

            final List<List<DataFileReader>> chunks =
                    splitIntoChunks(candidates, maxProjectedBytes, scanResult.stats());

            compactionTaskCounts.put(levelKey, chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                final String taskKey = levelKey + "_" + i;
                tasks.add(taskKey);
                executor.submit(new CompactionTask(taskKey, levelKey, level, chunks.get(i), compactorFactory, config));
            }

            if (chunks.size() > 1) {
                logger.info(
                        MERKLE_DB.getMarker(),
                        "[{}] Submitted {} compaction tasks for level {} ({} candidate files)",
                        storeName,
                        chunks.size(),
                        level,
                        candidates.size());
            }
        }
    }

    /**
     * Checks if any compaction task is currently submitted or running for the given store.
     * This checks all levels — if any level has a queued or active task, this returns
     * {@code true}.
     *
     * @param storeName store name (e.g. {@link MerkleDbDataSource#OBJECT_KEY_TO_PATH})
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

    // ========================================================================
    // Task key helpers
    // ========================================================================

    private static String scanTaskKey(final String storeName) {
        return storeName + "_scan";
    }

    private static String compactionTaskKey(final String storeName, final int level) {
        return storeName + "_compact_" + level;
    }

    // ========================================================================
    // Chunking
    // ========================================================================

    /**
     * Partitions candidates into chunks where each chunk's projected output size fits within
     * the cap. Files are taken in iteration order (file index order from the scanner) without
     * sorting. At least one file per chunk is always included.
     *
     * @param candidates        files eligible for compaction at a single level
     * @param maxProjectedBytes maximum projected alive bytes per chunk, or &le; 0 to disable
     * @param stats             per-file garbage statistics from the scan
     * @return list of chunks; each chunk is a non-empty sublist of candidates
     */
    static List<List<DataFileReader>> splitIntoChunks(
            final List<DataFileReader> candidates, final long maxProjectedBytes, final IndexedGarbageFileStats stats) {
        if (maxProjectedBytes <= 0) {
            return List.of(candidates);
        }

        final List<List<DataFileReader>> chunks = new ArrayList<>();
        List<DataFileReader> currentChunk = new ArrayList<>();
        long currentProjectedSize = 0;

        for (final DataFileReader reader : candidates) {
            final long projectedAlive = estimateAliveBytes(reader, stats);
            if (!currentChunk.isEmpty() && currentProjectedSize + projectedAlive > maxProjectedBytes) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentProjectedSize = 0;
            }
            currentChunk.add(reader);
            currentProjectedSize += projectedAlive;
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }

        return chunks;
    }

    /**
     * Estimates the projected alive bytes for a single file based on scan statistics.
     * Returns 0 for files with unknown item counts or files not found in the stats
     * (e.g. deleted between scan and chunking).
     */
    static long estimateAliveBytes(final DataFileReader reader, final IndexedGarbageFileStats stats) {
        if (stats == null) {
            return 0;
        }
        final int idx = reader.getIndex() - stats.offset();
        if (idx < 0 || idx >= stats.garbageFileStats().length) {
            return 0;
        }
        final GarbageScanner.GarbageFileStats fileStats = stats.garbageFileStats()[idx];
        if (fileStats == null || fileStats.totalItems() == 0) {
            return 0;
        }
        return (long) (reader.getSize() * (1.0 - fileStats.garbageRatio()));
    }

    // ========================================================================
    // Inner task classes
    // ========================================================================

    /**
     * Background task that traverses the in-memory index and computes per-file garbage statistics.
     * Results are stored in {@link #scanResultsByStore} for compaction tasks to consume.
     */
    private class ScannerTask implements Runnable {

        private final String taskKey;
        private final String storeName;
        private final GarbageScanner scanner;

        ScannerTask(final String taskKey, final String storeName, final GarbageScanner scanner) {
            this.taskKey = taskKey;
            this.storeName = storeName;
            this.scanner = scanner;
        }

        @Override
        public void run() {
            try {
                scanResultsByStore.put(storeName, scanner.scan());
            } catch (Exception e) {
                logger.error(EXCEPTION.getMarker(), "[{}] Garbage scan failed", taskKey, e);
            } finally {
                synchronized (MerkleDbCompactionCoordinator.this) {
                    tasks.remove(taskKey);
                    MerkleDbCompactionCoordinator.this.notifyAll();
                }
            }
        }
    }

    /**
     * Background compaction task for a pre-assigned chunk of files at a single level. The chunk
     * is determined at submission time by {@link #submitCompactionTasks}, which partitions
     * candidates by projected output size.
     *
     * <p>Before compacting, the task filters out files that may have been deleted by concurrent
     * compaction tasks since the scan. If no valid files remain, the task is a no-op.
     */
    private class CompactionTask implements Callable<Boolean> {

        private final String taskKey;
        private final String levelKey;
        private final int sourceLevel;
        private final List<DataFileReader> assignedFiles;
        private final Supplier<DataFileCompactor> compactorFactory;
        private final MerkleDbConfig config;

        CompactionTask(
                @NonNull final String taskKey,
                @NonNull final String levelKey,
                final int sourceLevel,
                @NonNull final List<DataFileReader> assignedFiles,
                @NonNull final Supplier<DataFileCompactor> compactorFactory,
                @NonNull final MerkleDbConfig config) {
            this.taskKey = taskKey;
            this.levelKey = levelKey;
            this.sourceLevel = sourceLevel;
            this.assignedFiles = assignedFiles;
            this.compactorFactory = compactorFactory;
            this.config = config;
        }

        @Override
        public Boolean call() {
            try {
                if (!isCompactionEnabled()) {
                    return false;
                }

                final DataFileCompactor compactor = compactorFactory.get();
                synchronized (MerkleDbCompactionCoordinator.this) {
                    if (!isCompactionEnabled()) {
                        return false;
                    }
                    compactorsByName.put(taskKey, compactor);
                }

                // Filter out files that were already compacted and deleted since the scan
                final Set<DataFileReader> currentFiles =
                        new HashSet<>(compactor.getDataFileCollection().getAllCompletedFiles());
                final List<DataFileReader> validFiles =
                        assignedFiles.stream().filter(currentFiles::contains).toList();
                if (validFiles.isEmpty()) {
                    return false;
                }

                final int targetLevel = Math.min(sourceLevel + 1, config.maxCompactionLevel());
                return compactor.compactSingleLevel(validFiles, targetLevel);

            } catch (final InterruptedException | ClosedByInterruptException e) {
                logger.info(MERKLE_DB.getMarker(), "Interrupted while compacting [{}], this is allowed", taskKey);
            } catch (Exception e) {
                logger.error(EXCEPTION.getMarker(), "[{}] Compaction failed", taskKey, e);
            } finally {
                synchronized (MerkleDbCompactionCoordinator.this) {
                    compactorsByName.remove(taskKey);
                    tasks.remove(taskKey);
                    final int remaining = compactionTaskCounts.merge(levelKey, -1, Integer::sum);
                    if (remaining <= 0) {
                        compactionTaskCounts.remove(levelKey);
                    }
                    MerkleDbCompactionCoordinator.this.notifyAll();
                }
            }
            return false;
        }
    }
}
