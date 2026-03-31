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
import org.hiero.base.crypto.Hashable;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Manages the reconnect process on the learner side for a virtual map. This class encapsulates
 * all reconnect-specific state and logic that was previously embedded inside {@link VirtualMap},
 * keeping {@link VirtualMap} focused on its core data-map responsibilities.
 *
 * <p>The reconnect lifecycle is:
 * <ol>
 *   <li>Create with {@link #create(VirtualMap)} from the learner's current (outdated) map.</li>
 *   <li>Build a {@link LearnerTreeView} via {@link #buildLearnerView(ReconnectConfig, ReconnectMapStats)}.</li>
 *   <li>Use this instance (which implements {@link Hashable}) and the view with a
 *       {@code LearningSynchronizer}.</li>
 *   <li>After synchronization completes, retrieve the fully initialized map with
 *       {@link #getVirtualMap()}.</li>
 * </ol>
 *
 * <p>No {@link VirtualMap} is created until {@link #close()} is called (which
 * happens internally when the learner tree view is closed), at which point a fresh, fully
 * initialized {@link VirtualMap} is constructed from the reconnected state.
 */
public final class VirtualMapReconnect implements Hashable {

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
    private final VirtualMapMetadata reconnectState;
    private final ConcurrentBlockingIterator<VirtualLeafBytes> reconnectIterator;
    private final CompletableFuture<Hash> reconnectHashingFuture;
    private final AtomicBoolean reconnectHashingStarted;
    private ReconnectHashLeafFlusher reconnectFlusher;
    private ReconnectNodeRemover nodeRemover;

    // ---- Set after reconnect completes ----

    /** The final hash produced by the reconnect hashing process. Null for empty trees. */
    @Nullable
    private Hash finalHash;

    /**
     * The fully initialized {@link VirtualMap} created at the end of the reconnect process.
     * Null until {@link #close()} has been called.
     */
    @Nullable
    private VirtualMap virtualMap;

    /**
     * Private constructor — use {@link #create(VirtualMap)} as the entry point.
     */
    private VirtualMapReconnect(@NonNull final VirtualMap originalMap) {
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

        // Initialize the reconnect-specific state.
        this.reconnectState = new VirtualMapMetadata();
        this.reconnectIterator = new ConcurrentBlockingIterator<>(MAX_RECONNECT_HASHING_BUFFER_SIZE);
        this.reconnectHashingFuture = new CompletableFuture<>();
        this.reconnectHashingStarted = new AtomicBoolean(false);
    }

    /**
     * Creates a new {@link VirtualMapReconnect} from the learner's current (outdated) virtual map.
     *
     * <p>The original map is hashed eagerly here so that the teacher can inspect hashes during
     * the synchronization process to determine which nodes need to be transferred.
     *
     * @param originalMap the learner's current virtual map; must not be null
     * @return a reconnect helper ready to be used with a {@code LearningSynchronizer}
     */
    @NonNull
    public static VirtualMapReconnect create(@NonNull final VirtualMap originalMap) {
        requireNonNull(originalMap, "originalMap must not be null");
        // Ensure the original map is hashed. Once hashed, all internal nodes are also hashed,
        // which is required for the reconnect process — the teacher uses these hashes to decide
        // whether to send the underlying nodes.
        originalMap.getHash();
        return new VirtualMapReconnect(originalMap);
    }

    // ---- Accessors for learner tree views ----

    /**
     * Returns the metadata of the original (pre-reconnect) map.
     */
    @NonNull
    public VirtualMapMetadata getOriginalState() {
        return originalState;
    }

    /**
     * Returns the metadata being populated during reconnect.
     */
    @NonNull
    public VirtualMapMetadata getReconnectState() {
        return reconnectState;
    }

    /**
     * Returns the record accessor for the original (pre-reconnect) map.
     */
    @NonNull
    public RecordAccessor getOriginalRecords() {
        return originalRecords;
    }

    /**
     * Returns the data source being updated during reconnect.
     */
    @NonNull
    public VirtualDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Returns the virtual map configuration.
     */
    @NonNull
    public VirtualMapConfig getVirtualMapConfig() {
        return virtualMapConfig;
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
        assert nodeRemover != null : "buildLearnerView() must be called first";
        reconnectState.setPaths(firstLeafPath, lastLeafPath);
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
        assert nodeRemover != null : "buildLearnerView() must be called first";
        nodeRemover.newLeafNode(leaf.path(), leaf.keyBytes());
        handleReconnectLeaf(leaf);
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
    public void close() {
        logger.info(RECONNECT.getMarker(), "call nodeRemover.allNodesReceived()");
        nodeRemover.allNodesReceived();
        endLearnerReconnect();
    }

    /**
     * Feeds a leaf record received from the teacher into the reconnect hashing pipeline.
     * May block if the hashing thread is slower than the incoming data rate.
     *
     * @param leafRecord the leaf received from the teacher; must not be null
     */
    private void handleReconnectLeaf(@NonNull final VirtualLeafBytes<?> leafRecord) {
        try {
            reconnectIterator.supply(leafRecord);
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
     * Starts the background hashing thread for the reconnect tree.
     * Must be called after the root response has been received from the teacher (i.e., after
     * first and last leaf paths are known).
     *
     * @param firstLeafPath first leaf path in the reconnected tree
     * @param lastLeafPath  last leaf path in the reconnected tree
     */
    private void prepareReconnectHashing(final long firstLeafPath, final long lastLeafPath) {
        assert reconnectFlusher != null : "Cannot prepare reconnect hashing: buildLearnerView() must be called first";
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
     * Called by {@link #close()} after all nodes have been signalled as received.
     */
    private void endLearnerReconnect() {
        try {
            logger.info(RECONNECT.getMarker(), "call reconnectIterator.close()");
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
        logger.info(RECONNECT.getMarker(), "endLearnerReconnect() complete");
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
    public LearnerTreeView buildLearnerView(
            @NonNull final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats) {
        reconnectFlusher =
                new ReconnectHashLeafFlusher(dataSource, virtualMapConfig.reconnectFlushInterval(), statistics);
        this.nodeRemover = new ReconnectNodeRemover(
                originalRecords, originalState.getFirstLeafPath(), originalState.getLastLeafPath(), reconnectFlusher);
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH ->
                new LearnerPushVirtualTreeView(this, originalRecords, originalState, reconnectState, mapStats);
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM ->
                new LearnerPullVirtualTreeView(
                        reconnectConfig,
                        this,
                        originalRecords,
                        originalState,
                        reconnectState,
                        new TopToBottomTraversalOrder(),
                        mapStats);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC ->
                new LearnerPullVirtualTreeView(
                        reconnectConfig,
                        this,
                        originalRecords,
                        originalState,
                        reconnectState,
                        new TwoPhasePessimisticTraversalOrder(),
                        mapStats);
            case VirtualMapReconnectMode.PULL_PARALLEL_SYNC ->
                new LearnerPullVirtualTreeView(
                        reconnectConfig,
                        this,
                        originalRecords,
                        originalState,
                        reconnectState,
                        new ParallelSyncTraversalOrder(),
                        mapStats);
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
     * {@link #buildLearnerView(ReconnectConfig, ReconnectMapStats)} has been closed.
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

    // ---- Hashable implementation ----
    // VirtualMapReconnect is self-hashing. The hash is computed by the background hashing thread
    // during reconnect and becomes available once close() completes.

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSelfHashing() {
        return true;
    }

    /**
     * Returns the hash of the reconnected tree.
     *
     * <p>Before reconnect completes, returns {@code null}. After reconnect completes, returns
     * the hash computed during reconnect (or triggers lazy computation for empty trees via the
     * underlying {@link VirtualMap}).
     */
    @Override
    @Nullable
    public Hash getHash() {
        // After close() the virtualMap is available; delegate to it so that
        // empty-tree hashing (where no hashing thread was started) is also handled correctly.
        if (virtualMap != null) {
            return virtualMap.getHash();
        }
        return finalHash;
    }

    /**
     * {@inheritDoc}
     * This object is self-hashing; calling this method throws {@link UnsupportedOperationException}.
     */
    @Override
    public void setHash(final Hash hash) {
        throw new UnsupportedOperationException("VirtualMapReconnect is self-hashing");
    }
}
