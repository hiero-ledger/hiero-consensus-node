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
import com.swirlds.virtualmap.internal.reconnect.LearnerPushVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.ParallelSyncTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashLeafFlusher;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import com.swirlds.virtualmap.internal.reconnect.ReconnectNodeRemover;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Manages the reconnect process on the learner side for a virtual map. This class encapsulates
 * all reconnect-specific state and logic that was previously embedded inside {@link VirtualMap},
 * keeping {@link VirtualMap} focused on its core data-map responsibilities.
 *
 * <p>No {@link VirtualMap} is created until {@link #onEnd()} is called (which
 * happens internally when the learner tree view is closed), at which point a fresh, fully
 * initialized {@link VirtualMap} is constructed from the reconnected state.
 */
public final class VirtualMapReconnect {

    private static final Logger logger = LogManager.getLogger(VirtualMapReconnect.class);

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
    private final ReconnectNodeRemover nodeRemover;
    private final LearnerTreeView learnerView;

    private final VirtualMapMetadata reconnectState = new VirtualMapMetadata();
    private final ConcurrentBlockingIterator<VirtualLeafBytes> reconnectIterator =
            new ConcurrentBlockingIterator<>(MAX_RECONNECT_HASHING_BUFFER_SIZE);
    private final CompletableFuture<Hash> reconnectHashingFuture = new CompletableFuture<>();
    private final AtomicBoolean reconnectHashingStarted = new AtomicBoolean(false);

    // ---- Set after reconnect completes ----

    /** The final hash produced by the reconnect hashing process. Null for empty trees. */
    @Nullable
    private Hash finalHash;

    /**
     * The fully initialized {@link VirtualMap} created at the end of the reconnect process.
     * Null until {@link #onEnd()} has been called.
     */
    @Nullable
    private VirtualMap virtualMap;

    /**
     * Creates a new {@link VirtualMapReconnect} from the learner's current (outdated) virtual map.
     *
     * <p>The original map is hashed eagerly here so that the teacher can inspect hashes during
     * the synchronization process to determine which nodes need to be transferred.
     *
     * @param originalMap the learner's current virtual map; must not be null
     * @param reconnectConfig reconnect configuration for this operation; must not be null
     * @param mapStats collector for reconnect metrics; must not be null
     */
    public VirtualMapReconnect(
            @NonNull final VirtualMap originalMap,
            @NonNull final ReconnectConfig reconnectConfig,
            @NonNull final ReconnectMapStats mapStats) {
        requireNonNull(originalMap, "originalMap must not be null");
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
        nodeRemover = new ReconnectNodeRemover(
                originalRecords, originalState.getFirstLeafPath(), originalState.getLastLeafPath(), reconnectFlusher);
        learnerView = buildLearnerView(reconnectConfig, mapStats);
    }

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
     * Called when the teacher has sent the root response, establishing the first and last leaf
     * paths of the reconnected tree. This initializes the reconnect state, registers old leaves
     * that need to be removed, and starts the background hashing thread.
     *
     * <p>Must be called before {@link #onLeaf(VirtualLeafBytes)}.
     *
     * @param firstLeafPath first leaf path in the reconnected tree
     * @param lastLeafPath  last leaf path in the reconnected tree
     */
    public void onStart(final long firstLeafPath, final long lastLeafPath) {
        logger.info(RECONNECT.getMarker(), "Start reconnect");

        reconnectState.setPaths(firstLeafPath, lastLeafPath);
        // setPathInformation() below may take a while if many old leaves need to be marked for deletion
        nodeRemover.setPathInformation(firstLeafPath, lastLeafPath);
        prepareReconnectHashing(firstLeafPath, lastLeafPath);
    }

    /**
     * Called when a dirty leaf is received from the teacher. Registers the leaf for stale-key
     * removal tracking and feeds it into the background hashing pipeline.
     * May block if the hashing thread is slower than the incoming data rate.
     *
     * @param leaf the leaf record received from the teacher; must not be null
     */
    public void onLeaf(@NonNull final VirtualLeafBytes<?> leaf) {
        nodeRemover.newLeafNode(leaf.path(), leaf.keyBytes());

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
     * Signals that all nodes have been received from the teacher, then finalizes the reconnect
     * process. Waits for hashing to complete and creates the fully initialized {@link VirtualMap}.
     * The new map can subsequently be retrieved via {@link #getVirtualMap()}.
     *
     * <p>This method is called automatically when the {@link LearnerTreeView} is closed.
     *
     * @throws MerkleSynchronizationException if hashing fails or if the calling thread is interrupted
     */
    public void onEnd() {
        logger.info(RECONNECT.getMarker(), "Ending learner reconnect");

        nodeRemover.allNodesReceived();
        endLearnerReconnect();

        logger.info(RECONNECT.getMarker(), "Leaner reconnect complete");
    }

    /**
     * Starts the background hashing thread for the reconnect tree.
     * Must be called after the root response has been received from the teacher (i.e., after
     * first and last leaf paths are known).
     *
     * @param firstLeafPath first leaf path in the reconnected tree
     * @param lastLeafPath  last leaf path in the reconnected tree
     */
    private void prepareReconnectHashing(final long firstLeafPath, final long lastLeafPath) {
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
    }

    /**
     * Waits for hashing to complete, then creates the fully initialized {@link VirtualMap}.
     * Called by {@link #onEnd()} after all nodes have been signalled as received.
     */
    private void endLearnerReconnect() {
        try {
            reconnectIterator.close();
            if (reconnectHashingStarted.get()) {
                logger.info(RECONNECT.getMarker(), "waiting for reconnect hashing to complete");
                finalHash = reconnectHashingFuture.get();
            } else {
                logger.warn(RECONNECT.getMarker(), "virtual map hashing thread was never started");
            }

            logger.info(RECONNECT.getMarker(), "creating fully initialized VirtualMap from reconnect state");
            final VirtualMapMetadata metadata = new VirtualMapMetadata(reconnectState.getSize());
            virtualMap = new VirtualMap(
                    virtualMapConfig, dataSourceBuilder, dataSource, metadata, statistics, hasher, finalHash);
        } catch (final ExecutionException e) {
            throw new MerkleSynchronizationException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException(e);
        }
    }

    // ---- Factory method for the learner tree view ----

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
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH -> new LearnerPushVirtualTreeView(this, mapStats);
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
                        + ". Supported modes: PUSH, PULL_TOP_TO_BOTTOM,"
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
        if (virtualMap == null) {
            throw new IllegalStateException("Reconnect has not completed; VirtualMap is not yet available");
        }
        return virtualMap;
    }
}
