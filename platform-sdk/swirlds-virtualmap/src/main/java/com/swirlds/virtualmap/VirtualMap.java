// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static com.swirlds.virtualmap.VirtualMap.CLASS_ID;
import static com.swirlds.virtualmap.internal.Path.FIRST_LEFT_PATH;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;
import static com.swirlds.virtualmap.internal.Path.getSiblingPath;
import static com.swirlds.virtualmap.internal.Path.isLeft;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hiero.base.utility.CommonUtils.getNormalisedStringBytes;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.IllegalChildIndexException;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.CustomReconnectRoot;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.merkle.utility.DebugIterationEndpoint;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.utility.Labeled;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapReconnectMode;
import com.swirlds.virtualmap.constructable.constructors.VirtualMapConstructor;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.ExternalVirtualMapMetadata;
import com.swirlds.virtualmap.internal.merkle.VirtualInternalNode;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.internal.pipeline.VirtualPipeline;
import com.swirlds.virtualmap.internal.pipeline.VirtualRoot;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableClass;
import org.hiero.base.constructable.RuntimeConstructable;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A {@link MerkleInternal} node that virtualizes all of its children, such that the child nodes
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
@DebugIterationEndpoint
@ConstructableClass(value = CLASS_ID, constructorType = VirtualMapConstructor.class)
public final class VirtualMap extends PartialBinaryMerkleInternal
        implements CustomReconnectRoot<Long, Long>, ExternalSelfSerializable, Labeled, MerkleInternal, VirtualRoot {

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
        public static final int REHASH_LEAVES = 3;
        public static final int NO_VIRTUAL_ROOT_NODE = 4;
    }

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

    private final AtomicBoolean detached = new AtomicBoolean(false);

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
        requireNonNull(configuration);
        this.configuration = configuration;

        this.fastCopyVersion = 0;
        // Hasher is required during reconnects
        this.hasher = new VirtualHasher();
        this.virtualMapConfig = requireNonNull(configuration.getConfigData(VirtualMapConfig.class));
        this.flushCandidateThreshold.set(virtualMapConfig.copyFlushCandidateThreshold());
    }

    /**
     * Create a new {@link VirtualMap}.
     *
     * @param label
     * 		A label to give the virtual map. This label is used by the data source and cannot be null.
     * @param dataSourceBuilder
     * 		The data source builder. Must not be null.
     * @param configuration platform configuration
     */
    public VirtualMap(
            final String label,
            final VirtualDataSourceBuilder dataSourceBuilder,
            final @NonNull Configuration configuration) {
        requireNonNull(configuration);
        this.configuration = configuration;

        if (label.length() > MAX_LABEL_CHARS) {
            throw new IllegalArgumentException("Label cannot be greater than 512 characters");
        }

        this.fastCopyVersion = 0;
        this.hasher = new VirtualHasher();
        this.virtualMapConfig = requireNonNull(configuration.getConfigData(VirtualMapConfig.class));
        this.flushCandidateThreshold.set(virtualMapConfig.copyFlushCandidateThreshold());
        this.dataSourceBuilder = requireNonNull(dataSourceBuilder);
        this.metadata = new VirtualMapMetadata(label);
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
        requireNonNull(metadata.getLabel());
        requireNonNull(dataSourceBuilder);

        if (cache == null) {
            cache = new VirtualNodeCache(virtualMapConfig);
        }
        if (dataSource == null) {
            dataSource = dataSourceBuilder.build(metadata.getLabel(), true);
        }

        updateShouldBeFlushed();

        this.records = new RecordAccessor(this.metadata, cache, dataSource);
        if (statistics == null) {
            // Only create statistics instance if we don't yet have statistics. During a reconnect operation.
            // it is necessary to use the statistics object from the previous instance of the state.
            statistics = new VirtualMapStatistics(metadata.getLabel());
        }

        // VM size metric value is updated in add() and remove(). However, if no elements are added or
        // removed, the metric may have a stale value for a long time. Update it explicitly here
        statistics.setSize(size());
        // At this point in time the copy knows if it should be flushed or merged, and so it is safe
        // to register with the pipeline.
        if (pipeline == null) {
            pipeline = new VirtualPipeline(virtualMapConfig, metadata.getLabel());
        }
        pipeline.registerCopy(this);
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
    public <T extends MerkleNode> T getChild(final int index) {
        if (isDestroyed()
                || dataSource == null
                || originalMap != null
                || metadata == null
                || metadata.getFirstLeafPath() == INVALID_PATH
                || index > 1) {
            return null;
        }

        final long path = index + 1L;
        final T node;
        if (path < metadata.getFirstLeafPath()) {
            //noinspection unchecked
            node = (T) VirtualInternalNode.getInternalNode(this, path);
        } else if (path <= metadata.getLastLeafPath()) {
            //noinspection unchecked
            node = (T) VirtualInternalNode.getLeafNode(this, path);
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
     * The current virtual map implementation does not support children.
     * Even though it is a {@link MerkleInternal} node, the data stored differently, in the child leaf nodes.
     */
    @Override
    public int getNumberOfChildren() {
        // FUTURE WORK: This should return 0 once the VirtualMap is migrated
        return 2;
    }

    //  FUTURE WORK: Uncomment this once migration from the existing VirtualMap is done
    //  See https://github.com/hiero-ledger/hiero-consensus-node/issues/19690
    //    /**
    //     * This is never called for a {@link VirtualMap}.
    //     *
    //     * {@inheritDoc}
    //     */
    //    @Override
    //    protected void setChildInternal(final int index, final MerkleNode child) {
    //        throw new UnsupportedOperationException("You cannot set the child of a VirtualMap directly with this
    // API");
    //    }

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
        // FUTURE WORK: This should throw an UnsupportedOperationException once the VirtualMap is migrated
        if (index < 0 || index > 1) {
            throw new IllegalChildIndexException(0, 1, index);
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
    public <V> V get(@NonNull final Bytes key, final Codec<V> valueCodec) {
        requireNonNull(key, NO_NULL_KEYS_ALLOWED_MESSAGE);
        final VirtualLeafBytes<V> rec = records.findLeafRecord(key);
        statistics.countReadEntities();
        return rec == null ? null : rec.value(valueCodec);
    }

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
            final long path = records.findKey(key);
            if (path == INVALID_PATH) {
                // The key is not stored. So add a new entry and return.
                add(key, value, valueCodec, valueBytes);
                statistics.countAddedEntities();
                statistics.setSize(metadata.getSize());
                return;
            }

            // FUTURE WORK: make VirtualLeafBytes.<init>(path, key, value, codec, bytes) public?
            final VirtualLeafBytes<V> leaf = valueCodec != null
                    ? new VirtualLeafBytes<>(path, key, value, valueCodec)
                    : new VirtualLeafBytes<>(path, key, valueBytes);
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
     * @param key The key to remove, must not be null.
     * @param valueCodec Value codec to decode the removed value.
     * @return The removed value. May return null if there was no value to remove or if the value was null.
     */
    public <V> V remove(@NonNull final Bytes key, @NonNull final Codec<V> valueCodec) {
        requireNonNull(valueCodec);
        Bytes removedValueBytes = remove(key);
        try {
            return removedValueBytes == null ? null : valueCodec.parse(removedValueBytes);
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
    public void setFlushCandidateThreshold(long value) {
        flushCandidateThreshold.set(value);
        updateShouldBeFlushed();
    }

    /**
     * Gets flush threshold for this virtual root.
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
     * If flush threshold isn't set for this virtual root, marks the root to flush based on
     * {@link VirtualMapConfig#flushInterval()} setting.
     */
    private void updateShouldBeFlushed() {
        if (flushCandidateThreshold.get() <= 0) {
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
        flush(cache, metadata, dataSource);
        cache.release();
        final long end = System.currentTimeMillis();
        flushed.set(true);
        flushLatch.countDown();
        statistics.recordFlush(end - start);
        logger.debug(
                VIRTUAL_MERKLE_STATS.getMarker(),
                "Flushed {} v{} in {} ms",
                metadata.getLabel(),
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
        return ClassVersion.NO_VIRTUAL_ROOT_NODE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        return metadata == null ? null : metadata.getLabel();
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
     */
    @Override
    public RecordAccessor detach() {
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
        final VirtualNodeCache cacheSnapshot = cache.snapshot();
        return new RecordAccessor(metadata.copy(), cacheSnapshot, dataSourceCopy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void snapshot(@NonNull final Path destination) throws IOException {
        requireNonNull(destination);
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
            final VirtualNodeCache cacheSnapshot = cache.snapshot();
            flush(cacheSnapshot, metadata, dataSourceCopy);
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
    public TeacherTreeView<Long> buildTeacherView(@NonNull final ReconnectConfig reconnectConfig) {
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
     * {@inheritDoc}
     */
    @Override
    public void setupWithOriginalNode(@NonNull final MerkleNode originalNode) {
        assert originalNode instanceof VirtualMap : "The original node was not a VirtualMap!";

        // NOTE: If we're reconnecting, then the old tree is toast. We hold onto the originalMap to
        // restart from that position again in the future if needed, but we're never going to use
        // the old map again. We need the data source builder from the old map so, we can create
        // new data sources in this new map with all the right settings.
        originalMap = (VirtualMap) originalNode;
        this.dataSourceBuilder = originalMap.dataSourceBuilder;

        // shutdown background compaction on original data source as it is no longer needed to be running as all data
        // in that data source is only there as a starting point for reconnect now. So compacting it further is not
        // helpful and will just burn resources.
        originalMap.dataSource.stopAndDisableBackgroundCompaction();

        reconnectState = new VirtualMapMetadata(originalMap.metadata.getLabel());
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
    public CustomReconnectRoot<Long, Long> createNewRoot() {
        final VirtualMap newRoot = new VirtualMap(configuration);
        newRoot.setupWithOriginalNode(this);
        return newRoot;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LearnerTreeView<Long> buildLearnerView(
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
            logger.info(RECONNECT.getMarker(), "call postInit()");
            nodeRemover = null;
            originalMap = null;
            metadata = new VirtualMapMetadata(reconnectState.getLabel(), reconnectState.getSize());
            postInit();
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

    @Override
    public MerkleNode migrate(@NonNull final Configuration configuration, int version) {
        if (version < ClassVersion.NO_VIRTUAL_ROOT_NODE) {
            // removing VirtualMapMetadata
            super.setChild(0, null);
            // removing VirtualRootNode
            super.setChild(1, null);
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out, @NonNull final Path outputDirectory)
            throws IOException {

        // Create and write to state the name of the file we will expect later on deserialization
        final String outputFileName = metadata.getLabel() + ".vmap";
        final byte[] outputFileNameBytes = getNormalisedStringBytes(outputFileName);
        out.writeInt(outputFileNameBytes.length);
        out.writeNormalisedString(outputFileName);

        // Write the virtual map and sub nodes
        final Path outputFile = outputDirectory.resolve(outputFileName);
        try (SerializableDataOutputStream serout =
                new SerializableDataOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile.toFile())))) {
            // FUTURE WORK: get rid of the label once we migrate to Virtual Mega Map
            serout.writeNormalisedString(metadata.getLabel());
            serout.writeLong(metadata.getSize());
            pipeline.pausePipelineAndRun("detach", () -> {
                snapshot(outputDirectory);
                return null;
            });
            serout.writeSerializable(dataSourceBuilder, true);
            serout.writeLong(cache.getFastCopyVersion());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(
            @NonNull final SerializableDataInputStream in, @NonNull final Path inputDirectory, final int version)
            throws IOException {

        if (version < ClassVersion.REHASH_LEAVES) {
            throw new UnsupportedOperationException("Version must be at least ClassVersion.REHASH_LEAVES");
        }

        boolean vmStateExternal = version < ClassVersion.NO_VIRTUAL_ROOT_NODE;
        final int fileNameLengthInBytes = in.readInt();
        final String inputFileName = in.readNormalisedString(fileNameLengthInBytes);
        final Path inputFile = inputDirectory.resolve(inputFileName);
        loadFromFile(inputFile, vmStateExternal);
    }

    /**
     * Deserializes the given serialized VirtualMap file into this map instance. This is not intended for
     * public use, it is for testing and tools only.
     *
     * @param inputFile              The input .vmap file. Cannot be null.
     * @param vmStateExternal        true for versions prior to version 4, the state is not a leaf for the VirtualMap
     * @throws IOException For problems.
     */
    public void loadFromFile(@NonNull final Path inputFile, boolean vmStateExternal) throws IOException {
        deserializeAndDebugOnFailure(
                () -> new SerializableDataInputStream(new BufferedInputStream(new FileInputStream(inputFile.toFile()))),
                (final MerkleDataInputStream stream) -> {
                    if (vmStateExternal) {
                        loadFromFilePreV4(
                                inputFile,
                                stream,
                                new VirtualMapMetadata(stream.<ExternalVirtualMapMetadata>readSerializable()));
                    } else {
                        // This instance of `VirtualMapMetadata` will have a label only,
                        // it's necessary to initialize a datasource in `VirtualRootNode
                        final String label = requireNonNull(stream.readNormalisedString(MAX_LABEL_CHARS));
                        final long stateSize = stream.readLong();
                        loadFromFileV4(inputFile, stream, new VirtualMapMetadata(label, stateSize));
                    }
                    return null;
                });

        postInit();
    }

    private void loadFromFileV4(Path inputFile, MerkleDataInputStream stream, VirtualMapMetadata virtualMapMetadata)
            throws IOException {
        dataSourceBuilder = stream.readSerializable();
        dataSource = dataSourceBuilder.restore(virtualMapMetadata.getLabel(), inputFile.getParent());
        cache = new VirtualNodeCache(virtualMapConfig, stream.readLong());
        metadata = virtualMapMetadata;
    }

    private void loadFromFilePreV4(Path inputFile, MerkleDataInputStream stream, VirtualMapMetadata externalState)
            throws IOException {
        final int virtualRootVersion = stream.readInt();
        // Prior to V4 the label was serialized twice - as VirtualMap metadata and as VirtualRootNode metadata
        final String label = stream.readNormalisedString(MAX_LABEL_LENGTH);
        if (!externalState.getLabel().equals(label)) {
            throw new IllegalStateException("Label of the VirtualRootNode is not equal to the label of the VirtualMap");
        }
        dataSourceBuilder = stream.readSerializable();
        dataSource = dataSourceBuilder.restore(label, inputFile.getParent());
        metadata = externalState;
        if (virtualRootVersion < VirtualRootNode.ClassVersion.VERSION_3_NO_NODE_CACHE) {
            throw new UnsupportedOperationException("Version " + virtualRootVersion + " is not supported");
        }
        if (virtualRootVersion < VirtualRootNode.ClassVersion.VERSION_4_BYTES) {
            // FUTURE WORK: clean up all serializers, once all states are of version 4+
            stream.readSerializable(); // skip key serializer
            stream.readSerializable(); // skip externalState serializer
        }
        cache = new VirtualNodeCache(virtualMapConfig, stream.readLong());
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
