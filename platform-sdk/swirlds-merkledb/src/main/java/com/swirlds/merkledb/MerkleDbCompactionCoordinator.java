// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.MerkleDb.MERKLEDB_COMPONENT;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible for coordinating compaction tasks for a {@link MerkleDbDataSource}.
 * It provides convenient API for starting compactions for each of the three storage types. Also, this class makes sure
 * that there are no concurrent compactions for the same storage type. And finally it provides a way to stop all compactions
 * and keep them disabled until they are explicitly enabled again.
 * The compaction tasks are executed in a background thread pool.
 * The number of threads in the pool is defined by {@link MerkleDbConfig#compactionThreads()} property.
 *
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
     * by the first instance of MerkleDbCompactionCoordinator class that calls the relevant non-static method.
     * Subsequent calls will reuse the same executor, regardless of any new configurations provided.
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

    private final AtomicBoolean compactionEnabled = new AtomicBoolean();

    // A map of compactor futures by task names
    final Map<String, Future<Boolean>> futuresByName = new ConcurrentHashMap<>(16);

    // A map of compactors by task names
    final Map<String, DataFileCompactor> compactorsByName = new ConcurrentHashMap<>(16);

    @NonNull
    private final MerkleDbConfig merkleDbConfig;

    // Number of compaction tasks currently running. Checked during shutdown to make sure all
    // tasks are stopped
    private final AtomicInteger tasksRunning = new AtomicInteger(0);

    /**
     * Creates a new instance of {@link MerkleDbCompactionCoordinator}.
     * @param tableName the name of the table
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
    void enableBackgroundCompaction() {
        compactionEnabled.set(true);
    }

    /**
     * Pauses compaction of all data file compactors. It may not stop compaction
     * immediately, but as soon as compaction process needs to update data source state, which is
     * critical for snapshots (e.g. update an index), it will be stopped until {@link
     * #resumeCompaction()}} is called.
     */
    public void pauseCompaction() throws IOException {
        for (final DataFileCompactor compactor : compactorsByName.values()) {
            compactor.pauseCompaction();
        }
    }

    /**
     * Resumes previously stopped data file collection compaction.
     */
    public void resumeCompaction() throws IOException {
        for (final DataFileCompactor compactor : compactorsByName.values()) {
            compactor.resumeCompaction();
        }
    }

    /**
     * Stops all compactions in progress and disables background compaction. All subsequent calls to
     * compacting methods will be ignored until {@link #enableBackgroundCompaction()} is called.
     */
    void stopAndDisableBackgroundCompaction() {
        compactionEnabled.set(false);
        // Interrupt all running compaction tasks, if any
        for (final Future<Boolean> futureEntry : futuresByName.values()) {
            futureEntry.cancel(true);
        }
        futuresByName.clear();
        compactorsByName.clear();
        // Wait till all the tasks are stopped
        final long now = System.currentTimeMillis();
        try {
            while ((tasksRunning.get() != 0) && (System.currentTimeMillis() - now < SHUTDOWN_TIMEOUT_MILLIS)) {
                Thread.sleep(1);
            }
        } catch (final InterruptedException e) {
            logger.warn(MERKLE_DB.getMarker(), "Interrupted while waiting for compaction tasks to complete", e);
        }
        // If some tasks are still running, there is nothing else to than to log it
        if (tasksRunning.get() != 0) {
            logger.error(MERKLE_DB.getMarker(), "Failed to stop all compactions tasks");
        }
    }

    /**
     * Submits a compaction task for execution. If a compactor with the given name is already in progress,
     * the call is effectively no op.
     *
     * @param key Compaction task name
     * @param compactor Compactor to run
     */
    public void compactIfNotRunningYet(final String key, final DataFileCompactor compactor) {
        if (!compactionEnabled.get()) {
            return;
        }
        if (futuresByName.containsKey(key)) {
            logger.debug(MERKLE_DB.getMarker(), "Compaction for {} is already in progress", key);
            return;
        }
        assert !compactorsByName.containsKey(key);
        compactorsByName.put(key, compactor);
        final ExecutorService executor = getCompactionExecutor(merkleDbConfig);
        final CompactionTask task = new CompactionTask(key, compactor);
        futuresByName.put(key, executor.submit(task));
    }

    /**
     * Checks if a compaction task with the given name is currently in progress.
     *
     * @param key Compactor name
     * @return {@code true} if compaction with this name is currently running, {@code false} otherwise
     */
    public boolean isCompactionRunning(final String key) {
        return futuresByName.containsKey(key);
    }

    boolean isCompactionEnabled() {
        return compactionEnabled.get();
    }

    /**
     * A helper class representing a task to run compaction for a specific storage type.
     */
    private class CompactionTask implements Callable<Boolean> {

        // Task ID
        private final String id;

        // Compactor to run
        private final DataFileCompactor compactor;

        public CompactionTask(@NonNull String id, @NonNull DataFileCompactor compactor) {
            this.id = id;
            this.compactor = compactor;
        }

        @Override
        public Boolean call() {
            tasksRunning.incrementAndGet();
            try {
                return compactor.compact();
            } catch (final InterruptedException | ClosedByInterruptException e) {
                logger.info(MERKLE_DB.getMarker(), "Interrupted while compacting, this is allowed");
            } catch (Exception e) {
                // It is important that we capture all exceptions here, otherwise a single exception
                // will stop all future merges from happening
                logger.error(EXCEPTION.getMarker(), "[{}] Compaction failed", id, e);
            } finally {
                compactorsByName.remove(id);
                futuresByName.remove(id);
                tasksRunning.decrementAndGet();
            }
            return false;
        }
    }
}
