// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.datasource.DataSourceHashChunkPreloader;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.reconnect.ConcurrentBlockingIterator;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.ParallelSyncTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashLeafFlusher;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * This class encapsulates all state and logic of the reconnect process on the learner side for a {@link VirtualMap}.
 *
 * <p>Lifecycle:
 * <ul>
 *     <li>Constructor {@link #VirtualMapLearner(VirtualMap, ReconnectConfig, ReconnectMapStats)}</li>
 *     <li>Caller accesses {@link LearnerTreeView} via {@link #getLearnerView()} to start lessons using reconnect input/output streams.</li>
 *     <li>When synchronization starts, the teacher first sends its current leaf path range, and the {@link LearnerTreeView} implementation triggers {@link #init(long, long, Runnable)}.</li>
 *     <li>Then on each dirty leaf {@link #onDirtyLeaf(VirtualLeafBytes)} has to be called</li>
 *     <li>On successful reconnect completion, the reconnect framework calls {@link #finish()} to finalize synchronization and create the new {@link VirtualMap} instance accessible via {@link #getVirtualMap()}.</li>
 *     <li>If reconnect fails before successful completion, the caller/reconnect orchestration code is responsible for aborting the reconnect attempt and cleaning up resources associated with the failed attempt via {@link #abortOnException()}.</li>
 * </ul>
 *
 * <p>No {@link VirtualMap} is created until {@link #finish()} is called during successful reconnect completion, at which point a fresh, fully
 * initialized {@link VirtualMap} is constructed from the reconnected state.
 *
 * <p>This class also handles leaves deletions For example,
 * if an internal node is received for a path from the teacher, but it was a leaf node on the learner, the
 * key (that corresponds to that leaf) needs to removed. Other cases are handled in a similar way.
 *
 * <p>One particular case is complicated. Assume the learner tree has a key K at path N, and the teacher has
 * the same key at path M, and M &lt; N, while at path N the teacher has a different key L. During reconnects the
 * path M is processed first. At this step, some old key is marked for removal. Some time later path N is
 * processed, and this time key K is marked for removal, but this is wrong as it's still a valid key, just at
 * a different path. To handle this case, during flushes we check all leaf candidates for removal, where in
 * the tree they are located. In the scenario above, when path M was processed, key location was changed from N
 * to M. Later during flush, key K is in the list of candidates to remove, but with path N (this is where it
 * was originally located in the learner tree). Since the path is different, the leaf will not be actually
 * removed from disk.
 */
public final class VirtualMapLearner {

    private static final Logger logger = LogManager.getLogger(VirtualMapLearner.class);

    private static final int MAX_RECONNECT_HASHING_BUFFER_SIZE = 10_000_000;

    // ---- State captured from the original map at creation time ----

    private final VirtualDataSourceBuilder dataSourceBuilder;
    private final VirtualHasher hasher;
    private final VirtualMapConfig virtualMapConfig;
    private final VirtualMapMetadata originalState;
    private final RecordAccessor originalRecords;

    // ---- Reconnect-specific state ----

    private final VirtualDataSource dataSource;
    private final VirtualMapStatistics statistics;
    private final ReconnectHashLeafFlusher reconnectFlusher;
    private final LearnerTreeView learnerView;

    private final VirtualMapMetadata reconnectState = new VirtualMapMetadata();
    private final ConcurrentBlockingIterator<VirtualLeafBytes> reconnectIterator =
            new ConcurrentBlockingIterator<>(MAX_RECONNECT_HASHING_BUFFER_SIZE);
    private final CompletableFuture<Hash> reconnectHashingFuture = new CompletableFuture<>();
    private final AtomicBoolean reconnectHashingStarted = new AtomicBoolean(false);

    // Tracks current stage of the reconnect process
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.NEW);

    // ---- Set after reconnect completes ----

    /** The final hash produced by the reconnect hashing process. Null for empty trees. */
    @Nullable
    private Hash finalHash;

    /**
     * The fully initialized {@link VirtualMap} created at the end of the reconnect process.
     * Null until {@link #finish()} has been called.
     */
    @Nullable
    private VirtualMap virtualMap;

    private enum Stage {
        NEW,
        INITIALIZING,
        INITIALIZED,
        FINISHING,
        FINISHED,
    }

    /**
     * Creates a new {@link VirtualMapLearner} from the learner's current (outdated) {@link VirtualMap}.
     *
     * <p> The original map is hashed eagerly here so that the teacher can inspect hashes during
     * the synchronization process to determine which nodes need to be transferred.
     *
     * <p> Some original map fields are remembered so they can be used to construct new {@link VirtualMap} at the end of reconnect process.
     * The original map's data source is shut down and an independent copy is created for the reconnect process,
     * which will be updated with new leaves and hashes as they are received from the teacher.
     *
     * <p> {@link LearnerTreeView} will be eagerly created and available to access by {@link #getLearnerView()}.
     *
     * @param originalMap the learner's current virtual map; must not be {@code null}
     * @param reconnectConfig reconnect configuration for this operation; must not be {@code null}
     * @param mapStats collector for reconnect metrics; must not be {@code null}
     */
    public VirtualMapLearner(
            @NonNull final VirtualMap originalMap,
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final ReconnectMapStats mapStats) {
        requireNonNull(originalMap, "originalMap must not be null");
        requireNonNull(reconnectConfig, "reconnectConfig must not be null");
        requireNonNull(mapStats, "mapStats must not be null");

        // Ensure the original map is hashed. Once hashed, all internal nodes are also hashed,
        // which is required for the reconnect process — the teacher uses these hashes to decide
        // whether to send the underlying nodes.
        originalMap.getHash();

        this.dataSourceBuilder = originalMap.getDataSourceBuilder();
        this.hasher = originalMap.getHasher();
        this.virtualMapConfig = originalMap.getVirtualMapConfig();
        this.statistics = originalMap.getStatistics();
        this.originalState = originalMap.getMetadata();
        this.originalRecords = originalMap.getRecords();

        // Shut down background compaction on the original data source; it is no longer
        // needed because all data in that source only serves as a starting point for reconnect.
        originalMap.getDataSource().stopAndDisableBackgroundCompaction();

        // Create an independent copy of the data source that will be updated during reconnect.
        this.dataSource = originalMap.detachAsDataSourceCopy();
        this.dataSource.copyStatisticsFrom(originalMap.getDataSource());

        reconnectFlusher =
                new ReconnectHashLeafFlusher(dataSource, virtualMapConfig.reconnectFlushInterval(), statistics);
        learnerView = buildLearnerView(reconnectConfig, mapStats);
    }

    /**
     * @return {@link LearnerTreeView} that should be passed to the {@code LearningSynchronizer} to start the reconnect process.
     */
    @NonNull
    public LearnerTreeView getLearnerView() {
        return learnerView;
    }

    @NonNull
    public VirtualMapMetadata getOriginalState() {
        return originalState;
    }

    public VirtualMapMetadata getReconnectState() {
        return reconnectState;
    }

    @Nullable
    public Hash findHash(long path) {
        return originalRecords.findHash(path);
    }

    // ---- Reconnect operations (called by LearnerTreeView implementations) ----

    /**
     * Updates the stage of the reconnect process, ensuring that stage transitions happen in the expected order.
     *
     * @param expectedStage the expected current stage; if the actual stage does not match this, an exception is thrown
     * @param newStage the desired new stage to be set
     * @throws IllegalStateException if the actual current stage does not match the expected stage, indicating an incorrect sequence of operations
     */
    private void updateStage(@NonNull final Stage expectedStage, @NonNull final Stage newStage) {
        if (!stage.compareAndSet(expectedStage, newStage)) {
            throw new IllegalStateException("Reconnect stage is incorrect. expected=" + expectedStage + ", actual="
                    + stage.get() + ", desired=" + newStage);
        }
    }

    /**
     * Called when the teacher has sent the root response, establishing the first and last leaf
     * paths of the reconnected tree. This initializes the reconnect state and flusher,
     * starts the background hashing thread and registers old leaves that need to be removed.
     *
     * <p><b>Must</b> be called before any {@link #onDirtyLeaf(VirtualLeafBytes)} and {@link #finish()} calls.
     *
     * @param firstLeafPath first leaf path in the reconnected tree
     * @param lastLeafPath  last leaf path in the reconnected tree
     * @param beforeCleaningLeavesAction an action to run after the reconnect state is initialized but before any old leaves are marked for deletion. Can be {@code null}.
     */
    public void init(final long firstLeafPath, final long lastLeafPath, @Nullable Runnable beforeCleaningLeavesAction) {
        updateStage(Stage.NEW, Stage.INITIALIZING);

        logger.info(
                RECONNECT.getMarker(),
                "Init reconnect state: firstLeafPath: {} -> {}, lastLeafPath: {} -> {}",
                originalState.getFirstLeafPath(),
                firstLeafPath,
                originalState.getLastLeafPath(),
                lastLeafPath);

        reconnectState.setPaths(firstLeafPath, lastLeafPath);
        reconnectFlusher.init(firstLeafPath, lastLeafPath);
        updateStage(Stage.INITIALIZING, Stage.INITIALIZED);

        startReconnectHashingThread(firstLeafPath, lastLeafPath);

        // setPathInformation() below may take a while if many old leaves need to be marked for deletion,
        // so we run the provided action before that to allow the caller to do any necessary preparation
        // (e.g., send an acknowledgment to the teacher to unblock it).
        if (beforeCleaningLeavesAction != null) {
            beforeCleaningLeavesAction.run();
        }

        deleteOldLeavesBeforeNewFirstLeafPath();
    }

    /**
     * Check if old leaves before new first leaf path have to be deleted.
     *
     * <p>If the old learner tree contained fewer elements than the new tree from the teacher, all leaves
     * from the old {@code firstLeafPath} inclusive to the new {@code firstLeafPath} exclusive are
     * marked for deletion.
     */
    private void deleteOldLeavesBeforeNewFirstLeafPath() {
        final long newFirstLeafPath = reconnectState.getFirstLeafPath();
        final long oldFirstLeafPath = originalState.getFirstLeafPath();

        // no-op if new first leaf path is less or equal to old first leaf path
        if (originalState.getLastLeafPath() > 0 && oldFirstLeafPath < newFirstLeafPath) {
            final long limit = Long.min(newFirstLeafPath, originalState.getLastLeafPath() + 1);

            logger.info(
                    RECONNECT.getMarker(),
                    "Starting deleting {} nodes from oldFirstLeafPath={} to {} exclusive.",
                    (limit - oldFirstLeafPath),
                    oldFirstLeafPath,
                    limit);

            for (long path = oldFirstLeafPath; path < limit; path++) {
                final VirtualLeafBytes<?> oldRecord = originalRecords.findLeafRecord(path);
                assert oldRecord != null : "Cannot find an old leaf record at path " + path;
                reconnectFlusher.deleteLeaf(oldRecord);
            }

            logger.info(
                    RECONNECT.getMarker(),
                    "Finished deleting nodes from oldFirstLeafPath={} to {} exclusive",
                    oldFirstLeafPath,
                    limit);
        } else {
            logger.info(RECONNECT.getMarker(), "No nodes to delete before newFirstLeafPath={}", newFirstLeafPath);
        }
    }

    /**
     * Called when a dirty leaf is received from the teacher. Registers the leaf for stale-key
     * removal tracking and feeds it into the background hashing pipeline.
     * May block if the hashing thread is slower than the incoming data rate.
     *
     * @param leaf the leaf record received from the teacher; must not be null
     */
    public void onDirtyLeaf(@NonNull final VirtualLeafBytes<?> leaf) {
        assert stage.get() == Stage.INITIALIZED : "reconnect is not initialized yet";
        checkOldLeafToBeDeleted(leaf);
        reconnectFlusher.updateLeaf(leaf);

        // Feeds a leaf record received from the teacher into the reconnect hashing pipeline.
        // May block if the hashing thread is slower than the incoming data rate.
        try {
            reconnectIterator.supply(leaf);
        } catch (final MerkleSynchronizationException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException(
                    "Interrupted while waiting to supply a new leaf to the hashing iterator buffer", e);
        } catch (final Exception e) {
            throw new MerkleSynchronizationException("Failed to handle a leaf during reconnect on the learner", e);
        }
    }

    /**
     * Deletes an old leaf, if the new leaf node is in the same path position and leaf keys are different.
     *
     * @param newLeaf new leaf to be checked if
     */
    private void checkOldLeafToBeDeleted(@NonNull final VirtualLeafBytes<?> newLeaf) {
        final VirtualLeafBytes<?> oldRecord = originalRecords.findLeafRecord(newLeaf.path());
        if ((oldRecord != null) && !newLeaf.keyBytes().equals(oldRecord.keyBytes())) {
            reconnectFlusher.deleteLeaf(oldRecord);
        }
    }

    /**
     * Signals that all nodes have been received from the teacher, then finalizes the reconnect
     * process. Waits for hashing to complete and creates the fully initialized {@link VirtualMap}.
     * The new map can subsequently be retrieved via {@link #getVirtualMap()}.
     *
     * <p>This method is called automatically when the {@link LearnerTreeView} is closed.
     *
     * @throws MerkleSynchronizationException if hashing fails or if the calling thread is interrupted
     */
    public void finish() {
        updateStage(Stage.INITIALIZED, Stage.FINISHING);

        logger.info(RECONNECT.getMarker(), "Finalizing learner reconnect");

        deleteOldLeavesAfterNewLastLeafPath();
        waitForHashingToComplete();
        reconnectFlusher.finish();

        virtualMap = new VirtualMap(
                virtualMapConfig, dataSourceBuilder, dataSource, reconnectState.copy(), statistics, hasher, finalHash);

        updateStage(Stage.FINISHING, Stage.FINISHED);
        logger.info(RECONNECT.getMarker(), "Learner reconnect complete");
    }

    /**
     * Check if old leaves after new last leaf path have to be deleted and delete them.
     */
    private void deleteOldLeavesAfterNewLastLeafPath() {
        final long oldLastLeafPath = originalState.getLastLeafPath();
        final long newLastLeafPath = reconnectState.getLastLeafPath();

        // No-op if new last leaf path is greater or equal to old last leaf path
        if (newLastLeafPath < oldLastLeafPath) {
            // if new state is empty (newLastLeafPath = -1) or new last leaf path is less than old first leaf path,
            // we delete all old leaves, otherwise we delete old leaves from new last leaf path + 1 to old last leaf
            // path.
            final long firstOldPathToDelete = Long.max(originalState.getFirstLeafPath(), newLastLeafPath + 1);

            logger.info(
                    RECONNECT.getMarker(),
                    "Starting deleting {} nodes from firstOldPathToDelete={} to oldLastLeafPath={} inclusive.",
                    (oldLastLeafPath - firstOldPathToDelete + 1),
                    firstOldPathToDelete,
                    oldLastLeafPath);

            for (long p = firstOldPathToDelete; p <= oldLastLeafPath; p++) {
                final VirtualLeafBytes<?> oldExtraLeafRecord = originalRecords.findLeafRecord(p);
                assert oldExtraLeafRecord != null;
                reconnectFlusher.deleteLeaf(oldExtraLeafRecord);
            }
            logger.info(
                    RECONNECT.getMarker(),
                    "Finished deleting nodes from firstOldStalePath={} to oldLastLeafPath={} inclusive",
                    firstOldPathToDelete,
                    oldLastLeafPath);
        } else {
            logger.info(RECONNECT.getMarker(), "No nodes to delete after newLastLeafPath={}", newLastLeafPath);
        }
    }

    /**
     * Starts the background hashing thread for the reconnect tree.
     * Must be called after the root response has been received from the teacher (i.e., after
     * first and last leaf paths are known).
     *
     * @param firstLeafPath first leaf path in the reconnected tree
     * @param lastLeafPath  last leaf path in the reconnected tree
     */
    private void startReconnectHashingThread(final long firstLeafPath, final long lastLeafPath) {
        final DataSourceHashChunkPreloader hashChunkPreloader = new DataSourceHashChunkPreloader(dataSource);
        final ReconnectHashListener hashListener = new ReconnectHashListener(reconnectFlusher, hashChunkPreloader);

        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("virtualmap")
                .setThreadName("hasher")
                .setRunnable(() -> reconnectHashingFuture.complete(hasher.hash(
                        dataSource.getHashChunkHeight(),
                        hashChunkPreloader,
                        reconnectIterator,
                        firstLeafPath,
                        lastLeafPath,
                        hashListener)))
                .setExceptionHandler((thread, exception) -> {
                    reconnectIterator.close();
                    final var message = "VirtualMap failed to hash during reconnect";
                    logger.error(EXCEPTION.getMarker(), message, exception);
                    reconnectHashingFuture.completeExceptionally(
                            new MerkleSynchronizationException(message, exception));
                })
                .build()
                .start();

        reconnectHashingStarted.set(true);
        logger.info(RECONNECT.getMarker(), "Reconnect hashing thread started");
    }

    /**
     * Waits for hashing to complete, then creates the fully initialized {@link VirtualMap}.
     * Called by {@link #finish()} after all nodes have been signalled as received.
     */
    private void waitForHashingToComplete() {
        try {
            reconnectIterator.close();
            if (reconnectHashingStarted.get()) {
                logger.info(RECONNECT.getMarker(), "Waiting for reconnect hashing to complete");
                finalHash = reconnectHashingFuture.get();
                logger.info(RECONNECT.getMarker(), "Reconnect hashing completed");
            } else {
                logger.warn(RECONNECT.getMarker(), "Hashing thread was never started");
            }
        } catch (final ExecutionException e) {
            throw new MerkleSynchronizationException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException(e);
        }
    }

    /**
     * Builds a {@link LearnerTreeView} for this reconnect operation.
     *
     * <p>Must be called before passing this instance to a {@code LearningSynchronizer}.
     * The view will be closed automatically by the synchronizer when reconnect completes or fails.
     *
     * @param reconnectConfig reconnect configuration
     * @param mapStats        collector for reconnect metrics
     * @return the learner tree view
     */
    @NonNull
    private LearnerTreeView buildLearnerView(
            @NonNull final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats) {
        logger.info(
                RECONNECT.getMarker(),
                "Building learner view for map with path range [{}, {}]",
                originalState.getFirstLeafPath(),
                originalState.getLastLeafPath());

        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM ->
                new LearnerPullVirtualTreeView(reconnectConfig, this, new TopToBottomTraversalOrder(), mapStats);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC ->
                new LearnerPullVirtualTreeView(
                        reconnectConfig, this, new TwoPhasePessimisticTraversalOrder(), mapStats);
            case VirtualMapReconnectMode.PULL_PARALLEL_SYNC ->
                new LearnerPullVirtualTreeView(reconnectConfig, this, new ParallelSyncTraversalOrder(), mapStats);
            default ->
                throw new UnsupportedOperationException("Unknown reconnect mode: "
                        + virtualMapConfig.reconnectMode()
                        + ". Supported modes: PULL_TOP_TO_BOTTOM,"
                        + " PULL_TWO_PHASE_PESSIMISTIC, PULL_PARALLEL_SYNC");
        };
    }

    // ---- Post-reconnect access ----

    /**
     * Returns the fully initialized {@link VirtualMap} created after reconnect completed.
     *
     * <p>Must only be called after the {@link LearnerTreeView} returned by
     * {@link #getLearnerView()} has been closed.
     *
     * @return the reconnected, fully initialized virtual map
     * @throws IllegalStateException if reconnect has not yet completed
     */
    @NonNull
    public VirtualMap getVirtualMap() {
        assert stage.get() == Stage.FINISHED : "Reconnect has not completed; current stage is " + stage.get();
        if (virtualMap == null) {
            throw new IllegalStateException("Reconnect has not completed - new virtual map is not yet available");
        }
        return virtualMap;
    }

    /**
     * Destroy current reconnect state by closing iterator and datasource.
     * Must be called in case of any reconnect exception and suppresses any exception thrown during closing resources
     * to preserve caller exception propagation.
     */
    public void abortOnException() {
        logger.info(RECONNECT.getMarker(), "Aborting reconnect and cleaning up resources");

        reconnectIterator.close();
        try {
            dataSource.close(false);
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Failed to close reconnect data source during abort", e);
            // ignore exception to preserve caller exception propagation
        }
    }
}
