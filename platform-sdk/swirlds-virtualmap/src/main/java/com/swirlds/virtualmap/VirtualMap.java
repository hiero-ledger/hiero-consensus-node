// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static com.swirlds.virtualmap.internal.Path.FIRST_LEFT_PATH;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;
import static com.swirlds.virtualmap.internal.Path.getSiblingPath;
import static com.swirlds.virtualmap.internal.Path.isLeft;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.utility.Labeled;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.AbstractVirtualRoot;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualRoot;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.hash.FullLeafRehashHashListener;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.pipeline.VirtualPipeline;
import com.swirlds.virtualmap.internal.reconnect.ConcurrentBlockingIterator;
import com.swirlds.virtualmap.internal.reconnect.LearnerPullVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.LearnerPushVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.NodeTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashLeafFlusher;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import com.swirlds.virtualmap.internal.reconnect.ReconnectNodeRemover;
import com.swirlds.virtualmap.internal.reconnect.TeacherPullVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.TeacherPushVirtualTreeView;
import com.swirlds.virtualmap.internal.reconnect.TopToBottomTraversalOrder;
import com.swirlds.virtualmap.internal.reconnect.TwoPhasePessimisticTraversalOrder;
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
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.ValueReference;
import org.hiero.base.constructable.RuntimeConstructable;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A Merkle tree that virtualizes all of its children, such that the child nodes
 * may not exist in RAM until they are required. Significantly, <strong>downward traversal in
 * the tree WILL NOT always returns consistent results until after hashes have been computed.</strong>
 * During the hash phase, all affected internal nodes are discovered and updated and "realized" into
 * memory. From that point, downward traversal through the tree will produce consistent results.
 *
 * <hr>
 * <p><strong>Virtualization</strong></p>
 *
 * <p>
 * All node data is persisted in a {@link VirtualDataSource}. The typical implementation would store node
 * data on disk. While an in-memory implementation may exist for various reasons (testing, benchmarking,
 * performance optimizations for certain scenarios), the best way to reason about this class is to
 * assume that the data source implementation is based on storing data on a filesystem.
 * <p>
 * Initially, the root node and other nodes are on disk and not in memory. When a client of the API
 * uses any of the map-like APIs, a leaf is read into memory. To make this more efficient, the leaf's
 * data is loaded lazily. Accessing the value causes the value to be read and deserialized from disk,
 * but does not cause the hash to be read or deserialized from disk. Central to the implementation is
 * avoiding as much disk access and deserialization as possible.
 * <p>
 * Each time a leaf is accessed, either for modification or reading, we first check an in-memory cache
 * to see if this leaf has already been accessed in some way. If so, we get it from memory directly and
 * avoid hitting the disk. The cache is shared across all copies of the map, so we actually check memory
 * for any existing versions going back to the oldest version that is still in memory (typically, a dozen
 * or so). If we have a cache miss there, then we go to disk, read an object, and place it in the cache,
 * if it will be modified later or is being modified now. We do not cache into memory records that are
 * only read.
 * <p>
 * One important optimization is avoiding accessing internal nodes during transaction handling. If a leaf
 * is added, we will need to create a new internal node, but we do not need to "walk up the tree" making
 * copies of the existing nodes. When we delete a leaf, we need to delete an internal node, but we don't
 * need to do anything special in that case either (except to delete it from our in memory cache). Avoiding
 * this work is important for performance, but it does lead to inconsistencies when traversing the children
 * of this node using an iterator, or any of the getChild methods on the class. This is because the state
 * of those internal nodes is unknown until we put in the work to sort them out. We do this efficiently during
 * the hashing process. Once hashing is complete, breadth or depth first traversal of the tree will be
 * correct and consistent for that version of the tree. It isn't hashing itself that makes the difference,
 * it is the method by which iteration happens.
 *
 * <hr>
 * <p><strong>Lifecycle</strong></p>
 * <p>
 * A {@link VirtualMap} is created at startup and copies are made as rounds are processed. Each map becomes
 * immutable through its map-like API after it is copied. Internal nodes can still be hashed until the hashing
 * round completes. Eventually, a map must be retired, and all in-memory references to the internal and leaf
 * nodes released for garbage collection, and all the data written to disk. It is <strong>essential</strong>
 * that data is written to disk in order from oldest to newest copy. Although maps may be released in any order,
 * they <strong>MUST</strong> be written to disk strictly in-order and only the oldest copy in memory can be
 * written. There cannot be an older copy in memory with a newer copy being written to disk.
 *
 * <hr>
 * <p><strong>Map-like Behavior</strong></p>
 * <p>
 * This class presents a map-like interface for getting and putting values. These values are stored
 * in the leaf nodes of this node's sub-tree. The map-like methods {@link #get(Bytes, Codec)},
 * {@link #put(Bytes, Object, Codec)}, and {@link #remove(Bytes, Codec)} can be used as a
 * fast and convenient way to read, add, modify, or delete the corresponding leaf nodes and
 * internal nodes. Indeed, you <strong>MUST NOT</strong> modify the tree structure directly, only
 * through the map-like methods.
 */
public final class VirtualMap extends AbstractVirtualRoot implements Labeled, VirtualRoot {

    /**
     * The number of elements to have in the buffer used during rehashing on start.
     */
    private static final int MAX_REHASHING_BUFFER_SIZE = 10_000_000;

    private static final int MAX_PBJ_RECORD_SIZE = 33554432;

    /**
     * Hardcoded virtual map label
     */
    public static final String LABEL = "state";

    private static final String NO_NULL_KEYS_ALLOWED_MESSAGE = "Null keys are not allowed";

    /**
     * Used for serialization.
     */
    public static final long CLASS_ID = 0xb881f3704885e853L;

    /**
     * Use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(VirtualMap.class);

    /**
     * The number of elements to have in the buffer used during reconnect on a learner when passing
     * leaves to the hashing system. The size of this variable will depend on the incoming rate
     * of leaves vs. the speed of hashing.
     */
    private static final int MAX_RECONNECT_HASHING_BUFFER_SIZE = 10_000_000;

    /** Virtual Map platform configuration */
    @NonNull
    private final VirtualMapConfig virtualMapConfig;

    /**
     * This version number should be used to handle compatibility issues that may arise from any future changes
     */
    public static class ClassVersion {
        public static final int NO_VIRTUAL_ROOT_NODE = 4;
    }

    /**
     * @deprecated to be removed after 0.70 release
     */
    @Deprecated(forRemoval = true)
    public static final int MAX_LABEL_CHARS = 512;

    /** Platform configuration */
    @NonNull
    private final Configuration configuration;

    /**
     * A {@link VirtualDataSourceBuilder} used for creating instances of {@link VirtualDataSource}.
     * The data source used by this instance is created from this builder. The builder is needed
     * during reconnect to create a new data source based on a snapshot directory, or in
     * various other scenarios.
     */
    private VirtualDataSourceBuilder dataSourceBuilder;

    /**
     * Provides access to the {@link VirtualDataSource} for tree data.
     * All instances of {@link VirtualMap} in the "family" (i.e. that are copies
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
     * Since this is fast-copyable and shared across all copies of a {@link VirtualMap}, it contains the changed
     * leaves over history. Since we always flush from oldest to newest, we know for certain that
     * anything here is at least as new as, or newer than, what is on disk. So we check it first whenever
     * we need a leaf. This allows us to keep the disk simple and not fast-copyable.
     */
    private VirtualNodeCache cache;

    /**
     * A reference to the map metadata, such as the first leaf path, last leaf path, name ({@link VirtualMapMetadata}).
     * Ideally this would be final and never null, but serialization requires partially constructed objects,
     * so it must not be final and may be null until deserialization is complete.
     */
    private VirtualMapMetadata metadata;

    /**
     * An interface through which the {@link VirtualMap} can access record data from the cache and the
     * data source. By encapsulating this logic in a RecordAccessor, we make it convenient to access records
     * using a combination of different caches, states, and data sources, which becomes important for reconnect
     * and other uses. This should never be null except for a brief window during initialization / reconnect /
     * serialization.
     */
    private RecordAccessor records;

    /**
     * The hasher is responsible for hashing data in a virtual merkle tree.
     */
    private final VirtualHasher hasher;

    /**
     * The {@link VirtualPipeline}, shared across all copies of a given {@link VirtualMap}, maintains the
     * lifecycle of the nodes, making sure they are merged or flushed or hashed in order and according to the
     * defined lifecycle rules. This class makes calls to the pipeline, and the pipeline calls back methods
     * defined in this class.
     */
    private VirtualPipeline pipeline;

    /**
     * Hash of this root node. If null, the node isn't hashed yet.
     */
    private final AtomicReference<Hash> hash = new AtomicReference<>();
    /**
     * If true, then this copy of {@link VirtualMap} should eventually be flushed to disk. A heuristic is
     * used to determine which copy is flushed.
     */
    private final AtomicBoolean shouldBeFlushed = new AtomicBoolean(false);

    /**
     * Flush threshold. If greater than zero, then this virtual root will be flushed to disk, if
     * its estimated size exceeds the threshold. If this virtual root is explicitly requested to flush
     * using {@link #enableFlush()}, the threshold is not taken into consideration.
     *
     * <p>By default, the threshold is set to {@link VirtualMapConfig#copyFlushCandidateThreshold()}. The
     * threshold is inherited by all copies.
     */
    private final AtomicLong flushCandidateThreshold = new AtomicLong();

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

    /**
     * Created at the beginning of reconnect as a <strong>learner</strong>, this iterator allows
     * for other threads to feed its leaf records to be used during hashing.
     */
    private ConcurrentBlockingIterator<VirtualLeafBytes> reconnectIterator = null;

    /**
     * A {@link java.util.concurrent.Future} that will contain the final hash result of the
     * reconnect hashing process.
     */
    private CompletableFuture<Hash> reconnectHashingFuture;

    /**
     * Set to true once the reconnect hashing thread has been started.
     */
    private AtomicBoolean reconnectHashingStarted;

    /**
     * Empty VirtualMap state created using a label from the original map.
     * Paths are not initialized in this instance on purpose.
     */
    private VirtualMapMetadata reconnectState;
    /**
     * The {@link RecordAccessor} for the state, cache, and data source needed during reconnect.
     */
    private RecordAccessor reconnectRecords;

    /**
     * During reconnect as a learner, this is the root node in the old learner merkle tree.
     */
    private VirtualMap originalMap;

    private ReconnectHashLeafFlusher reconnectFlusher;

    private ReconnectNodeRemover nodeRemover;

    private final long fastCopyVersion;

    private VirtualMapStatistics statistics;

    /**
     * This reference is used to assert that there is only one thread modifying the VM at a time.
     * NOTE: This field is used *only* if assertions are enabled, otherwise it always has null value.
     */
    private final AtomicReference<Thread> currentModifyingThreadRef = new AtomicReference<>(null);

    /**
     * Required by the {@link RuntimeConstructable} contract.
     * This can <strong>only</strong> be called as part of serialization and reconnect, not for normal use.
     */
    public VirtualMap(final @NonNull Configuration configuration) {
        this.configuration = requireNonNull(configuration);

        this.fastCopyVersion = 0;
        // Hasher is required during reconnects
        this.hasher = new VirtualHasher();
        this.virtualMapConfig = requireNonNull(configuration.getConfigData(VirtualMapConfig.class));
        this.flushCandidateThreshold.set(virtualMapConfig.copyFlushCandidateThreshold());
    }

    /**
     * Create a new {@link VirtualMap}.
     *
     * @param dataSourceBuilder
     * 		The data source builder. Must not be null.
     * @param configuration platform configuration
     */
    public VirtualMap(
            final @NonNull VirtualDataSourceBuilder dataSourceBuilder, final @NonNull Configuration configuration) {
        this.configuration = requireNonNull(configuration);

        this.fastCopyVersion = 0;
        this.hasher = new VirtualHasher();
        this.virtualMapConfig = requireNonNull(configuration.getConfigData(VirtualMapConfig.class));
        this.flushCandidateThreshold.set(virtualMapConfig.copyFlushCandidateThreshold());
        this.dataSourceBuilder = requireNonNull(dataSourceBuilder);
        dataSource = dataSourceBuilder.build(LABEL, null, true, false);
        this.metadata = new VirtualMapMetadata();
        postInit();
    }

    /**
     * Create a virtual map from a snapshot
     * @param dataSourceBuilder the data source builder. Must not be null.
     * @param configuration platform configuration
     * @param snapshotPath path to the snapshot directory. Must not be null.
     */
    private VirtualMap(
            final @NonNull VirtualDataSourceBuilder dataSourceBuilder,
            final @NonNull Configuration configuration,
            final @NonNull Path snapshotPath) {
        requireNonNull(snapshotPath);

        this.fastCopyVersion = 0L;
        this.configuration = requireNonNull(configuration);
        this.hasher = new VirtualHasher();
        this.virtualMapConfig = requireNonNull(configuration.getConfigData(VirtualMapConfig.class));
        this.flushCandidateThreshold.set(virtualMapConfig.copyFlushCandidateThreshold());
        this.dataSourceBuilder = requireNonNull(dataSourceBuilder);
        this.dataSource = dataSourceBuilder.build(LABEL, snapshotPath, true, false);
        this.metadata = new VirtualMapMetadata(dataSource.getFirstLeafPath(), dataSource.getLastLeafPath());
        postInit();
    }

    /**
     * Create a copy of the given source.
     *
     * @param source
     * 		must not be null.
     */
    private VirtualMap(final VirtualMap source) {
        configuration = source.configuration;

        metadata = source.metadata.copy();
        fastCopyVersion = source.fastCopyVersion + 1;
        dataSourceBuilder = source.dataSourceBuilder;
        dataSource = source.dataSource;
        cache = source.cache.copy();
        hasher = source.hasher;
        reconnectHashingFuture = null;
        reconnectHashingStarted = null;
        reconnectIterator = null;
        reconnectRecords = null;
        pipeline = source.pipeline;
        flushCandidateThreshold.set(source.flushCandidateThreshold.get());
        statistics = source.statistics;
        virtualMapConfig = source.virtualMapConfig;

        if (this.pipeline.isTerminated()) {
            throw new IllegalStateException("A fast-copy was made of a VirtualMap with a terminated pipeline!");
        }

        postInit();
    }

    /**
     * Sets the {@link VirtualMapMetadata}. This method is called when this root node
     * is added as a child to its virtual map. It happens when virtual maps are created
     * from scratch, or during deserialization. It's also called after learner reconnects.
     *
     */
    void postInit() {
        requireNonNull(metadata);
        requireNonNull(dataSource);

        if (cache == null) {
            cache = new VirtualNodeCache(virtualMapConfig);
        }

        this.records = new RecordAccessor(this.metadata, cache, dataSource);
        if (statistics == null) {
            // Only create statistics instance if we don't yet have statistics. During a reconnect operation.
            // it is necessary to use the statistics object from the previous instance of the state.
            statistics = new VirtualMapStatistics(LABEL);
        }

        // VM size metric value is updated in add() and remove(). However, if no elements are added or
        // removed, the metric may have a stale value for a long time. Update it explicitly here
        statistics.setSize(size());
        // At this point in time the copy knows if it should be flushed or merged, and so it is safe
        // to register with the pipeline.
        if (pipeline == null) {
            pipeline = new VirtualPipeline(virtualMapConfig, LABEL);
        }
        pipeline.registerCopy(this);
    }

    /**
     * Performs a full rehash of all persisted leaves in the map if the leaf hash bytes
     * were calculated differently (e.g. due to a change in bytes to hash).
     * <p>
     * To detect a difference, this method loads the stored hash of the leaf at
     * {@code firstLeafPath}, recalculates the current hash for that leaf, and compares
     * the two values.
     * <p>
     * If the hashes differ, the method iterates over every leaf node directly from disk
     * and rehashes them.
     * <p>
     * The main difference from {@link #computeHash()} is that {@code computeHash()}
     * only updates hashes for dirty leaves that are already in the in-memory cache,
     * whereas this method always rehashes every leaf from persistent storage. Because
     * the number of leaves is very large, this method is deliberately designed to never
     * load all leaves into memory at once (unlike {@code computeHash()}, which can
     * safely ignore memory consumption since the cache is already resident).
     */
    public void fullLeafRehashIfNecessary() {
        requireNonNull(records, "Records must be initialized before rehashing");

        // getting a range that is relevant for the data source
        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();

        assert firstLeafPath == metadata.getFirstLeafPath();
        assert lastLeafPath == metadata.getLastLeafPath();

        final ConcurrentBlockingIterator<VirtualLeafBytes> rehashIterator =
                new ConcurrentBlockingIterator<>(MAX_REHASHING_BUFFER_SIZE);

        if (firstLeafPath < 0 || lastLeafPath < 0) {
            logger.info(STARTUP.getMarker(), "VirtualMap is empty, skipping full rehash.");
            return;
        }
        try {
            final Hash loadedHash = dataSource.loadHash(firstLeafPath);
            final VirtualLeafBytes virtualLeafBytes = dataSource.loadLeafRecord(firstLeafPath);
            if (virtualLeafBytes == null || loadedHash == null) {
                logger.error(
                        STARTUP.getMarker(),
                        "Loaded leaf bytes or hash for the first leaf path {} is null, skipping full rehash",
                        firstLeafPath);
                return;
            }
            final WritableMessageDigest wmd = new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest());
            virtualLeafBytes.writeToForHashing(wmd);
            final Hash recaclulatedHash = new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
            if (loadedHash.equals(recaclulatedHash)) {
                logger.info(
                        STARTUP.getMarker(),
                        "Recalculated hash for the first leaf path is equal to loaded hash, skipping full rehash");
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        logger.info(STARTUP.getMarker(), "Doing full rehash for the path range: {} - {}", firstLeafPath, lastLeafPath);
        final FullLeafRehashHashListener hashListener = new FullLeafRehashHashListener(
                firstLeafPath,
                lastLeafPath,
                dataSource,
                statistics,
                // even though this listener has nothing to do with the reconnect, reconnect flush interval value
                // is appropriate to use here.
                virtualMapConfig.reconnectFlushInterval());

        // This background thread will be responsible for hashing the tree and sending the
        // data to the hash listener to flush.
        final CompletableFuture<Hash> fullRehashFuture = CompletableFuture.supplyAsync(() -> hasher.hash(
                        records::findHash, rehashIterator, firstLeafPath, lastLeafPath, hashListener, virtualMapConfig))
                .exceptionally((exception) -> {
                    // Shut down the iterator.
                    rehashIterator.close();
                    final var message = "Full rehash failed";
                    logger.error(EXCEPTION.getMarker(), message, exception);
                    throw new MerkleSynchronizationException(message, exception);
                });

        final long onePercent = (lastLeafPath - firstLeafPath) / 100 + 1;
        final long start = System.currentTimeMillis();
        try {
            for (long i = firstLeafPath; i <= lastLeafPath; i++) {
                try {
                    final VirtualLeafBytes<?> leafBytes = dataSource.loadLeafRecord(i);
                    assert leafBytes != null : "Leaf record should not be null";
                    try {
                        rehashIterator.supply(leafBytes);
                    } catch (final MerkleSynchronizationException e) {
                        throw e;
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MerkleSynchronizationException(
                                "Interrupted while waiting to supply a new leaf to the hashing iterator buffer", e);
                    } catch (final Exception e) {
                        throw new MerkleSynchronizationException("Failed to handle a leaf during full rehashing", e);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (i % onePercent == 0) {
                    logger.info(STARTUP.getMarker(), "Full rehash progress: {}%", (i - firstLeafPath) / onePercent + 1);
                }
            }
        } finally {
            rehashIterator.close();
        }

        try {
            final long millisSpent = System.currentTimeMillis() - start;
            logger.info(STARTUP.getMarker(), "It took {} seconds to feed all leaves to the hasher", millisSpent / 1000);
            setHashPrivate(fullRehashFuture.get(virtualMapConfig.fullRehashTimeoutMs() - millisSpent, MILLISECONDS));
        } catch (ExecutionException e) {
            final var message = "Failed to get hash during full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            final var message = "Interrupted while full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (TimeoutException e) {
            final var message = "Wasn't able to finish full rehashing in time";
            throw new MerkleSynchronizationException(message, e);
        }
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    public VirtualNodeCache getCache() {
        return cache;
    }

    @SuppressWarnings("ClassEscapesDefinedScope")
    public RecordAccessor getRecords() {
        return records;
    }

    // Exposed for tests only.
    public VirtualPipeline getPipeline() {
        return pipeline;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRegisteredToPipeline(final VirtualPipeline pipeline) {
        return pipeline == this.pipeline;
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
                    "Destroying the virtual map, but its pipeline is null. It may happen during failed reconnect");
            closeDataSource();
        }
    }

    /**
     * Checks whether a leaf for the given key exists.
     *
     * @param key
     * 		The key. Cannot be null.
     * @return True if there is a leaf corresponding to this key.
     */
    public boolean containsKey(final Bytes key) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final long path = records.findPath(key);
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
    public <V> V get(@NonNull final Bytes key, final Codec<V> valueCodec) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final VirtualLeafBytes<V> rec = records.findLeafRecord(key);
        statistics.countReadEntities();
        return rec == null ? null : rec.value(valueCodec);
    }

    /**
     * Gets the value associated with the given key as raw bytes.
     *
     * @param key
     * 		The key. This must not be null.
     * @return The value bytes. The value may be null.
     */
    @Nullable
    @SuppressWarnings("rawtypes")
    public Bytes getBytes(@NonNull final Bytes key) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final VirtualLeafBytes rec = records.findLeafRecord(key);
        statistics.countReadEntities();
        return rec == null ? null : rec.valueBytes();
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
    public <V> void put(@NonNull final Bytes key, @Nullable final V value, @Nullable final Codec<V> valueCodec) {
        put(key, value, valueCodec, null);
    }

    /**
     * Puts the key/value pair represented as bytes into the map. The key must not be null, but the value
     * may be null. If the entry was already in the map, the value is replaced. If the mapping was not in the map, then a new entry is made.
     *
     * @param keyBytes
     * 		the key bytes, cannot be null.
     * @param valueBytes
     * 		the value bytes, may be null.
     */
    public void putBytes(@NonNull final Bytes keyBytes, @Nullable final Bytes valueBytes) {
        put(keyBytes, null, null, valueBytes);
    }

    private <V> void put(final Bytes key, final V value, final Codec<V> valueCodec, final Bytes valueBytes) {
        throwIfImmutable();
        assert !isHashed() : "Cannot modify already hashed node";
        assert currentModifyingThreadRef.compareAndSet(null, Thread.currentThread());
        try {
            requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
            final long path = records.findPath(key);
            if (path == INVALID_PATH) {
                // The key is not stored. So add a new entry and return.
                add(key, value, valueCodec, valueBytes);
                statistics.countAddedEntities();
                statistics.setSize(metadata.getSize());
                return;
            }

            // Check the leaf is in cache, so we can reuse its old path. If not, the leaf is in
            // the data source, and the path above can be used as the old path
            final VirtualLeafBytes<V> existing = cache.lookupLeafByPath(path);
            final VirtualLeafBytes<V> updated;
            if (existing != null) {
                updated = valueCodec != null
                        ? existing.withValue(value, valueCodec)
                        : existing.withValueBytes(valueBytes);
            } else {
                updated = valueCodec != null
                        ? new VirtualLeafBytes<>(path, path, key, value, valueCodec)
                        : new VirtualLeafBytes<>(path, path, key, valueBytes);
            }
            cache.putLeaf(updated);
            statistics.countUpdatedEntities();
        } finally {
            assert currentModifyingThreadRef.compareAndSet(Thread.currentThread(), null);
        }
    }

    /**
     * Removes the key/value pair denoted by the given key from the map. Has no effect
     * if the key didn't exist.
     *
     * @param key The key to remove, must not be null.
     * @param valueCodec Value codec to decode the removed value.
     * @return The removed value. May return null if there was no value to remove or if the value was null.
     */
    public <V> V remove(@NonNull final Bytes key, @NonNull final Codec<V> valueCodec) {
        requireNonNull(valueCodec);
        Bytes removedValueBytes = remove(key);
        try {
            return removedValueBytes == null
                    ? null
                    : valueCodec.parse(
                            removedValueBytes.toReadableSequentialData(),
                            false,
                            false,
                            DEFAULT_MAX_DEPTH,
                            MAX_PBJ_RECORD_SIZE);
        } catch (final ParseException e) {
            throw new RuntimeException("Failed to deserialize a value from bytes", e);
        }
    }

    /**
     * Removes the key/value pair denoted by the given key from the map. Has no effect
     * if the key didn't exist.
     * @param key The key to remove, must not be null
     * @return The removed value represented as {@link Bytes}. May return null if there was no value to remove or if the value was null.
     */
    public Bytes remove(@NonNull final Bytes key) {
        throwIfImmutable();
        requireNonNull(key);
        assert currentModifyingThreadRef.compareAndSet(null, Thread.currentThread());
        try {
            // Verify whether the current leaf exists. If not, we can just return null.
            VirtualLeafBytes<?> leafToDelete = records.findLeafRecord(key);
            if (leafToDelete == null) {
                return null;
            }

            // Mark the leaf as being deleted.
            cache.deleteLeaf(leafToDelete);
            statistics.countRemovedEntities();

            // We're going to need these
            final long lastLeafPath = metadata.getLastLeafPath();
            final long firstLeafPath = metadata.getFirstLeafPath();
            final long leafToDeletePath = leafToDelete.path();

            // If the leaf was not the last leaf, then move the last leaf to take this spot
            if (leafToDeletePath != lastLeafPath) {
                final VirtualLeafBytes<?> lastLeaf = records.findLeafRecord(lastLeafPath);
                assert lastLeaf != null;
                cache.clearLeafPath(lastLeafPath);
                cache.putLeaf(lastLeaf.withPath(leafToDeletePath));
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
                    metadata.setFirstLeafPath(INVALID_PATH);
                    metadata.setLastLeafPath(INVALID_PATH);
                } else {
                    // We removed the second to last leaf, so the first & last leaf paths are now the same.
                    metadata.setLastLeafPath(FIRST_LEFT_PATH);
                    // One of the two remaining leaves is removed. When this virtual root copy is hashed,
                    // the root hash will be a product of the remaining leaf hash and a null hash at
                    // path 2. However, rehashing is only triggered, if there is at least one dirty leaf,
                    // while leaf 1 is not marked as such: neither its contents nor its path are changed.
                    // To fix it, mark it as dirty explicitly
                    final VirtualLeafBytes<?> leaf = records.findLeafRecord(1);
                    cache.putLeaf(leaf);
                }
            } else {
                final long lastLeafSibling = getSiblingPath(lastLeafPath);
                final VirtualLeafBytes<?> sibling = records.findLeafRecord(lastLeafSibling);
                assert sibling != null;
                cache.clearLeafPath(lastLeafSibling);
                cache.putLeaf(sibling.withPath(lastLeafParent));

                // Update the first & last leaf paths
                metadata.setFirstLeafPath(lastLeafParent); // replaced by the sibling, it is now first
                metadata.setLastLeafPath(lastLeafSibling - 1); // One left of the last leaf sibling
            }
            if (statistics != null) {
                statistics.setSize(metadata.getSize());
            }

            // Get the value and return it, if requested
            return leafToDelete.valueBytes();
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
        if (!isDestroyed()) {
            throw new IllegalStateException("merge is legal only after this node is destroyed");
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
     * Sets a flush threshold for this virtual root. When a copy of this virtual root is created,
     * it inherits the threshold value.
     *
     * <p>If this virtual root is explicitly marked to flush using {@link #enableFlush()}, changing
     * the flush threshold doesn't have any effect.
     *
     * @param value The flush threshold, in bytes
     */
    public void setFlushCandidateThreshold(long value) {
        flushCandidateThreshold.set(value);
    }

    /**
     * Gets the flush threshold for this virtual root.
     *
     * @return The flush threshold, in bytes
     */
    long getFlushCandidateThreshold() {
        return flushCandidateThreshold.get();
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
        final long threshold = flushCandidateThreshold.get();
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
        flush(cache, metadata, dataSource);
        cache.release();
        final long end = System.currentTimeMillis();
        flushed.set(true);
        flushLatch.countDown();
        statistics.recordFlush(end - start);
        logger.debug(
                VIRTUAL_MERKLE_STATS.getMarker(),
                "Flushed {} v{} in {} ms",
                LABEL,
                cache.getFastCopyVersion(),
                end - start);
    }

    private void flush(VirtualNodeCache cacheToFlush, VirtualMapMetadata stateToUse, VirtualDataSource ds) {
        try {
            // Get the leaves that were changed and sort them by path so that lower paths come first
            final Stream<VirtualLeafBytes> dirtyLeaves =
                    cacheToFlush.dirtyLeavesForFlush(stateToUse.getFirstLeafPath(), stateToUse.getLastLeafPath());
            // Get the deleted leaves
            final Stream<VirtualLeafBytes> deletedLeaves = cacheToFlush.deletedLeaves();
            // Save the dirty hashes
            final Stream<VirtualHashRecord> dirtyHashes =
                    cacheToFlush.dirtyHashesForFlush(stateToUse.getLastLeafPath());
            ds.saveRecords(
                    stateToUse.getFirstLeafPath(),
                    stateToUse.getLastLeafPath(),
                    dirtyHashes,
                    dirtyLeaves,
                    deletedLeaves,
                    false);
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
        return cache.getEstimatedSize();
    }

    /**
     * Gets the {@link VirtualDataSource} used with this map.
     *
     * @return A non-null reference to the data source.
     */
    public VirtualDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Gets the current state.
     *
     * @return The current state
     */
    public VirtualMapMetadata getMetadata() {
        return metadata;
    }

    /*
     * Implementation of MerkleInternal and associated APIs
     **/

    /**
     * {@inheritDoc}
     * @deprecated this method can be safely removed once we switch to pull-based reconnect,
     * see <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/12648">issue #12648</a>
     */
    @Deprecated(forRemoval = true)
    @Override
    public long getClassId() {
        // This class id is still required for the reconnect code
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.NO_VIRTUAL_ROOT_NODE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        return LABEL;
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
    @NonNull
    @Override
    public Hash getHash() {
        if (hash.get() == null) {
            pipeline.hashCopy(this);
        }
        return hash.get();
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
    public void setHash(final Hash hash) {
        throw new UnsupportedOperationException("data type is self hashing");
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
        final VirtualHashListener hashListener = new VirtualHashListener() {
            @Override
            public void onNodeHashed(final long path, final Hash hash) {
                cache.putHash(path, hash);
            }
        };
        Hash virtualHash = hasher.hash(
                records::findHash,
                cache.dirtyLeavesForHash(metadata.getFirstLeafPath(), metadata.getLastLeafPath())
                        .iterator(),
                metadata.getFirstLeafPath(),
                metadata.getLastLeafPath(),
                hashListener,
                virtualMapConfig);

        if (virtualHash == null) {
            final Hash rootHash = (metadata.getSize() == 0) ? null : records.findHash(0);
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
     *
     * <p>This method must be called when the lifecycle thread for this virtual map is paused,
     * or data source flushes are disabled some other way.
     */
    @Override
    public RecordAccessor detach() {
        final Path snapshotPath = dataSourceSnapshot();
        final VirtualDataSource dataSourceCopy = dataSourceBuilder.build(getLabel(), snapshotPath, false, false);
        final VirtualNodeCache cacheSnapshot = cache.snapshot();
        return new RecordAccessor(metadata.copy(), cacheSnapshot, dataSourceCopy);
    }

    /**
     * Creates a snapshot of this map's data source. This method must be called only when
     * the lifecycle thread is paused to make sure no data is flushed to the data source
     * while the copy is prepared. The base path to the snapshot is returned.
     */
    private Path dataSourceSnapshot() {
        if (isDestroyed()) {
            throw new IllegalStateException("Can't make data source copy: virtual map copy is already destroyed");
        }
        if (!isImmutable()) {
            throw new IllegalStateException("Can't make data source copy: virtual map copy is mutable");
        }
        if (!isHashed()) {
            throw new IllegalStateException("Can't make data source copy: virtual map copy isn't hashed");
        }

        return dataSourceBuilder.snapshot(null, dataSource);
    }

    /*
     * Reconnect Implementation
     **/

    /**
     * Creates a virtual view for this map to use by reconnect teacher. The view is used
     * to access all nodes and hashes in the virtual tree. The view must not share any
     * data with this map, so if any changes are made to the map, they aren't reflected
     * in the view.
     *
     * <p>The view will be closed by reconnect teacher, when reconnect is complete or failed.
     */
    public TeacherTreeView buildTeacherView(@NonNull final ReconnectConfig reconnectConfig) {
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH ->
                new TeacherPushVirtualTreeView(getStaticThreadManager(), reconnectConfig, this, metadata, pipeline);
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM ->
                new TeacherPullVirtualTreeView(getStaticThreadManager(), reconnectConfig, this, metadata, pipeline);
            case VirtualMapReconnectMode.PULL_TWO_PHASE_PESSIMISTIC ->
                new TeacherPullVirtualTreeView(getStaticThreadManager(), reconnectConfig, this, metadata, pipeline);
            default ->
                throw new UnsupportedOperationException("Unknown reconnect mode: " + virtualMapConfig.reconnectMode());
        };
    }

    /**
     * Initialize a new reconnect root created using {@link #newReconnectRoot()} with data
     * from the specified virtual map.
     */
    private void setupWithOriginalNode(@NonNull final VirtualMap originalMap) {
        // NOTE: If we're reconnecting, then the old tree is toast. We hold onto the originalMap to
        // restart from that position again in the future if needed, but we're never going to use
        // the old map again. We need the data source builder from the old map so, we can create
        // new data sources in this new map with all the right settings.
        this.originalMap = originalMap;
        this.dataSourceBuilder = originalMap.dataSourceBuilder;

        // shutdown background compaction on original data source as it is no longer needed to be running as all data
        // in that data source is only there as a starting point for reconnect now. So compacting it further is not
        // helpful and will just burn resources.
        originalMap.dataSource.stopAndDisableBackgroundCompaction();

        reconnectState = new VirtualMapMetadata();
        reconnectRecords = originalMap.pipeline.pausePipelineAndRun("copy", () -> {
            // shutdown background compaction on original data source as it is no longer needed to be running as all
            // data
            // in that data source is only there as a starting point for reconnect now. So compacting it further is not
            // helpful and will just burn resources.
            originalMap.dataSource.stopAndDisableBackgroundCompaction();

            // Take a snapshot, and use the snapshot database as my data source
            final Path snapshotPath = dataSourceBuilder.snapshot(null, originalMap.dataSource);
            this.dataSource = dataSourceBuilder.build(originalMap.getLabel(), snapshotPath, true, false);

            // The old map's cache is going to become immutable, but that's OK, because the old map
            // will NEVER be updated again.
            assert originalMap.isHashed() : "The system should have made sure this was hashed by this point!";
            final VirtualNodeCache snapshotCache = originalMap.cache.snapshot();
            flush(snapshotCache, originalMap.metadata, this.dataSource);

            return new RecordAccessor(reconnectState, snapshotCache, dataSource);
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
     * Creates a new virtual map to be used by reconnect learner. The new map will contain
     * the same data as this map, all changes to the new map will not be reflected in
     * this map.
     */
    public VirtualMap newReconnectRoot() {
        final VirtualMap newRoot = new VirtualMap(configuration);
        // Ensure the original map is hashed here. Once hashed, all its internal nodes are also hashed,
        // which is required for the reconnect process. A teacher needs these hashes to determine
        // whether to send the underlying nodes or not.
        getHash();
        newRoot.setupWithOriginalNode(this);
        return newRoot;
    }

    /**
     * Creates a virtual tree view for a new reconnect root created using {@link #newReconnectRoot()}.
     * The view will be used to access all nodes and hashes in the virtual tree by reconnect
     * learner.
     *
     * <p>The view will be closed by reconnect learner, when reconnect is complete or failed.
     */
    public LearnerTreeView buildLearnerView(
            @NonNull final ReconnectConfig reconnectConfig, @NonNull final ReconnectMapStats mapStats) {
        assert originalMap != null;
        // During reconnect we want to look up state from the original records
        final VirtualMapMetadata originalState = originalMap.getMetadata();
        reconnectFlusher =
                new ReconnectHashLeafFlusher(dataSource, virtualMapConfig.reconnectFlushInterval(), statistics);
        nodeRemover = new ReconnectNodeRemover(
                originalMap.getRecords(),
                originalState.getFirstLeafPath(),
                originalState.getLastLeafPath(),
                reconnectFlusher);
        return switch (virtualMapConfig.reconnectMode()) {
            case VirtualMapReconnectMode.PUSH ->
                new LearnerPushVirtualTreeView(
                        reconnectConfig,
                        this,
                        originalMap.records,
                        originalState,
                        reconnectState,
                        nodeRemover,
                        mapStats);
            case VirtualMapReconnectMode.PULL_TOP_TO_BOTTOM -> {
                final NodeTraversalOrder topToBottom = new TopToBottomTraversalOrder();
                yield new LearnerPullVirtualTreeView(
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
                yield new LearnerPullVirtualTreeView(
                        reconnectConfig,
                        this,
                        originalMap.records,
                        originalState,
                        reconnectState,
                        nodeRemover,
                        twoPhasePessimistic,
                        mapStats);
            }
            default ->
                throw new UnsupportedOperationException("Unknown reconnect mode: " + virtualMapConfig.reconnectMode());
        };
    }

    /**
     * Pass all statistics to the registry.
     *
     * @param metrics
     * 		reference to the metrics system
     */
    public void registerMetrics(@NonNull final Metrics metrics) {
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
    public void handleReconnectLeaf(@NonNull final VirtualLeafBytes<?> leafRecord) {
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
        final ReconnectHashListener hashListener = new ReconnectHashListener(reconnectFlusher);

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
                    final var message = "VirtualMap failed to hash during reconnect";
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
            logger.info(RECONNECT.getMarker(), "call postInit()");
            nodeRemover = null;
            originalMap = null;
            metadata = new VirtualMapMetadata(reconnectState.getSize());
            postInit();
        } catch (ExecutionException e) {
            throw new MerkleSynchronizationException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException(e);
        }
        logger.info(RECONNECT.getMarker(), "endLearnerReconnect() complete");
    }

    /**
     * To speed up transaction processing for a given round, we can use OS page cache's help
     * Just by loading leaf record and internal records from disk
     * <ol>
     *   <li> It will be read from disk</li>
     *   <li> The OS will cache it in its page cache</li>
     * </ol>
     * The idea is that during SwirldState.handleTransactionRound(..) or during preHandle(..)
     * we know what leaf records and internal records are going to be accessed and hence preloading/warming
     * them in os cache before transaction processing should significantly speed up transaction processing.
     *
     *  @param key The key of the leaf to warm, must not be null
     */
    public void warm(@NonNull final Bytes key) {
        records.findLeafRecord(key);
    }

    // ----------------------

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
    private <V> void add(
            @NonNull final Bytes key,
            @Nullable final V value,
            @Nullable final Codec<V> valueCodec,
            @Nullable final Bytes valueBytes) {
        throwIfImmutable();
        assert !isHashed() : "Cannot modify already hashed node";

        // We're going to imagine what happens to the leaf and the tree without
        // actually bringing into existence any nodes. Virtual Virtual!! SUPER LAZY FTW!!

        // We will compute the new leaf path below, and ultimately set it on the leaf.
        long leafPath;

        // Find the lastLeafPath which will tell me the new path for this new item
        final long lastLeafPath = metadata.getLastLeafPath();
        if (lastLeafPath == INVALID_PATH) {
            // There are no leaves! So this one will just go left on the root
            leafPath = getLeftChildPath(ROOT_PATH);
            metadata.setLastLeafPath(leafPath);
            metadata.setFirstLeafPath(leafPath);
        } else if (isLeft(lastLeafPath)) {
            // The only time that lastLeafPath is a left node is if the parent is root.
            // In all other cases, it will be a right node. So we can just add this
            // to root.
            leafPath = getRightChildPath(ROOT_PATH);
            metadata.setLastLeafPath(leafPath);
        } else {
            // We have to make some modification to the tree because there is not
            // an open position on root. So we need to pick a node where a leaf currently exists
            // and then swap it out with a parent, move the leaf to the parent as the
            // "left", and then we can put the new leaf on the right. It turns out,
            // the slot is always the firstLeafPath
            final long firstLeafPath = metadata.getFirstLeafPath();
            final long nextFirstLeafPath = firstLeafPath + 1;

            // The firstLeafPath points to the old leaf that we want to replace.
            // Get the old leaf.
            final VirtualLeafBytes<?> oldLeaf = records.findLeafRecord(firstLeafPath);
            requireNonNull(oldLeaf);
            cache.clearLeafPath(firstLeafPath);
            cache.putLeaf(oldLeaf.withPath(getLeftChildPath(firstLeafPath)));

            // Create a new internal node that is in the position of the old leaf and attach it to the parent
            // on the left side. Put the new item on the right side of the new parent.
            leafPath = getRightChildPath(firstLeafPath);

            // Save the first and last leaf paths
            metadata.setLastLeafPath(leafPath);
            metadata.setFirstLeafPath(nextFirstLeafPath);
        }
        statistics.setSize(metadata.getSize());

        // FUTURE WORK: make VirtualLeafBytes.<init>(path, key, value, codec, bytes) public?
        final VirtualLeafBytes<V> newLeaf = valueCodec != null
                ? new VirtualLeafBytes<>(leafPath, key, value, valueCodec)
                : new VirtualLeafBytes<>(leafPath, key, valueBytes);
        cache.putLeaf(newLeaf);
    }

    @Override
    public long getFastCopyVersion() {
        return fastCopyVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualMap copy() {
        throwIfImmutable();
        throwIfDestroyed();

        final VirtualMap copy = new VirtualMap(this);
        setImmutable(true);

        if (isHashed()) {
            // Special case: after a "reconnect", the mutable copy will already be hashed
            // at this point in time.
            cache.seal();
        }

        return copy;
    }

    /**
     * Creates a snapshot of the current virtual map
     * @param outputDirectory target snapshot directory
     * @throws IOException for IO errors
     */
    public void createSnapshot(@NonNull final Path outputDirectory) throws IOException {
        final ValueReference<VirtualNodeCache> cacheSnapshot = new ValueReference<>();
        final Path snapshotPath = pipeline.pausePipelineAndRun("detach", () -> {
            // Lifecycle thread is paused, no cache flushes/merges, it's safe to take cache snapshot
            cacheSnapshot.setValue(cache.snapshot());
            // And make a data source snapshot. The snapshot is not loaded here, though, it is
            // done below
            return dataSourceSnapshot();
        });

        // build(), flush() and snapshot() below are called outside pausePipelineAndRun() to
        // unpause the lifecycle thread as quickly as possible. If the lifecycle thread is paused
        // for too long, unhandled copies pile up in the virtual pipeline, which triggers size
        // backpressure mechanism
        VirtualDataSource dataSourceCopy = null;
        try {
            // Restore a data source into memory from the snapshot. It will use its own directory
            // to store data files
            dataSourceCopy = dataSourceBuilder.build(LABEL, snapshotPath, false, true);
            // Then flush the cache snapshot to the data source copy
            flush(cacheSnapshot.getValue(), metadata, dataSourceCopy);
            // And finally snapshot the copy to the target dir
            dataSourceBuilder.snapshot(outputDirectory, dataSourceCopy);
        } finally {
            // Delete the snapshot directory
            FileUtils.deleteDirectory(snapshotPath);
            // And delete the data source copy directory
            if (dataSourceCopy != null) {
                dataSourceCopy.close();
            }
        }
    }

    /**
     * Creates a new virtual map from a snapshot
     * @param snapshotPath path to the snapshot directory
     * @param configuration virtual map configuration
     * @param dataSourceBuilderSupplier data source builder supplier
     * @return new virtual map instance
     */
    public static VirtualMap loadFromDirectory(
            @NonNull final Path snapshotPath,
            @NonNull final Configuration configuration,
            @NonNull Supplier<VirtualDataSourceBuilder> dataSourceBuilderSupplier) {
        VirtualMap virtualMap = new VirtualMap(dataSourceBuilderSupplier.get(), configuration, snapshotPath);
        virtualMap.fullLeafRehashIfNecessary();
        return virtualMap;
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
        return metadata.getSize();
    }

    /*
     * Gets whether this map is empty.
     *
     * @return True if the map is empty
     */
    public boolean isEmpty() {
        return size() == 0;
    }
}
