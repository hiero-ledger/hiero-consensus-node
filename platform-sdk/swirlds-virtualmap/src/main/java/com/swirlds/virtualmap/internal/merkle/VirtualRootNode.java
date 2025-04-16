// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static com.swirlds.virtualmap.internal.Path.FIRST_LEFT_PATH;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getIndexInRank;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.getPathForRankAndIndex;
import static com.swirlds.virtualmap.internal.Path.getRank;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;
import static com.swirlds.virtualmap.internal.Path.getSiblingPath;
import static com.swirlds.virtualmap.internal.Path.isFarRight;
import static com.swirlds.virtualmap.internal.Path.isLeft;
import static com.swirlds.virtualmap.internal.merkle.VirtualMapState.CLASS_ID;
import static com.swirlds.virtualmap.internal.merkle.VirtualMapState.MAX_LABEL_LENGTH;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.internal.AbstractMerkleInternal;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.CustomReconnectRoot;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.merkle.utility.DebugIterationEndpoint;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.constructable.constructors.VirtualRootNodeConstructor;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualStateAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.pipeline.VirtualPipeline;
import com.swirlds.virtualmap.internal.pipeline.VirtualRoot;
import com.swirlds.virtualmap.internal.reconnect.ConcurrentBlockingIterator;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.LearnerPushVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashLeafFlusher;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import com.swirlds.virtualmap.internal.reconnect.ReconnectNodeRemover;
import com.swirlds.virtualmap.internal.reconnect.ReconnectState;
import com.swirlds.virtualmap.internal.reconnect.TeacherPullVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.TeacherPushVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;

/**
 * An {@link AbstractMerkleInternal} that represents the root node of a virtual tree. This root node always
 * exists, even if no leaves exist.
 *
 * <hr>
 * <p><strong>Hashing</strong></p>
 * <p>
 * This class implements a multithreaded 'leaf-first' hashing algorithm that visits only those nodes required
 * for hashing and visits them at most exactly once. The set of leaves modified during the round (so-called "dirty"
 * leaves) and sorts them such that the first leaf in the list is the leaf with the largest path, and the last leaf
 * has the lowest path. (A path in our system is an incrementing number where root is 0, root's left child is 1,
 * root's right child is 2, root's left-most grand child is 3, and so forth, rank by rank from left to right). This
 * algorithm is <strong>not</strong> depth-first or breadth-first and requires a path-to-node lookup map to work
 * if the nodes do not already have a reference to the parent. Since our leaves are "realized" on demand, most of
 * them will not have references to their parent. However, as the graph builds up over time, many of the parent
 * nodes in the lower ranks (those closer to root) will have strong references to their parents, obviating the
 * need to look up those nodes in the map.
 * <p>
 * This class is responsible for hashing itself. This class has its own thread pool and
 * worker threads which it uses for implementing the algorithm. If the entire in-memory Merkle tree is modified
 * to use the same algorithm, then this code can be moved or eliminated.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
@DebugIterationEndpoint
@ConstructableClass(value = CLASS_ID, constructorType = VirtualRootNodeConstructor.class)
public final class VirtualRootNode<K extends VirtualKey, V extends VirtualValue> extends PartialBinaryMerkleInternal
        implements CustomReconnectRoot<Long, Long>, ExternalSelfSerializable, VirtualRoot<K, V>, MerkleInternal {

    private static final String NO_NULL_KEYS_ALLOWED_MESSAGE = "Null keys are not allowed";

    /**
     * Used for serialization.
     */
    public static final long CLASS_ID = 0x4a7d82719a5e1af5L;

    /**
     * This version number should be used to handle compatibility issues that may arise from any future changes
     */
    public static class ClassVersion {
        public static final int VERSION_1_ORIGINAL = 1;
        public static final int VERSION_2_KEYVALUE_SERIALIZERS = 2;
        public static final int VERSION_3_NO_NODE_CACHE = 3;

        public static final int CURRENT_VERSION = VERSION_3_NO_NODE_CACHE;
    }

    /**
     * Use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(VirtualRootNode.class);

    /**
     * The number of elements to have in the buffer used during reconnect on a learner when passing
     * leaves to the hashing system. The size of this variable will depend on the incoming rate
     * of leaves vs. the speed of hashing.
     */
    private static final int MAX_RECONNECT_HASHING_BUFFER_SIZE = 10_000_000;
    /**
     * The number of elements to have in the buffer used during rehashing on start.
     */
    private static final int MAX_REHASHING_BUFFER_SIZE = 10_000_000;

    /**
     * The number of seconds to wait for the hashing buffer during learner-reconnect before we
     * cancel the reconnect with an exception. If we cannot make space after this many seconds,
     * then it means a single round of hashing has exceeded this time threshold.
     */
    private static final int MAX_RECONNECT_HASHING_BUFFER_TIMEOUT = 60;

    /**
     * The number of seconds to wait for the full leaf rehash process to finish
     * (see {@link VirtualRootNode#fullLeafRehashIfNecessary()}) before we fail with an exception.
     */
    private static final int MAX_FULL_REHASHING_TIMEOUT = 3600; // 1 hour

    /** Virtual Map platform configuration */
    @NonNull
    private final VirtualMapConfig virtualMapConfig;

    /**
     * The maximum size() we have reached, where we have (already) recorded a warning message about how little
     * space is left before this {@link VirtualRootNode} hits the size limit.  We retain this information
     * because if we later delete some nodes and then add some more, we don't want to trigger a duplicate warning.
     */
    private long maxSizeReachedTriggeringWarning = 0;

    private KeySerializer<K> keySerializer;

    private ValueSerializer<V> valueSerializer;

    /**
     * A {@link VirtualDataSourceBuilder} used for creating instances of {@link VirtualDataSource}.
     * The data source used by this instance is created from this builder. The builder is needed
     * during reconnect to create a new data source based on a snapshot directory, or in
     * various other scenarios.
     */
    private VirtualDataSourceBuilder dataSourceBuilder;

    /**
     * Provides access to the {@link VirtualDataSource} for tree data.
     * All instances of {@link VirtualRootNode} in the "family" (i.e. that are copies
     * going back to some first progenitor) share the exact same dataSource instance.
     */
    private VirtualDataSource dataSource;

    /**
     * A cache for virtual tree nodes. This cache is very specific for this use case. The elements
     * in the cache are those nodes that were modified by this root node, or any copy of this node, that have
     * not yet been written to disk. This cache is used for two purposes. First, we avoid writing to
     * disk until the round is completed and hashed as both a performance enhancement and, more critically,
     * to avoid having to make the filesystem fast-copyable. Second, since modifications are not written
     * to disk, we must cache them here to return correct and consistent results to callers of the map-like APIs.
     * <p>
     * Deleted leaves are represented with records that have the "deleted" flag set.
     * <p>
     * Since this is fast-copyable and shared across all copies of a {@link VirtualRootNode}, it contains the changed
     * leaves over history. Since we always flush from oldest to newest, we know for certain that
     * anything here is at least as new as, or newer than, what is on disk. So we check it first whenever
     * we need a leaf. This allows us to keep the disk simple and not fast-copyable.
     */
    private VirtualNodeCache<K, V> cache;

    /**
     * An interface through which the {@link VirtualRootNode} can access persistent virtual map state, such
     * as the first leaf path, last leaf path, name, and other state. We do not access the {@link VirtualMapState}
     * directly because the {@link VirtualMapState} instance may change over time, so we use this interface
     * instead and allow the {@link VirtualMap} to provide indirection onto the current state data.
     */
    private VirtualStateAccessor state;

    /**
     * An interface through which the {@link VirtualRootNode} can access record data from the cache and the
     * data source. By encapsulating this logic in a RecordAccessor, we make it convenient to access records
     * using a combination of different caches, states, and data sources, which becomes important for reconnect
     * and other uses. This should never be null except for a brief window during initialization / reconnect /
     * serialization.
     */
    private RecordAccessor<K, V> records;

    /**
     * The hasher is responsible for hashing data in a virtual merkle tree.
     */
    private final VirtualHasher<K, V> hasher;

    /**
     * The {@link VirtualPipeline}, shared across all copies of a given {@link VirtualRootNode}, maintains the
     * lifecycle of the nodes, making sure they are merged or flushed or hashed in order and according to the
     * defined lifecycle rules. This class makes calls to the pipeline, and the pipeline calls back methods
     * defined in this class.
     */
    private VirtualPipeline<K, V> pipeline;

    /**
     * Hash of this root node. If null, the node isn't hashed yet.
     */
    private final AtomicReference<Hash> hash = new AtomicReference<>();

    /**
     * If true, then this copy of {@link VirtualRootNode} should eventually be flushed to disk. A heuristic is
     * used to determine which copy is flushed.
     */
    private final AtomicBoolean shouldBeFlushed = new AtomicBoolean(false);

    /**
     * Flush threshold. If greater than zero, then this virtual root will be flushed to disk, if
     * its estimated size exceeds the threshold. If this virtual root is explicitly requested to flush,
     * the threshold is not taken into consideration.
     *
     * <p>By default, the threshold is set to {@link VirtualMapConfig#copyFlushThreshold()}. The
     * threshold is inherited by all copies.
     */
    private final AtomicLong flushThreshold = new AtomicLong();

    /**
     * This latch is used to implement {@link #waitUntilFlushed()}.
     */
    private final CountDownLatch flushLatch = new CountDownLatch(1);

    /**
     * Specifies whether this current copy has been flushed. This will only be true if {@link #shouldBeFlushed}
     * is true, and it has been flushed.
     */
    private final AtomicBoolean flushed = new AtomicBoolean(false);

    /**
     * Specifies whether this current copy hsa been merged. This will only be true if {@link #shouldBeFlushed}
     * is false, and it has been merged.
     */
    private final AtomicBoolean merged = new AtomicBoolean(false);

    private final AtomicBoolean detached = new AtomicBoolean(false);

    /**
     * Created at the beginning of reconnect as a <strong>learner</strong>, this iterator allows
     * for other threads to feed its leaf records to be used during hashing.
     */
    private ConcurrentBlockingIterator<VirtualLeafRecord<K, V>> reconnectIterator = null;

    /**
     * A {@link java.util.concurrent.Future} that will contain the final hash result of the
     * reconnect hashing process.
     */
    private CompletableFuture<Hash> reconnectHashingFuture;

    /**
     * Set to true once the reconnect hashing thread has been started.
     */
    private AtomicBoolean reconnectHashingStarted;

    private VirtualStateAccessor reconnectState;
    /**
     * The {@link RecordAccessor} for the state, cache, and data source needed during reconnect.
     */
    private RecordAccessor<K, V> reconnectRecords;

    private VirtualStateAccessor fullyReconnectedState;

    /**
     * During reconnect as a learner, this is the root node in the old learner merkle tree.
     */
    private VirtualRootNode<K, V> originalMap;

    private ReconnectHashLeafFlusher<K, V> reconnectFlusher;

    private ReconnectNodeRemover<K, V> nodeRemover;

    private final long fastCopyVersion;

    private VirtualMapStatistics statistics;

    /**
     * This reference is used to assert that there is only one thread modifying the VM at a time.
     * NOTE: This field is used *only* if assertions are enabled, otherwise it always has null value.
     */
    private final AtomicReference<Thread> currentModifyingThreadRef = new AtomicReference<>(null);

    /**
     * Creates a new empty root node. This constructor is used for deserialization and
     * reconnects, not for normal use.
     *
     * @param virtualMapConfig virtual map platform configuration
     */
    public VirtualRootNode(final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(virtualMapConfig);
        this.fastCopyVersion = 0;
        // Hasher is required during reconnects
        this.hasher = new VirtualHasher<>();
        this.virtualMapConfig = virtualMapConfig;
        this.flushThreshold.set(virtualMapConfig.copyFlushThreshold());
        // All other fields are initialized in postInit()
    }

    /**
     * Creates a new root node using the provided data source builder to create node's
     * virtual data source.
     *
     * @param keySerializer virtual key serializer, must not be null
     * @param valueSerializer virtual value serializer, must not be null
     * @param dataSourceBuilder data source builder, must not be null
     * @param virtualMapConfig virtual map platform configuration
     */
    public VirtualRootNode(
            final @NonNull KeySerializer<K> keySerializer,
            final @NonNull ValueSerializer<V> valueSerializer,
            final @NonNull VirtualDataSourceBuilder dataSourceBuilder,
            final @NonNull VirtualMapConfig virtualMapConfig) {
        this.fastCopyVersion = 0;
        this.hasher = new VirtualHasher<>();
        this.virtualMapConfig = requireNonNull(virtualMapConfig);
        this.flushThreshold.set(virtualMapConfig.copyFlushThreshold());
        this.keySerializer = requireNonNull(keySerializer);
        this.valueSerializer = requireNonNull(valueSerializer);
        this.dataSourceBuilder = requireNonNull(dataSourceBuilder);
        // All other fields are initialized in postInit()
    }

    /**
     * Creates a copy of the given virtual root node. The created root node shares most
     * attributes with the source (hasher, data source, cache, pipeline, etc.) Created
     * copy's fast copy version is set to source' version + 1.
     *
     * @param source virtual root to copy, must not be null
     */
    @SuppressWarnings("CopyConstructorMissesField")
    private VirtualRootNode(VirtualRootNode<K, V> source) {
        super(source);
        this.fastCopyVersion = source.fastCopyVersion + 1;
        this.keySerializer = source.keySerializer;
        this.valueSerializer = source.valueSerializer;
        this.dataSourceBuilder = source.dataSourceBuilder;
        this.dataSource = source.dataSource;
        this.cache = source.cache.copy();
        this.hasher = source.hasher;
        this.reconnectHashingFuture = null;
        this.reconnectHashingStarted = null;
        this.reconnectIterator = null;
        this.reconnectRecords = null;
        this.fullyReconnectedState = null;
        this.maxSizeReachedTriggeringWarning = source.maxSizeReachedTriggeringWarning;
        this.pipeline = source.pipeline;
        this.flushThreshold.set(source.flushThreshold.get());
        this.statistics = source.statistics;
        this.virtualMapConfig = source.virtualMapConfig;

        if (this.pipeline.isTerminated()) {
            throw new IllegalStateException("A fast-copy was made of a VirtualRootNode with a terminated pipeline!");
        }

        // All other fields are initialized in postInit()
    }

    /**
     * Sets the {@link VirtualStateAccessor}. This method is called when this root node
     * is added as a child to its virtual map. It happens when virtual maps are created
     * from scratch, or during deserialization. It's also called after learner reconnects.
     *
     * @param state
     * 		The accessor. Cannot be null.
     */
    @SuppressWarnings("ClassEscapesDefinedScope")
    public void postInit(final VirtualStateAccessor state) {
        // We're reconnecting, state doesn't match cache or dataSource, gotta bail.
        if (originalMap != null) {
            fullyReconnectedState = state;
            return;
        }
        if (cache == null) {
            cache = new VirtualNodeCache<>(virtualMapConfig);
        }
        this.state = requireNonNull(state);
        updateShouldBeFlushed();
        requireNonNull(dataSourceBuilder);
        if (dataSource == null) {
            dataSource = dataSourceBuilder.build(state.getLabel(), true);
        }
        this.records = new RecordAccessorImpl<>(this.state, cache, keySerializer, valueSerializer, dataSource);
        if (statistics == null) {
            // Only create statistics instance if we don't yet have statistics. During a reconnect operation.
            // it is necessary to use the statistics object from the previous instance of the state.
            statistics = new VirtualMapStatistics(state.getLabel());
        }
        // VM size metric value is updated in add() and remove(). However, if no elements are added or
        // removed, the metric may have a stale value for a long time. Update it explicitly here
        statistics.setSize(size());
        // At this point in time the copy knows if it should be flushed or merged, and so it is safe
        // to register with the pipeline.
        if (pipeline == null) {
            pipeline = new VirtualPipeline<>(virtualMapConfig, state.getLabel());
        }
        pipeline.registerCopy(this);
    }

    /**
     * Do a full rehash of the persisted leaves of the map if the leaf hashes are absent. To determine if the leaf hashes
     * are available it checks tries to load a hash by the last leaf path.
     *
     * If the hash is not available, it will iterate over all the leaf nodes from the disk and rehash them.
     * The main difference between this and {@link VirtualRootNode#computeHash()} is that {@code computeHash}
     * update hashes for dirty leaves that are in the cache, while this method will rehash all the leaves from the disk.
     * {@code computeHash} doesn't have to take memory consumption into account because the cache is already in memory and
     * for this method it is critical to not load all the leaves into memory because there are too many of them.
     */
    public void fullLeafRehashIfNecessary() {
        requireNonNull(records, "Records must be initialized before rehashing");

        final ConcurrentBlockingIterator<VirtualLeafRecord<K, V>> rehashIterator =
                new ConcurrentBlockingIterator<>(MAX_REHASHING_BUFFER_SIZE);
        final CompletableFuture<Hash> fullRehashFuture = new CompletableFuture<>();
        final CompletableFuture<Void> leafFeedFuture = new CompletableFuture<>();
        // getting a range that is relevant for the data source
        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();
        if (firstLeafPath < 0 || lastLeafPath < 0) {
            logger.info(
                    STARTUP.getMarker(),
                    "Paths range is invalid, skipping full rehash in in the VirtualMap at {}. First path: {}, last path: {}",
                    getRoute(),
                    firstLeafPath,
                    lastLeafPath);
            return;
        }
        try {
            Hash loadedHash = dataSource.loadHash(lastLeafPath);
            if (loadedHash != null) {
                logger.info(
                        STARTUP.getMarker(),
                        "Calculated hash found for the last leaf path: {} in the VirtualMap at {}, skipping full rehash",
                        lastLeafPath,
                        getRoute());
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        logger.info(
                STARTUP.getMarker(),
                "Doing full rehash for the path range: {} - {}  in the VirtualMap at {}",
                firstLeafPath,
                lastLeafPath,
                getRoute());
        // Full leaf rehashing has nothing to do with reconnects, but existing reconnect mechanisms,
        // flusher and hash listener, work just fine in this scenario
        final ReconnectHashLeafFlusher<K, V> flusher = new ReconnectHashLeafFlusher<>(
                keySerializer, valueSerializer, dataSource, virtualMapConfig.reconnectFlushInterval(), statistics);
        final ReconnectHashListener<K, V> hashListener = new ReconnectHashListener<>(flusher);

        // This background thread will be responsible for hashing the tree and sending the
        // data to the hash listener to flush.
        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("virtualmap")
                .setThreadName("leafRehasher")
                .setRunnable(() -> fullRehashFuture.complete(hasher.hash(
                        records::findHash,
                        rehashIterator,
                        firstLeafPath,
                        lastLeafPath,
                        hashListener,
                        virtualMapConfig)))
                .setExceptionHandler((thread, exception) -> {
                    // Shut down the iterator.
                    rehashIterator.close();
                    final var message = "VirtualMap@" + getRoute() + " failed to do full rehash";
                    logger.error(EXCEPTION.getMarker(), message, exception);
                    fullRehashFuture.completeExceptionally(new MerkleSynchronizationException(message, exception));
                })
                .build()
                .start();

        // This background thread will be responsible for feeding the iterator with data.
        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("virtualmap")
                .setThreadName("leafFeeder")
                .setRunnable(() -> {
                    final long onePercent = (lastLeafPath - firstLeafPath) / 100 + 1;
                    try {
                        for (long i = firstLeafPath; i <= lastLeafPath; i++) {
                            try {
                                final VirtualLeafBytes leafBytes = dataSource.loadLeafRecord(i);
                                assert leafBytes != null : "Leaf record should not be null";
                                final VirtualLeafRecord<K, V> leafRecord =
                                        leafBytes.toRecord(keySerializer, valueSerializer);
                                try {
                                    rehashIterator.supply(leafRecord);
                                } catch (final MerkleSynchronizationException e) {
                                    throw e;
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new MerkleSynchronizationException(
                                            "Interrupted while waiting to supply a new leaf to the hashing iterator buffer",
                                            e);
                                } catch (final Exception e) {
                                    throw new MerkleSynchronizationException(
                                            "Failed to handle a leaf during full rehashing", e);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            // we don't care about tracking progress on small maps.
                            if (onePercent > 10 && i % onePercent == 0) {
                                logger.info(
                                        STARTUP.getMarker(),
                                        "Full rehash progress for the VirtualMap at {}: {}%",
                                        getRoute(),
                                        (i - firstLeafPath) / onePercent + 1);
                            }
                        }
                    } finally {
                        rehashIterator.close();
                    }
                    leafFeedFuture.complete(null);
                })
                .setExceptionHandler((thread, exception) -> {
                    // Shut down the iterator.
                    rehashIterator.close();
                    final var message = "VirtualMap@" + getRoute() + " failed to feed all leaves the hasher";
                    logger.error(EXCEPTION.getMarker(), message, exception);
                    leafFeedFuture.completeExceptionally(new MerkleSynchronizationException(message, exception));
                })
                .build()
                .start();

        try {
            final long start = System.currentTimeMillis();
            leafFeedFuture.get(MAX_FULL_REHASHING_TIMEOUT, SECONDS);
            final long secondsSpent = (System.currentTimeMillis() - start) / 1000;
            logger.info(
                    STARTUP.getMarker(),
                    "It took {} seconds to feed all leaves to the hasher for the VirtualMap at {}",
                    secondsSpent,
                    getRoute());
            setHashPrivate(fullRehashFuture.get(MAX_FULL_REHASHING_TIMEOUT - secondsSpent, SECONDS));
        } catch (ExecutionException e) {
            final var message = "VirtualMap@" + getRoute() + " failed to get hash during full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            final var message = "VirtualMap@" + getRoute() + " interrupted while full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (TimeoutException e) {
            final var message = "VirtualMap@" + getRoute() + "wasn't able to finish full rehashing in time";
            throw new MerkleSynchronizationException(message, e);
        }
    }

    /**
     * Gets the {@link VirtualStateAccessor} containing state for this copy of {@link VirtualRootNode}.
     *
     * @return The {@link VirtualStateAccessor}. Will not be null unless called during serialization before
     * 		serialization completes.
     */
    @SuppressWarnings("ClassEscapesDefinedScope")
    public VirtualStateAccessor getState() {
        return state;
    }

    /**
     * Gets the {@link VirtualDataSource}.
     *
     * @return The data source. Will not be null.
     */
    public VirtualDataSource getDataSource() {
        return dataSource;
    }

    KeySerializer<K> getKeySerializer() {
        return keySerializer;
    }

    ValueSerializer<V> getValueSerializer() {
        return valueSerializer;
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    public VirtualNodeCache<K, V> getCache() {
        return cache;
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    public RecordAccessor<K, V> getRecords() {
        return records;
    }

    // Exposed for tests only.
    public VirtualPipeline<K, V> getPipeline() {
        return pipeline;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRegisteredToPipeline(final VirtualPipeline<K, V> pipeline) {
        return pipeline == this.pipeline;
    }

    /*
     * Implementation of MerkleInternal and associated APIs
     **/

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.CURRENT_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends MerkleNode> T getChild(final int index) {
        if (isDestroyed()
                || dataSource == null
                || originalMap != null
                || state.getFirstLeafPath() == INVALID_PATH
                || index > 1) {
            return null;
        }

        final long path = index + 1L;
        final T node;
        if (path < state.getFirstLeafPath()) {
            final Hash hash = records.findHash(path);
            final VirtualHashRecord virtualHashRecord =
                    new VirtualHashRecord(path, hash != VirtualNodeCache.DELETED_HASH ? hash : null);
            //noinspection unchecked
            node = (T) (new VirtualInternalNode<>(this, virtualHashRecord));
        } else if (path <= state.getLastLeafPath()) {
            final VirtualLeafRecord<K, V> leafRecord = records.findLeafRecord(path, false);
            if (leafRecord == null) {
                throw new IllegalStateException("Invalid null record for child index " + index + " (path = "
                        + path + "). First leaf path = " + state.getFirstLeafPath() + ", last leaf path = "
                        + state.getLastLeafPath() + ".");
            }
            final Hash hash = records.findHash(path);
            //noinspection unchecked
            node = (T) (new VirtualLeafNode<>(leafRecord, hash != VirtualNodeCache.DELETED_HASH ? hash : null));
        } else {
            // The index is out of bounds. Maybe we have a root node with one leaf and somebody has asked
            // for the second leaf, in which case it would be null.
            return null;
        }

        final MerkleRoute route = this.getRoute().extendRoute(index);
        node.setRoute(route);
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualRootNode<K, V> copy() {
        throwIfImmutable();
        throwIfDestroyed();

        // After creating the copy, mark it as immutable and then register it with the pipeline.
        // We're careful about this ordering because the pipeline runs background threads, and
        // it is crucial that the copy is made and this instance is marked immutable *before*
        // those background threads run. Otherwise, they may try to hash this copy before it
        // has a chance to "seal" the cache, and we will get exceptions.
        final VirtualRootNode<K, V> copy = new VirtualRootNode<>(this);
        setImmutable(true);

        if (isHashed()) {
            // Special case: after a "reconnect", the mutable copy will already be hashed
            // at this point in time.
            cache.seal();
        }

        return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroyNode() {
        if (pipeline != null) {
            pipeline.destroyCopy(this);
        } else {
            logger.info(
                    VIRTUAL_MERKLE_STATS.getMarker(),
                    "Destroying virtual root node at route {}, but its pipeline is null. It may happen during failed reconnect",
                    getRoute());
            closeDataSource();
        }
    }

    /**
     * We always have *potentially* two children.
     *
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren() {
        return 2;
    }

    /**
     * This is never called for a {@link VirtualRootNode}.
     *
     * {@inheritDoc}
     */
    @Override
    protected void setChildInternal(final int index, final MerkleNode child) {
        throw new UnsupportedOperationException("You cannot set the child of a VirtualRootNode directly with this API");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void allocateSpaceForChild(final int index) {
        // No-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void checkChildIndexIsValid(final int index) {
        if (index < 0 || index > 1) {
            throw new IllegalChildIndexException(0, 1, index);
        }
    }

    /*
     * Implementation of Map-like methods
     **/

    /*
     * Gets the number of elements in this map.
     *
     * @return The number of key/value pairs in the map.
     */
    public long size() {
        return state.size();
    }

    /*
     * Gets whether this map is empty.
     *
     * @return True if the map is empty
     */
    public boolean isEmpty() {
        final long lastLeafPath = state.getLastLeafPath();
        return lastLeafPath == INVALID_PATH;
    }

    /**
     * Checks whether a leaf for the given key exists.
     *
     * @param key
     * 		The key. Cannot be null.
     * @return True if there is a leaf corresponding to this key.
     */
    public boolean containsKey(final K key) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final long path = records.findKey(key);
        statistics.countReadEntities();
        return path != INVALID_PATH;
    }

    /**
     * Gets the value associated with the given key.
     *
     * @param key
     * 		The key. This must not be null.
     * @return The value. The value may be null, or will be read only.
     */
    public V get(final K key) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final VirtualLeafRecord<K, V> rec = records.findLeafRecord(key, false);
        final V value = rec == null ? null : rec.getValue();
        statistics.countReadEntities();
        //noinspection unchecked
        return value == null ? null : (V) value.asReadOnly();
    }

    /**
     * Puts the key/value pair into the map. The key must not be null, but the value
     * may be null. The previous value, if it existed, is returned. If the entry was already in the map,
     * the value is replaced. If the mapping was not in the map, then a new entry is made.
     *
     * @param key
     * 		the key, cannot be null.
     * @param value
     * 		the value, may be null.
     */
    public void put(final K key, final V value) {
        throwIfImmutable();
        assert !isHashed() : "Cannot modify already hashed node";
        assert currentModifyingThreadRef.compareAndSet(null, Thread.currentThread());
        try {
            requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);

            final long path = records.findKey(key);
            if (path == INVALID_PATH) {
                // The key is not stored. So add a new entry and return.
                add(key, value);
                statistics.countAddedEntities();
                statistics.setSize(state.size());
                return;
            }

            final VirtualLeafRecord<K, V> leaf = new VirtualLeafRecord<>(path, key, value);
            cache.putLeaf(leaf);
            statistics.countUpdatedEntities();
        } finally {
            assert currentModifyingThreadRef.compareAndSet(Thread.currentThread(), null);
        }
    }

    /**
     * Removes the key/value pair denoted by the given key from the map. Has no effect
     * if the key didn't exist.
     *
     * @param key
     * 		The key to remove. Cannot be null.
     * @return The removed value. May return null if there was no value to remove or if the value was null.
     */
    public V remove(final K key) {
        throwIfImmutable();
        requireNonNull(key);
        assert currentModifyingThreadRef.compareAndSet(null, Thread.currentThread());
        try {
            // Verify whether the current leaf exists. If not, we can just return null.
            VirtualLeafRecord<K, V> leafToDelete = records.findLeafRecord(key, true);
            if (leafToDelete == null) {
                return null;
            }

            // Mark the leaf as being deleted.
            cache.deleteLeaf(leafToDelete);
            statistics.countRemovedEntities();

            // We're going to need these
            final long lastLeafPath = state.getLastLeafPath();
            final long firstLeafPath = state.getFirstLeafPath();
            final long leafToDeletePath = leafToDelete.getPath();

            // If the leaf was not the last leaf, then move the last leaf to take this spot
            if (leafToDeletePath != lastLeafPath) {
                final VirtualLeafRecord<K, V> lastLeaf = records.findLeafRecord(lastLeafPath, true);
                assert lastLeaf != null;
                cache.clearLeafPath(lastLeafPath);
                lastLeaf.setPath(leafToDeletePath);
                cache.putLeaf(lastLeaf);
                // NOTE: at this point, if leafToDelete was in the cache at some "path" index, it isn't anymore!
                // The lastLeaf has taken its place in the path index.
            }

            // If the parent of the last leaf is root, then we can simply do some bookkeeping.
            // Otherwise, we replace the parent of the last leaf with the sibling of the last leaf,
            // and mark it dirty. This covers all cases.
            final long lastLeafParent = getParentPath(lastLeafPath);
            if (lastLeafParent == ROOT_PATH) {
                if (firstLeafPath == lastLeafPath) {
                    // We just removed the very last leaf, so set these paths to be invalid
                    state.setFirstLeafPath(INVALID_PATH);
                    state.setLastLeafPath(INVALID_PATH);
                } else {
                    // We removed the second to last leaf, so the first & last leaf paths are now the same.
                    state.setLastLeafPath(FIRST_LEFT_PATH);
                    // One of the two remaining leaves is removed. When this virtual root copy is hashed,
                    // the root hash will be a product of the remaining leaf hash and a null hash at
                    // path 2. However, rehashing is only triggered, if there is at least one dirty leaf,
                    // while leaf 1 is not marked as such: neither its contents nor its path are changed.
                    // To fix it, mark it as dirty explicitly
                    final VirtualLeafRecord<K, V> leaf = records.findLeafRecord(1, true);
                    cache.putLeaf(leaf);
                }
            } else {
                final long lastLeafSibling = getSiblingPath(lastLeafPath);
                final VirtualLeafRecord<K, V> sibling = records.findLeafRecord(lastLeafSibling, true);
                assert sibling != null;
                cache.clearLeafPath(lastLeafSibling);
                cache.deleteHash(lastLeafParent);
                sibling.setPath(lastLeafParent);
                cache.putLeaf(sibling);

                // Update the first & last leaf paths
                state.setFirstLeafPath(lastLeafParent); // replaced by the sibling, it is now first
                state.setLastLeafPath(lastLeafSibling - 1); // One left of the last leaf sibling
            }
            if (statistics != null) {
                statistics.setSize(state.size());
            }

            // Get the value and return it (as read only).
            final V value = leafToDelete.getValue();
            //noinspection unchecked
            return value == null ? null : (V) value.asReadOnly();
        } finally {
            assert currentModifyingThreadRef.compareAndSet(Thread.currentThread(), null);
        }
    }

    /*
     * Shutdown implementation
     **/

    /**
     * {@inheritDoc}
     */
    @Override
    public void onShutdown(final boolean immediately) {
        if (immediately) {
            // If immediate shutdown is required then let the hasher know it is being stopped. If shutdown
            // is not immediate, the hasher will eventually stop once it finishes all of its work.
            hasher.shutdown();
        }
        closeDataSource();
    }

    private void closeDataSource() {
        // Shut down the data source. If this doesn't shut things down, then there isn't
        // much we can do aside from logging the fact. The node may well die before too long
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (final Exception e) {
                logger.error(
                        EXCEPTION.getMarker(), "Could not close the dataSource after all copies were destroyed", e);
            }
        }
    }

    /*
     * Merge implementation
     **/

    /**
     * {@inheritDoc}
     */
    @Override
    public void merge() {
        final long start = System.currentTimeMillis();
        if (!(isDestroyed() || isDetached())) {
            throw new IllegalStateException("merge is legal only after this node is destroyed or detached");
        }
        if (!isImmutable()) {
            throw new IllegalStateException("merge is only allowed on immutable copies");
        }
        if (!isHashed()) {
            throw new IllegalStateException("copy must be hashed before it is merged");
        }
        if (merged.get()) {
            throw new IllegalStateException("this copy has already been merged");
        }
        if (flushed.get()) {
            throw new IllegalStateException("a flushed copy can not be merged");
        }
        cache.merge();
        merged.set(true);

        final long end = System.currentTimeMillis();
        statistics.recordMerge(end - start);
        logger.debug(VIRTUAL_MERKLE_STATS.getMarker(), "Merged in {} ms", end - start);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMerged() {
        return merged.get();
    }

    /*
     * Flush implementation
     **/

    /**
     * If called, this copy of the map will eventually be flushed.
     */
    public void enableFlush() {
        this.shouldBeFlushed.set(true);
    }

    /**
     * Sets flush threshold for this virtual root. When a copy of this virtual root is created,
     * it inherits the threshold value.
     *
     * If this virtual root is explicitly marked to flush using {@link #enableFlush()}, changing
     * flush threshold doesn't have any effect.
     *
     * @param value The flush threshold, in bytes
     */
    public void setFlushThreshold(long value) {
        flushThreshold.set(value);
        updateShouldBeFlushed();
    }

    /**
     * Gets flush threshold for this virtual root.
     *
     * @return The flush threshold, in bytes
     */
    long getFlushThreshold() {
        return flushThreshold.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldBeFlushed() {
        // Check if this copy was explicitly marked to flush
        if (shouldBeFlushed.get()) {
            return true;
        }
        // Otherwise check its size and compare against flush threshold
        final long threshold = flushThreshold.get();
        return (threshold > 0) && (estimatedSize() >= threshold);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFlushed() {
        return flushed.get();
    }

    /**
     * If flush threshold isn't set for this virtual root, marks the root to flush based on
     * {@link VirtualMapConfig#flushInterval()} setting.
     */
    private void updateShouldBeFlushed() {
        if (flushThreshold.get() <= 0) {
            // If copy size flush threshold is not set, use flush interval
            this.shouldBeFlushed.set(fastCopyVersion != 0 && fastCopyVersion % virtualMapConfig.flushInterval() == 0);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilFlushed() throws InterruptedException {
        if (!flushLatch.await(1, MINUTES)) {
            // Unless the platform has enacted a freeze, if it takes
            // more than a minute to become flushed then something is
            // terribly wrong.
            // Write debug information for the pipeline to the log.

            pipeline.logDebugInfo();
            flushLatch.await();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        if (!isImmutable()) {
            throw new IllegalStateException("mutable copies can not be flushed");
        }
        if (flushed.get()) {
            throw new IllegalStateException("This map has already been flushed");
        }
        if (merged.get()) {
            throw new IllegalStateException("a merged copy can not be flushed");
        }

        final long start = System.currentTimeMillis();
        flush(cache, state, dataSource);
        cache.release();
        final long end = System.currentTimeMillis();
        flushed.set(true);
        flushLatch.countDown();
        statistics.recordFlush(end - start);
        logger.debug(
                VIRTUAL_MERKLE_STATS.getMarker(),
                "Flushed {} {} in {} ms",
                state.getLabel(),
                cache.getFastCopyVersion(),
                end - start);
    }

    private void flush(VirtualNodeCache<K, V> cacheToFlush, VirtualStateAccessor stateToUse, VirtualDataSource ds) {
        try {
            // Get the leaves that were changed and sort them by path so that lower paths come first
            final Stream<VirtualLeafBytes> dirtyLeaves = cacheToFlush
                    .dirtyLeavesForFlush(stateToUse.getFirstLeafPath(), stateToUse.getLastLeafPath())
                    .map(r -> r.toBytes(keySerializer, valueSerializer));
            // Get the deleted leaves
            final Stream<VirtualLeafBytes> deletedLeaves =
                    cacheToFlush.deletedLeaves().map(r -> r.toBytes(keySerializer, valueSerializer));
            // Save the dirty hashes
            final Stream<VirtualHashRecord> dirtyHashes =
                    cacheToFlush.dirtyHashesForFlush(stateToUse.getLastLeafPath());
            ds.saveRecords(
                    stateToUse.getFirstLeafPath(),
                    stateToUse.getLastLeafPath(),
                    dirtyHashes,
                    dirtyLeaves,
                    deletedLeaves);
        } catch (final ClosedByInterruptException ex) {
            logger.info(
                    TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT.getMarker(),
                    "flush interrupted - this is probably not an error " + "if this happens shortly after a reconnect");
            Thread.currentThread().interrupt();
        } catch (final IOException ex) {
            logger.error(EXCEPTION.getMarker(), "Error while flushing VirtualMap", ex);
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public long estimatedSize() {
        final long estimatedDirtyLeavesCount = cache.estimatedDirtyLeavesCount();
        final long estimatedLeavesSize = estimatedDirtyLeavesCount
                * (Long.BYTES // path
                        + DigestType.SHA_384.digestLength() // hash
                        + keySerializer.getTypicalSerializedSize() // key
                        + valueSerializer.getTypicalSerializedSize()); // value

        final long estimatedInternalsCount = cache.estimatedHashesCount();
        final long estimatedInternalsSize = estimatedInternalsCount
                * (Long.BYTES // path
                        + DigestType.SHA_384.digestLength()); // hash

        return estimatedInternalsSize + estimatedLeavesSize;
    }

    // Serialization implementation

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out, final Path outputDirectory) throws IOException {
        pipeline.pausePipelineAndRun("detach", () -> {
            snapshot(outputDirectory);
            return null;
        });
        out.writeNormalisedString(state.getLabel());
        out.writeSerializable(dataSourceBuilder, true);
        out.writeSerializable(keySerializer, true);
        out.writeSerializable(valueSerializer, true);
        out.writeLong(cache.getFastCopyVersion());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings({"deprecation", "unchecked"})
    public void deserialize(final SerializableDataInputStream in, final Path inputDirectory, final int version)
            throws IOException {
        final String label = in.readNormalisedString(MAX_LABEL_LENGTH);
        dataSourceBuilder = in.readSerializable();
        dataSource = dataSourceBuilder.restore(label, inputDirectory);
        if (version < ClassVersion.VERSION_2_KEYVALUE_SERIALIZERS) {
            // In version 1, key and value serializers are stored in the data source
            keySerializer = dataSource.getKeySerializer();
            if (keySerializer == null) {
                throw new IllegalStateException("No key serializer available");
            }
            valueSerializer = dataSource.getValueSerializer();
            if (valueSerializer == null) {
                throw new IllegalStateException("No value serializer available");
            }
        } else {
            // In version 2 and later, the serializers are a part of VirtualRootNode
            keySerializer = in.readSerializable();
            valueSerializer = in.readSerializable();
        }
        if (version < ClassVersion.VERSION_3_NO_NODE_CACHE) {
            // Future work: once all states are version 3 or later, this code branch can be
            // removed alltogether, and cache may be a final field
            cache = in.readSerializable();
        } else {
            cache = new VirtualNodeCache<>(virtualMapConfig, in.readLong());
        }
    }

    // Hashing implementation

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSelfHashing() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        if (hash.get() == null) {
            pipeline.hashCopy(this);
        }
        return hash.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(final Hash hash) {
        throw new UnsupportedOperationException("data type is self hashing");
    }

    /**
     * This class is self-hashing, it doesn't use inherited {@link #setHash} method. Instead,
     * the hash is set using this private method.
     *
     * @param value Hash value to set
     */
    private void setHashPrivate(@Nullable final Hash value) {
        hash.set(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invalidateHash() {
        throw new UnsupportedOperationException("this node is self hashing");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHashed() {
        return hash.get() != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHash() {
        if (hash.get() != null) {
            return;
        }

        final long start = System.currentTimeMillis();

        // Make sure the cache is immutable for leaf changes but mutable for internal node changes
        cache.prepareForHashing();

        // Compute the root hash of the virtual tree
        final VirtualHashListener<K, V> hashListener = new VirtualHashListener<>() {
            @Override
            public void onNodeHashed(final long path, final Hash hash) {
                cache.putHash(path, hash);
            }
        };
        Hash virtualHash = hasher.hash(
                records::findHash,
                cache.dirtyLeavesForHash(state.getFirstLeafPath(), state.getLastLeafPath())
                        .iterator(),
                state.getFirstLeafPath(),
                state.getLastLeafPath(),
                hashListener,
                virtualMapConfig);

        if (virtualHash == null) {
            final Hash rootHash = (state.size() == 0) ? null : records.findHash(0);
            virtualHash = (rootHash != null) ? rootHash : hasher.emptyRootHash();
        }

        // There are no remaining changes to be made to the cache, so we can seal it.
        cache.seal();

        // Make sure the copy is marked as hashed after the cache is sealed, otherwise the chances
        // are an attempt to merge the cache will fail because the cache hasn't been sealed yet
        setHashPrivate(virtualHash);

        final long end = System.currentTimeMillis();
        statistics.recordHash(end - start);
    }

    /*
     * Detach implementation
     **/

    /**
     * {@inheritDoc}
     */
    @Override
    public RecordAccessor<K, V> detach() {
        if (isDestroyed()) {
            throw new IllegalStateException("detach is illegal on already destroyed copies");
        }
        if (!isImmutable()) {
            throw new IllegalStateException("detach is only allowed on immutable copies");
        }
        if (!isHashed()) {
            throw new IllegalStateException("copy must be hashed before it is detached");
        }

        detached.set(true);

        // The pipeline is paused while this runs, so I can go ahead and call snapshot on the data
        // source, and also snapshot the cache. I will create a new "RecordAccessor" for the detached
        // record state.
        final VirtualDataSource dataSourceCopy = dataSourceBuilder.copy(dataSource, false, false);
        final VirtualNodeCache<K, V> cacheSnapshot = cache.snapshot();
        return new RecordAccessorImpl<>(state, cacheSnapshot, keySerializer, valueSerializer, dataSourceCopy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void snapshot(final Path destination) throws IOException {
        if (isDestroyed()) {
            throw new IllegalStateException("snapshot is illegal on already destroyed copies");
        }
        if (!isImmutable()) {
            throw new IllegalStateException("snapshot is only allowed on immutable copies");
        }
        if (!isHashed()) {
            throw new IllegalStateException("copy must be hashed before snapshot");
        }

        detached.set(true);

        // The pipeline is paused while this runs, so I can go ahead and call snapshot on the data
        // source, and also snapshot the cache. I will create a new "RecordAccessor" for the detached
        // record state.
        final VirtualDataSource dataSourceCopy = dataSourceBuilder.copy(dataSource, false, true);
        try {
            final VirtualNodeCache<K, V> cacheSnapshot = cache.snapshot();
            flush(cacheSnapshot, state, dataSourceCopy);
            dataSourceBuilder.snapshot(destination, dataSourceCopy);
        } finally {
            dataSourceCopy.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDetached() {
        return detached.get();
    }

    /*
     * Reconnect Implementation
     **/

    /**
     * {@inheritDoc}
     */
    @Override
    public TeacherTreeView<Long> buildTeacherView(final ReconnectConfig reconnectConfig) {
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH -> new TeacherPushVirtualTreeView<>(
                    getStaticThreadManager(), reconnectConfig, this, state, pipeline);
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM -> new TeacherPullVirtualTreeView<>(
                    getStaticThreadManager(), reconnectConfig, this, state, pipeline);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC -> new TeacherPullVirtualTreeView<>(
                    getStaticThreadManager(), reconnectConfig, this, state, pipeline);
            default -> throw new UnsupportedOperationException(
                    "Unknown reconnect mode: " + virtualMapConfig.reconnectMode());
        };
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public void setupWithOriginalNode(final MerkleNode originalNode) {
        assert originalNode instanceof VirtualRootNode : "The original node was not a VirtualRootNode!";

        // NOTE: If we're reconnecting, then the old tree is toast. We hold onto the originalMap to
        // restart from that position again in the future if needed, but we're never going to use
        // the old map again. We need the data source builder from the old map so, we can create
        // new data sources in this new map with all the right settings.
        //noinspection unchecked
        originalMap = (VirtualRootNode<K, V>) originalNode;
        this.dataSourceBuilder = originalMap.dataSourceBuilder;

        this.keySerializer = originalMap.keySerializer;
        this.valueSerializer = originalMap.valueSerializer;

        reconnectState = new ReconnectState(-1, -1);
        reconnectRecords = originalMap.pipeline.pausePipelineAndRun("copy", () -> {
            // shutdown background compaction on original data source as it is no longer needed to be running as all
            // data
            // in that data source is only there as a starting point for reconnect now. So compacting it further is not
            // helpful and will just burn resources.
            originalMap.dataSource.stopAndDisableBackgroundCompaction();

            // Take a snapshot, and use the snapshot database as my data source
            this.dataSource = dataSourceBuilder.copy(originalMap.dataSource, true, false);

            // The old map's cache is going to become immutable, but that's OK, because the old map
            // will NEVER be updated again.
            assert originalMap.isHashed() : "The system should have made sure this was hashed by this point!";
            final VirtualNodeCache<K, V> snapshotCache = originalMap.cache.snapshot();
            flush(snapshotCache, originalMap.state, this.dataSource);

            // I assume an empty node cache can be used below rather than snapshotCache, since all the
            // cache entries are flushed to the data source anyway. However, using snapshotCache may
            // be slightly faster, because it's in memory
            return new RecordAccessorImpl<>(reconnectState, snapshotCache, keySerializer, valueSerializer, dataSource);
        });

        // Set up the VirtualHasher which we will use during reconnect.
        // Initial timeout is intentionally very long, timeout is reduced once we receive the first leaf in the tree.
        reconnectIterator = new ConcurrentBlockingIterator<>(MAX_RECONNECT_HASHING_BUFFER_SIZE);
        reconnectHashingFuture = new CompletableFuture<>();
        reconnectHashingStarted = new AtomicBoolean(false);

        // Current statistics can only be registered when the node boots, requiring statistics
        // objects to be passed from version to version of the state.
        dataSource.copyStatisticsFrom(originalMap.dataSource);
        statistics = originalMap.statistics;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupWithNoData() {
        // No-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LearnerTreeView<Long> buildLearnerView(
            final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats) {
        assert originalMap != null;
        // During reconnect we want to look up state from the original records
        final VirtualStateAccessor originalState = originalMap.getState();
        reconnectFlusher = new ReconnectHashLeafFlusher<>(
                keySerializer,
                valueSerializer,
                reconnectRecords.getDataSource(),
                virtualMapConfig.reconnectFlushInterval(),
                statistics);
        nodeRemover = new ReconnectNodeRemover<>(
                originalMap.getRecords(),
                originalState.getFirstLeafPath(),
                originalState.getLastLeafPath(),
                reconnectFlusher);
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH -> new LearnerPushVirtualTreeView<>(
                    reconnectConfig, this, originalMap.records, originalState, reconnectState, nodeRemover, mapStats);
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM -> {
                final NodeTraversalOrder topToBottom = new TopToBottomTraversalOrder();
                yield new LearnerPullVirtualTreeView<>(
                        reconnectConfig,
                        this,
                        originalMap.records,
                        originalState,
                        reconnectState,
                        nodeRemover,
                        topToBottom,
                        mapStats);
            }
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC -> {
                final NodeTraversalOrder twoPhasePessimistic = new TwoPhasePessimisticTraversalOrder();
                yield new LearnerPullVirtualTreeView<>(
                        reconnectConfig,
                        this,
                        originalMap.records,
                        originalState,
                        reconnectState,
                        nodeRemover,
                        twoPhasePessimistic,
                        mapStats);
            }
            default -> throw new UnsupportedOperationException(
                    "Unknown reconnect mode: " + virtualMapConfig.reconnectMode());
        };
    }

    /**
     * Pass all statistics to the registry.
     *
     * @param metrics
     * 		reference to the metrics system
     */
    public void registerMetrics(final Metrics metrics) {
        statistics.registerMetrics(metrics);
        pipeline.registerMetrics(metrics);
        dataSource.registerMetrics(metrics);
    }

    /**
     * This method is passed all leaf nodes that are deserialized during a reconnect operation.
     *
     * @param leafRecord
     * 		describes a leaf
     */
    public void handleReconnectLeaf(final VirtualLeafRecord<K, V> leafRecord) {
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

    public void prepareReconnectHashing(final long firstLeafPath, final long lastLeafPath) {
        assert reconnectFlusher != null : "Cannot prepare reconnect hashing, since reconnect is not started";
        // The hash listener will be responsible for flushing stuff to the reconnect data source
        final ReconnectHashListener<K, V> hashListener = new ReconnectHashListener<>(reconnectFlusher);

        // This background thread will be responsible for hashing the tree and sending the
        // data to the hash listener to flush.
        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("virtualmap")
                .setThreadName("hasher")
                .setRunnable(() -> reconnectHashingFuture.complete(hasher.hash(
                        reconnectRecords::findHash,
                        reconnectIterator,
                        firstLeafPath,
                        lastLeafPath,
                        hashListener,
                        virtualMapConfig)))
                .setExceptionHandler((thread, exception) -> {
                    // Shut down the iterator. This will cause reconnect to terminate.
                    reconnectIterator.close();
                    final var message = "VirtualMap@" + getRoute() + " failed to hash during reconnect";
                    logger.error(EXCEPTION.getMarker(), message, exception);
                    reconnectHashingFuture.completeExceptionally(
                            new MerkleSynchronizationException(message, exception));
                })
                .build()
                .start();

        reconnectHashingStarted.set(true);
    }

    public void endLearnerReconnect() {
        try {
            logger.info(RECONNECT.getMarker(), "call reconnectIterator.close()");
            reconnectIterator.close();
            if (reconnectHashingStarted.get()) {
                // Only block on future if the hashing thread is known to have been started.
                logger.info(RECONNECT.getMarker(), "call setHashPrivate()");
                setHashPrivate(reconnectHashingFuture.get());
            } else {
                logger.warn(RECONNECT.getMarker(), "virtual map hashing thread was never started");
            }
            nodeRemover = null;
            originalMap = null;
            logger.info(RECONNECT.getMarker(), "call postInit()");
            postInit(fullyReconnectedState);
        } catch (ExecutionException e) {
            final var message = "VirtualMap@" + getRoute() + " failed to get hash during learner reconnect";
            throw new MerkleSynchronizationException(message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            final var message = "VirtualMap@" + getRoute() + " interrupted while ending learner reconnect";
            throw new MerkleSynchronizationException(message, e);
        }
        logger.info(RECONNECT.getMarker(), "endLearnerReconnect() complete");
    }

    /**
     * Loads the leaf record.
     * Lower level caches (VirtualDataSource, the OS file cache) should make subsequent value retrievals faster.
     * Warming keys can be done in parallel.
     * @param key key to the leaf node
     */
    public void warm(final K key) {
        records.findLeafRecord(key, false);
    }

    ////////////////////////

    /**
     * Adds a new leaf with the given key and value. The precondition to calling this
     * method is that the key DOES NOT have a corresponding leaf already either in the
     * cached leaves or in the data source.
     *
     * @param key
     * 		A non-null key. Previously validated.
     * @param value
     * 		The value to add. May be null.
     */
    private void add(final K key, final V value) {
        throwIfImmutable();
        assert !isHashed() : "Cannot modify already hashed node";

        // We're going to imagine what happens to the leaf and the tree without
        // actually bringing into existence any nodes. Virtual Virtual!! SUPER LAZY FTW!!

        // We will compute the new leaf path below, and ultimately set it on the leaf.
        long leafPath;

        // Confirm that adding one more entry is not too much for this VirtualMap to hold.
        final long currentSize = size();
        final long maximumAllowedSize = virtualMapConfig.maximumVirtualMapSize();
        if (currentSize >= maximumAllowedSize) {
            throw new IllegalStateException("Virtual Map has no more space");
        }

        final long remainingCapacity = maximumAllowedSize - currentSize;
        if ((currentSize > maxSizeReachedTriggeringWarning)
                && (remainingCapacity <= virtualMapConfig.virtualMapWarningThreshold())
                && (remainingCapacity % virtualMapConfig.virtualMapWarningInterval() == 0)) {

            maxSizeReachedTriggeringWarning = currentSize;
            logger.warn(
                    VIRTUAL_MERKLE_STATS.getMarker(),
                    "Virtual Map only has room for {} additional entries",
                    remainingCapacity);
        }
        if (remainingCapacity == 1) {
            logger.warn(VIRTUAL_MERKLE_STATS.getMarker(), "Virtual Map is now full!");
        }

        // Find the lastLeafPath which will tell me the new path for this new item
        final long lastLeafPath = state.getLastLeafPath();
        if (lastLeafPath == INVALID_PATH) {
            // There are no leaves! So this one will just go left on the root
            leafPath = getLeftChildPath(ROOT_PATH);
            state.setLastLeafPath(leafPath);
            state.setFirstLeafPath(leafPath);
        } else if (isLeft(lastLeafPath)) {
            // The only time that lastLeafPath is a left node is if the parent is root.
            // In all other cases, it will be a right node. So we can just add this
            // to root.
            leafPath = getRightChildPath(ROOT_PATH);
            state.setLastLeafPath(leafPath);
        } else {
            // We have to make some modification to the tree because there is not
            // an open position on root. So we need to pick a node where a leaf currently exists
            // and then swap it out with a parent, move the leaf to the parent as the
            // "left", and then we can put the new leaf on the right. It turns out,
            // the slot is always the firstLeafPath. If the current firstLeafPath
            // is all the way on the far right of the graph, then the next firstLeafPath
            // will be the first leaf on the far left of the next rank. Otherwise,
            // it is just the sibling to the right.
            final long firstLeafPath = state.getFirstLeafPath();
            final long nextFirstLeafPath = isFarRight(firstLeafPath)
                    ? getPathForRankAndIndex((byte) (getRank(firstLeafPath) + 1), 0)
                    : getPathForRankAndIndex(getRank(firstLeafPath), getIndexInRank(firstLeafPath) + 1);

            // The firstLeafPath points to the old leaf that we want to replace.
            // Get the old leaf.
            final VirtualLeafRecord<K, V> oldLeaf = records.findLeafRecord(firstLeafPath, true);
            requireNonNull(oldLeaf);
            cache.clearLeafPath(firstLeafPath);
            oldLeaf.setPath(getLeftChildPath(firstLeafPath));
            cache.putLeaf(oldLeaf);

            // Create a new internal node that is in the position of the old leaf and attach it to the parent
            // on the left side. Put the new item on the right side of the new parent.
            leafPath = getRightChildPath(firstLeafPath);

            // Save the first and last leaf paths
            state.setLastLeafPath(leafPath);
            state.setFirstLeafPath(nextFirstLeafPath);
        }
        statistics.setSize(state.size());

        final VirtualLeafRecord<K, V> newLeaf = new VirtualLeafRecord<>(leafPath, key, value);
        cache.putLeaf(newLeaf);
    }

    @Override
    public long getFastCopyVersion() {
        return fastCopyVersion;
    }
}
