// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.MerkleDbDataSource.MERKLEDB_COMPONENT;
import static com.swirlds.merkledb.files.DataFileCommon.formatSizeBytes;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.io.utility.IORunnable;
import com.swirlds.merkledb.GarbageScanner.GarbageFileStats;
import com.swirlds.merkledb.GarbageScanner.IndexedGarbageFileStats;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 *       do not need to be paused for snapshots. Results are cached in {@link #scanStatsByStore}
 *       and shared across all compaction tasks for the same store.</li>
 *   <li><b>Compaction tasks</b> — multiple tasks per level per store. The coordinator filters
 *       files by {@code gcRateThreshold}, partitions eligible files into groups bounded by
 *       projected output size ({@link MerkleDbConfig#maxCompactedFileSizeInMB()}), absorbs
 *       additional files into each group (phase 2), and submits each group as an independent
 *       task. Tasks at different levels and within the same level run concurrently.
 *       New groups for a level are only submitted once ALL previous tasks for that level have
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
                            .setExceptionHandler((_, ex) ->
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
    private final Set<String> taskKeys = new HashSet<>(20);

    /**
     * Number of outstanding (queued + running) compaction tasks per level key.
     * Key format: "storeName_compact_level" (e.g. "IdToHashChunk_compact_0").
     * New tasks for a level are only submitted when the count reaches zero, ensuring
     * all groups from the previous cycle finish before a fresh scan result is consumed.
     * Synchronized on this.
     */
    private final Map<String, Integer> compactionTaskCounts = new HashMap<>(16);

    /**
     * Latest scan statistics per store name. Written by scanner tasks, read by
     * {@link #submitCompactionTasks} to filter and partition candidates.
     * Keys are store names (e.g. "IdToHashChunk").
     */
    private final Map<String, IndexedGarbageFileStats> scanStatsByStore = new ConcurrentHashMap<>(4);

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
    synchronized void pauseCompactionAndRun(final @NonNull IORunnable action) throws IOException {
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
        if (!taskKeys.isEmpty()) {
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
        while (!taskKeys.isEmpty()) {
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
     * traverses the in-memory index and stores the results in {@link #scanStatsByStore},
     * where {@link #submitCompactionTasks} reads them to filter and partition work.
     *
     * @param storeName store name (e.g. {@link MerkleDbDataSource#ID_TO_HASH_CHUNK})
     * @param scanner   the scanner to run
     */
    synchronized void submitScanIfNotRunning(final @NonNull String storeName, final @NonNull GarbageScanner scanner) {
        if (!compactionEnabled) {
            return;
        }

        final String scanTaskKey = scanTaskKey(storeName);
        if (taskKeys.contains(scanTaskKey)) {
            return;
        }

        taskKeys.add(scanTaskKey);
        getCompactionExecutor(merkleDbConfig).submit(new ScannerTask(scanTaskKey, storeName, scanner));
    }

    /**
     * Filters files from the latest scan results, partitions eligible files into groups,
     * runs phase 2 absorption on each group, and submits each group as an independent
     * compaction task.
     *
     * <p>The algorithm proceeds per level:
     * <ol>
     *   <li><b>Phase 1:</b> select files whose {@code deadToAliveRatio > gcRateThreshold}.</li>
     *   <li><b>Split:</b> partition eligible files into groups bounded by
     *       {@code maxCompactedFileSizeInMB}.</li>
     *   <li><b>Phase 2:</b> for each group, absorb additional non-eligible files from a shared
     *       remaining pool. Absorbed files are removed from the pool so no other group at the
     *       same level can claim them.</li>
     *   <li><b>Submit:</b> submit each group as a compaction task.</li>
     * </ol>
     *
     * <p>For each level, new tasks are only submitted when ALL tasks from the previous
     * submission for that level have completed (counter reaches zero).
     *
     * @param storeName        store name (e.g. {@link MerkleDbDataSource#ID_TO_HASH_CHUNK})
     * @param compactorFactory creates a fresh {@link DataFileCompactor} per compaction task
     * @param config           MerkleDb config with threshold, size cap, and level cap parameters
     */
    synchronized void submitCompactionTasks(
            final @NonNull String storeName,
            final @NonNull Supplier<DataFileCompactor> compactorFactory,
            final @NonNull MerkleDbConfig config) {
        if (!compactionEnabled) {
            return;
        }

        final IndexedGarbageFileStats stats = scanStatsByStore.get(storeName);
        if (stats == null || stats.garbageFileStats().length == 0) {
            return;
        }

        final double gcRateThreshold = config.gcRateThreshold();
        final long maxCompactedFileSize = config.maxCompactedFileSizeInMB() * MEBIBYTES_TO_BYTES;
        final long maxProjectedBytes = maxCompactedFileSize == 0 ? Long.MAX_VALUE : maxCompactedFileSize;
        final ExecutorService executor = getCompactionExecutor(merkleDbConfig);

        // Phase 1: separate eligible from remaining, grouped by level
        final Map<Integer, List<DataFileReader>> eligibleByLevel = new HashMap<>();
        final Map<Integer, List<DataFileReader>> remainingByLevel = new HashMap<>();
        for (final GarbageFileStats fs : stats.garbageFileStats()) {
            if (fs == null) {
                continue;
            }
            final int level = fs.compactionLevel();
            if (fs.deadToAliveRatio() > gcRateThreshold) {
                eligibleByLevel.computeIfAbsent(level, _ -> new ArrayList<>()).add(fs.fileReader);
            } else {
                remainingByLevel.computeIfAbsent(level, _ -> new ArrayList<>()).add(fs.fileReader);
            }
        }

        for (final var entry : eligibleByLevel.entrySet()) {
            final int level = entry.getKey();
            final String levelKey = compactionTaskKey(storeName, level);

            // Only submit new groups when ALL previous tasks for this level have finished
            if (compactionTaskCounts.getOrDefault(levelKey, 0) > 0) {
                continue;
            }

            final List<DataFileReader> eligible = entry.getValue();
            if (eligible.isEmpty()) {
                continue;
            }

            // Split eligible files into groups bounded by projected output size
            final List<List<DataFileReader>> groups = splitIntoGroups(eligible, maxProjectedBytes, stats);

            // Phase 2: for each group, absorb additional files from the shared remaining pool
            final List<DataFileReader> remainingPool = remainingByLevel.getOrDefault(level, List.of());
            if (!remainingPool.isEmpty()) {
                // Sort once by dead/alive descending — files that least worsen the aggregate first
                final List<DataFileReader> sortedPool = new ArrayList<>(remainingPool);
                sortedPool.sort(Comparator.<DataFileReader>comparingDouble(r -> {
                            final GarbageFileStats fs = lookupStats(r, stats);
                            return fs != null ? fs.deadToAliveRatio() : 0.0;
                        })
                        .reversed());

                for (final List<DataFileReader> group : groups) {
                    absorbIntoGroup(storeName, group, sortedPool, stats, gcRateThreshold, maxProjectedBytes);
                }
            }

            compactionTaskCounts.put(levelKey, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                final String taskKey = levelKey + "_" + i;
                taskKeys.add(taskKey);
                executor.submit(new CompactionTask(taskKey, levelKey, level, groups.get(i), compactorFactory, config));
            }

            if (groups.size() > 1) {
                logger.info(
                        MERKLE_DB.getMarker(),
                        "[{}] Submitted {} compaction tasks for level {} ({} eligible files)",
                        storeName,
                        groups.size(),
                        level,
                        eligible.size());
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
    synchronized boolean isCompactionRunning(final @NonNull String storeName) {
        final String prefix = storeName + "_compact_";
        for (final String key : taskKeys) {
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

    private static String scanTaskKey(final @NonNull String storeName) {
        return storeName + "_scan";
    }

    private static String compactionTaskKey(final @NonNull String storeName, final int level) {
        return storeName + "_compact_" + level;
    }

    // ========================================================================
    // Grouping and absorption
    // ========================================================================

    /**
     * Partitions candidates into groups where each group's projected output size fits within
     * the cap. Files are taken in iteration order (file index order from the scanner) without
     * sorting. At least one file per group is always included.
     *
     * @param candidates        files eligible for compaction at a single level
     * @param maxProjectedBytes maximum projected alive bytes per group, or &le; 0 to disable
     * @param stats             per-file garbage statistics from the scan
     * @return list of groups; each group is a non-empty sublist of candidates
     */
    static List<List<DataFileReader>> splitIntoGroups(
            final @NonNull List<DataFileReader> candidates,
            final long maxProjectedBytes,
            final @NonNull IndexedGarbageFileStats stats) {
        if (maxProjectedBytes == Long.MAX_VALUE) {
            return List.of(candidates);
        }

        final List<List<DataFileReader>> groups = new ArrayList<>();
        List<DataFileReader> currentGroup = new ArrayList<>();
        long currentProjectedSize = 0;

        for (final DataFileReader reader : candidates) {
            final GarbageFileStats fs = lookupStats(reader, stats);
            final long projectedAlive = fs == null ? 0 : estimateAliveBytes(reader, fs);
            if (!currentGroup.isEmpty() && currentProjectedSize + projectedAlive > maxProjectedBytes) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentProjectedSize = 0;
            }
            currentGroup.add(reader);
            currentProjectedSize += projectedAlive;
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        return groups;
    }

    /**
     * Phase 2: absorbs additional files from a shared remaining pool into a single group.
     * Files that would push the group's aggregate dead/alive ratio below the threshold or
     * the projected output size past the cap are skipped. Absorbed files are removed from
     * the pool so no other group at the same level can claim them.
     *
     * <p>The pool must be pre-sorted by dead/alive ratio descending. Files that least worsen
     * the aggregate are tried first, maximizing the number of files absorbed.
     *
     * @param storeName         name of the store being compacted (for logging only)
     * @param group             the group to absorb files into (modified in place)
     * @param remainingPool     shared pool of non-eligible files for this level, sorted by
     *                          dead/alive ratio descending (modified — absorbed files are removed)
     * @param stats             per-file garbage statistics from the scan
     * @param gcRateThreshold   minimum aggregate dead/alive ratio to maintain
     * @param maxProjectedBytes maximum projected alive bytes for the group
     */
    static void absorbIntoGroup(
            @NonNull String storeName,
            @NonNull final List<DataFileReader> group,
            @NonNull final List<DataFileReader> remainingPool,
            @NonNull final IndexedGarbageFileStats stats,
            final double gcRateThreshold,
            final long maxProjectedBytes) {

        // Compute current aggregate for the group
        long totalLive = 0;
        long totalDead = 0;
        long projectedSize = 0;
        for (final DataFileReader reader : group) {
            final GarbageFileStats fs = lookupStats(reader, stats);
            if (fs != null) {
                totalLive += fs.aliveItems();
                totalDead += fs.deadItems();
                projectedSize += estimateAliveBytes(reader, fs);
            }
        }

        final double aggregateRatio = totalLive == 0 ? Double.MAX_VALUE : (double) totalDead / totalLive;

        // No headroom — skip absorption for this group
        if (aggregateRatio <= gcRateThreshold || projectedSize >= maxProjectedBytes) {
            return;
        }

        int absorbed = 0;
        final Iterator<DataFileReader> it = remainingPool.iterator();
        while (it.hasNext()) {
            final DataFileReader reader = it.next();
            final GarbageFileStats fs = lookupStats(reader, stats);
            if (fs == null) {
                continue;
            }

            final long fileLive = fs.aliveItems();
            final long fileDead = fs.deadItems();
            final long fileProjectedAlive = estimateAliveBytes(reader, fs);

            final long newTotalLive = totalLive + fileLive;
            final long newTotalDead = totalDead + fileDead;
            final double newRatio = newTotalLive == 0 ? Double.MAX_VALUE : (double) newTotalDead / newTotalLive;
            final long newProjectedSize = projectedSize + fileProjectedAlive;

            // Skip this file if it would breach either limit
            if (newRatio <= gcRateThreshold || newProjectedSize >= maxProjectedBytes) {
                continue;
            }

            group.add(reader);
            it.remove(); // remove from shared pool so no other group can claim it
            totalLive = newTotalLive;
            totalDead = newTotalDead;
            projectedSize = newProjectedSize;
            absorbed++;
        }

        if (absorbed > 0) {
            final String finalRatio =
                    totalLive == 0 ? "n/a" : String.valueOf(Math.round((double) totalDead / totalLive * 100) / 100.0);
            logger.info(
                    MERKLE_DB.getMarker(),
                    "[{}]: absorbed {} files into group, aggregate dead/alive={}, projected output={}",
                    storeName,
                    absorbed,
                    finalRatio,
                    formatSizeBytes(projectedSize));
        }
    }

    /**
     * Estimates the projected alive bytes for a single file based on scan statistics.
     * Returns 0 for files with unknown item counts or files not found in the stats
     * (e.g. deleted between scan and grouping).
     */
    static long estimateAliveBytes(final @NonNull DataFileReader reader, final @Nullable GarbageFileStats fileStats) {
        if (fileStats == null || fileStats.totalItems() == 0) {
            return 0;
        }
        return (long) (reader.getSize() * (1.0 - fileStats.garbageRatio()));
    }

    @Nullable
    private static GarbageFileStats lookupStats(
            final @NonNull DataFileReader reader, final @NonNull IndexedGarbageFileStats stats) {
        final int idx = reader.getIndex() - stats.offset();
        if (idx < 0 || idx >= stats.garbageFileStats().length) {
            return null;
        }
        return stats.garbageFileStats()[idx];
    }

    // ========================================================================
    // Inner task classes
    // ========================================================================

    /**
     * Background task that traverses the in-memory index and computes per-file garbage statistics.
     * Results are stored in {@link #scanStatsByStore} for compaction tasks to consume.
     */
    private class ScannerTask implements Runnable {

        private final String taskKey;
        private final String storeName;
        private final GarbageScanner scanner;

        /**
         * Creates a new scanner task.
         *
         * @param taskKey   unique key for deduplication and tracking (e.g. "IdToHashChunk_scan")
         * @param storeName store name used for keying scan results in {@link #scanStatsByStore}
         * @param scanner   the scanner to execute
         */
        ScannerTask(
                @NonNull final String taskKey, @NonNull final String storeName, @NonNull final GarbageScanner scanner) {
            this.taskKey = taskKey;
            this.storeName = storeName;
            this.scanner = scanner;
        }

        @Override
        public void run() {
            try {
                scanStatsByStore.put(storeName, scanner.scan());
            } catch (Exception e) {
                logger.error(EXCEPTION.getMarker(), "[{}] Garbage scan failed", taskKey, e);
            } finally {
                synchronized (MerkleDbCompactionCoordinator.this) {
                    taskKeys.remove(taskKey);
                    MerkleDbCompactionCoordinator.this.notifyAll();
                }
            }
        }
    }

    /**
     * Background compaction task for a pre-assigned group of files at a single level. The group
     * is determined at submission time by {@link #submitCompactionTasks}, which partitions
     * candidates by projected output size and absorbs additional files via phase 2.
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

        /**
         * Creates a new compaction task for a pre-assigned group of files.
         *
         * @param taskKey          unique key for deduplication and tracking
         *                         (e.g. "IdToHashChunk_compact_0_1")
         * @param levelKey         per-level key used for the counter-based deduplication in
         *                         {@link #compactionTaskCounts} (e.g. "IdToHashChunk_compact_0")
         * @param sourceLevel      compaction level of the input files
         * @param assignedFiles    pre-assigned group of files to compact (non-overlapping with
         *                         other groups at the same level)
         * @param compactorFactory creates a fresh {@link DataFileCompactor} for this task
         * @param config           MerkleDb configuration for level cap and other parameters
         */
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
                // Create a compactor and register it for pause/resume/interrupt
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
                    taskKeys.remove(taskKey);
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
