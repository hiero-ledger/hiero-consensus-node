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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
@SuppressWarnings("rawtypes")
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

    public static final String HASH_STORE_DISK_SUFFIX = "HashStoreDisk";
    public static final String OBJECT_KEY_TO_PATH_SUFFIX = "ObjectKeyToPath";
    public static final String PATH_TO_KEY_VALUE_SUFFIX = "PathToKeyValue";
    private final AtomicBoolean compactionEnabled = new AtomicBoolean();
    // we need a map of exactly three elements, one per storage
    final ConcurrentMap<String, Future<Boolean>> compactionFuturesByName = new ConcurrentHashMap<>(3);
    private final CompactionTask objectKeyToPathTask;
    private final CompactionTask hashesStoreDiskTask;
    private final CompactionTask pathToKeyValueTask;

    @Nullable
    private final DataFileCompactor objectKeyToPath;

    @Nullable
    private final DataFileCompactor hashesStoreDisk;

    @NonNull
    private final DataFileCompactor pathToKeyValue;

    @NonNull
    private final MerkleDbConfig merkleDbConfig;

    // Number of compaction tasks currently running. Checked during shutdown to make sure all
    // tasks are stopped
    private final AtomicInteger tasksRunning = new AtomicInteger(0);

    /**
     * Creates a new instance of {@link MerkleDbCompactionCoordinator}.
     * @param tableName the name of the table
     * @param objectKeyToPath an object key to path store
     * @param hashesStoreDisk a hash store
     * @param pathToKeyValue a path to key-value store
     * @param merkleDbConfig platform config for MerkleDbDataSource
     */
    public MerkleDbCompactionCoordinator(
            @NonNull String tableName,
            @Nullable DataFileCompactor objectKeyToPath,
            @Nullable DataFileCompactor hashesStoreDisk,
            @NonNull DataFileCompactor pathToKeyValue,
            @NonNull MerkleDbConfig merkleDbConfig) {
        requireNonNull(tableName);
        requireNonNull(pathToKeyValue);
        requireNonNull(merkleDbConfig);
        this.objectKeyToPath = objectKeyToPath;
        this.hashesStoreDisk = hashesStoreDisk;
        this.pathToKeyValue = pathToKeyValue;
        this.merkleDbConfig = merkleDbConfig;
        if (objectKeyToPath != null) {
            objectKeyToPathTask = new CompactionTask(tableName + OBJECT_KEY_TO_PATH_SUFFIX, objectKeyToPath);
        } else {
            objectKeyToPathTask = null;
        }
        if (hashesStoreDisk != null) {
            hashesStoreDiskTask = new CompactionTask(tableName + HASH_STORE_DISK_SUFFIX, hashesStoreDisk);
        } else {
            hashesStoreDiskTask = null;
        }
        this.pathToKeyValueTask = new CompactionTask(tableName + PATH_TO_KEY_VALUE_SUFFIX, pathToKeyValue);
    }

    /**
     * Compacts the object key to path store asynchronously if it's present.
     */
    void compactDiskStoreForKeyToPathAsync() {
        if (objectKeyToPathTask == null) {
            return;
        }
        submitCompactionTaskForExecution(objectKeyToPathTask);
    }

    /**
     * Compacts the hash store asynchronously if it's present.
     */
    void compactDiskStoreForHashesAsync() {
        if (hashesStoreDiskTask == null) {
            return;
        }
        submitCompactionTaskForExecution(hashesStoreDiskTask);
    }

    /**
     * Compacts the path to key-value store asynchronously.
     */
    void compactPathToKeyValueAsync() {
        submitCompactionTaskForExecution(pathToKeyValueTask);
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
        if (hashesStoreDisk != null) {
            hashesStoreDisk.pauseCompaction();
        }

        pathToKeyValue.pauseCompaction();

        if (objectKeyToPath != null) {
            objectKeyToPath.pauseCompaction();
        }
    }
    /** Resumes previously stopped data file collection compaction. */
    public void resumeCompaction() throws IOException {
        if (hashesStoreDisk != null) {
            hashesStoreDisk.resumeCompaction();
        }

        pathToKeyValue.resumeCompaction();

        if (objectKeyToPath != null) {
            objectKeyToPath.resumeCompaction();
        }
    }

    /**
     * Stops all compactions in progress and disables background compaction.
     * All subsequent calls to compacting methods will be ignored until {@link #enableBackgroundCompaction()} is called.
     */
    void stopAndDisableBackgroundCompaction() {
        compactionEnabled.set(false);
        // Interrupt all running compaction tasks, if any
        synchronized (compactionFuturesByName) {
            for (var futureEntry : compactionFuturesByName.values()) {
                futureEntry.cancel(true);
            }
            compactionFuturesByName.clear();
        }
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
     * Submits a compaction task for execution. If a compaction task for the same storage type is already in progress,
     * the call is effectively no op.
     * @param task a compaction task to execute
     */
    private void submitCompactionTaskForExecution(CompactionTask task) {
        synchronized (compactionFuturesByName) {
            if (!compactionEnabled.get()) {
                return;
            }
            if (compactionFuturesByName.containsKey(task.id)) {
                Future<?> future = compactionFuturesByName.get(task.id);
                if (future.isDone()) {
                    compactionFuturesByName.remove(task.id);
                } else {
                    logger.debug(MERKLE_DB.getMarker(), "Compaction for {} is already in progress", task.id);
                    return;
                }
            }
            final ExecutorService executor = getCompactionExecutor(merkleDbConfig);
            compactionFuturesByName.put(task.id, executor.submit(task));
        }
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
                logger.info(MERKLE_DB.getMarker(), "Interrupted while compacting, this is allowed.", e);
            } catch (Exception e) {
                // It is important that we capture all exceptions here, otherwise a single exception
                // will stop all  future merges from happening.
                logger.error(EXCEPTION.getMarker(), "[{}] Compaction failed", id, e);
            } finally {
                tasksRunning.decrementAndGet();
            }
            return false;
        }
    }
}
