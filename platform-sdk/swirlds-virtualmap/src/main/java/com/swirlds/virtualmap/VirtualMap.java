// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.common.io.streams.StreamDebugUtils.deserializeAndDebugOnFailure;
import static com.swirlds.virtualmap.VirtualMap.CLASS_ID;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.utility.CommonUtils.getNormalisedStringBytes;

import com.swirlds.common.constructable.ConstructableClass;
import com.swirlds.common.io.ExternalSelfSerializable;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.SerializableDataInputStreamImpl;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.utility.DebugIterationEndpoint;
import com.swirlds.common.utility.Labeled;
import com.swirlds.common.utility.RuntimeObjectRecord;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.common.utility.ValueReference;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.constructable.constructors.VirtualMapConstructor;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualMapState;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.internal.merkle.VirtualStateAccessorImpl;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.constructable.RuntimeConstructable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

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
 * in the leaf nodes of this node's sub-tree. The map-like methods {@link #get(VirtualKey)},
 * {@link #put(VirtualKey, VirtualValue)}, and {@link #remove(VirtualKey)} can be used as a
 * fast and convenient way to read, add, modify, or delete the corresponding leaf nodes and
 * internal nodes. Indeed, you <strong>MUST NOT</strong> modify the tree structure directly, only
 * through the map-like methods.
 *
 * @param <K>
 * 		The key. Must be a {@link VirtualKey}. It must also be <strong>immutable</strong>.
 * @param <V>
 * 		The value. Must be a {@link VirtualValue}.
 */
@DebugIterationEndpoint
@ConstructableClass(value = CLASS_ID, constructorType = VirtualMapConstructor.class)
public final class VirtualMap<K extends VirtualKey, V extends VirtualValue> extends PartialBinaryMerkleInternal
        implements ExternalSelfSerializable, Labeled, MerkleInternal {

    /**
     * Used for serialization.
     */
    public static final long CLASS_ID = 0xb881f3704885e853L;

    /**
     * This version number should be used to handle compatibility issues that may arise from any future changes
     */
    public static class ClassVersion {
        public static final int ORIGINAL = 1;
        public static final int MERKLE_SERIALIZATION_CLEANUP = 2;
        public static final int REHASH_LEAVES = 3;
    }

    private static final class ChildIndices {
        /**
         * The index of the first child, which is used for storing in-state map {@link VirtualMapState}.
         */
        private static final int MAP_STATE_CHILD_INDEX = 0;

        /**
         * The index of the second child which is the {@link VirtualRootNode}.
         */
        private static final int VIRTUAL_ROOT_CHILD_INDEX = 1;
    }

    /**
     * A reference to the first child, the {@link VirtualMapState}. Ideally this would be final
     * and never null, but serialization requires partially constructed objects, so it must not be
     * final and may be null until deserialization is complete.
     */
    private VirtualMapState state;

    /**
     * A reference to the second child, the {@link VirtualRootNode}. We hold this reference
     * only for performance overhead reasons. Ideally this would be final and never null, but
     * serialization requires partially constructed objects, so it must not be final and may be
     * null until deserialization is complete.
     */
    private VirtualRootNode<K, V> root;

    /**
     * Used to track the lifespan of this virtual map. The record is released when the map is destroyed.
     */
    private final RuntimeObjectRecord registryRecord;

    /** Platform configuration */
    @NonNull
    private final Configuration configuration;

    /**
     * Required by the {@link RuntimeConstructable} contract.
     * This can <strong>only</strong> be called as part of serialization and reconnect, not for normal use.
     */
    public VirtualMap(final @NonNull Configuration configuration) {
        requireNonNull(configuration);
        this.configuration = configuration;
        registryRecord = RuntimeObjectRegistry.createRecord(getClass());
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
            final KeySerializer<K> keySerializer,
            final ValueSerializer<V> valueSerializer,
            final VirtualDataSourceBuilder dataSourceBuilder,
            final @NonNull Configuration configuration) {
        this(configuration);
        setChild(ChildIndices.MAP_STATE_CHILD_INDEX, new VirtualMapState(requireNonNull(label)));
        setChild(
                ChildIndices.VIRTUAL_ROOT_CHILD_INDEX,
                new VirtualRootNode<>(
                        keySerializer,
                        valueSerializer,
                        requireNonNull(dataSourceBuilder),
                        requireNonNull(configuration.getConfigData(VirtualMapConfig.class))));
    }

    /**
     * Create a copy of the given source.
     *
     * @param source
     * 		must not be null.
     */
    private VirtualMap(final VirtualMap<K, V> source) {
        this(source.configuration);
        setChild(ChildIndices.MAP_STATE_CHILD_INDEX, source.getState().copy());
        setChild(ChildIndices.VIRTUAL_ROOT_CHILD_INDEX, source.getRoot().copy());
    }

    /**
     * Gets the {@link VirtualDataSource} used with this map.
     *
     * @return A non-null reference to the data source.
     */
    public VirtualDataSource getDataSource() {
        return root.getDataSource();
    }

    /**
     * Gets the current state.
     *
     * @return The current state
     */
    VirtualMapState getState() {
        return state;
    }

    /**
     * Gets the current root node.
     *
     * @return The current root node
     */
    VirtualRootNode<K, V> getRoot() {
        return root;
    }

    /**
     * Register all statistics with a registry. If not called then no statistics will be captured for this map.
     *
     * @param metrics
     * 		refernece to the metrics-system
     */
    public void registerMetrics(final Metrics metrics) {
        root.registerMetrics(metrics);
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
        return ClassVersion.REHASH_LEAVES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLabel() {
        return state.getLabel();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualMap<K, V> copy() {
        throwIfImmutable();
        throwIfDestroyed();

        final VirtualMap<K, V> copy = new VirtualMap<>(this);
        setImmutable(true);
        return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(final int index, final MerkleNode child) {
        // The children of this node are *ONLY* updated during initialization, where initialization includes:
        //   - Normal construction
        //   - Copy construction
        //   - Reconnect construction
        //   - Restart (from saved state) construction.
        //
        // All four of these uses end up creating a new VirtualMapState instance and a new VirtualRootNode instance
        // and need to supply the virtual root node with a StateAccessor that can interface with the new VirtualMapState
        // instance. This would be trivial except for reconnect and restart (serialization) use cases because
        // the serialization engine will create an incomplete VirtualMap structure and then add the children to it
        // dynamically.
        //
        // For this reason we must construct an incomplete VirtualRootNode and then finish initialization on it.
        if (index == ChildIndices.MAP_STATE_CHILD_INDEX) {
            state = child.cast();
        } else if (index == ChildIndices.VIRTUAL_ROOT_CHILD_INDEX) {
            root = child.cast();
            root.postInit(new VirtualStateAccessorImpl(state));
        }

        super.setChild(index, child);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out, final Path outputDirectory) throws IOException {

        // Create and write to state the name of the file we will expect later on deserialization
        final String outputFileName = state.getLabel() + ".vmap";
        final byte[] outputFileNameBytes = getNormalisedStringBytes(outputFileName);
        out.writeInt(outputFileNameBytes.length);
        out.writeNormalisedString(outputFileName);

        // Write the virtual map and sub nodes
        final Path outputFile = outputDirectory.resolve(outputFileName);
        try (SerializableDataOutputStream serout = new SerializableDataOutputStreamImpl(
                new BufferedOutputStream(new FileOutputStream(outputFile.toFile())))) {
            serout.writeSerializable(state, true);
            serout.writeInt(root.getVersion());
            root.serialize(serout, outputDirectory);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final Path inputDirectory, final int version)
            throws IOException {

        if (version == ClassVersion.ORIGINAL) {
            // Read and discard the hash that is in the stream at this position
            in.readSerializable();
        }

        final int fileNameLengthInBytes = in.readInt();
        final String inputFileName = in.readNormalisedString(fileNameLengthInBytes);
        final Path inputFile = inputDirectory.resolve(inputFileName);
        loadFromFile(inputFile);
        if (version < ClassVersion.REHASH_LEAVES) {
            root.fullLeafRehashIfNecessary();
        }
    }

    /**
     * Deserializes the given serialized VirtualMap file into this map instance. This is not intended for
     * public use, it is for testing and tools only.
     *
     * @param inputFile
     * 		The input .vmap file. Cannot be null.
     * @throws IOException
     * 		For problems.
     */
    public void loadFromFile(final Path inputFile) throws IOException {
        final ValueReference<VirtualMapState> virtualMapState = new ValueReference<>();
        final ValueReference<VirtualRootNode<K, V>> virtualRootNode = new ValueReference<>();

        deserializeAndDebugOnFailure(
                () -> new SerializableDataInputStreamImpl(
                        new BufferedInputStream(new FileInputStream(inputFile.toFile()))),
                (final MerkleDataInputStream stream) -> {
                    virtualMapState.setValue(stream.readSerializable());
                    virtualRootNode.setValue(
                            new VirtualRootNode<>(configuration.getConfigData(VirtualMapConfig.class)));
                    virtualRootNode.getValue().deserialize(stream, inputFile.getParent(), stream.readInt());
                    return null;
                });

        state = virtualMapState.getValue();
        root = virtualRootNode.getValue();
        addDeserializedChildren(List.of(state, root), getVersion());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void destroyNode() {
        registryRecord.release();
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
        return state.getSize();
    }

    /*
     * Gets whether this map is empty.
     *
     * @return True if the map is empty
     */
    public boolean isEmpty() {
        return root.isEmpty();
    }

    /*
     * Gets whether this map is initialized and valid.
     *
     * @return True if the map is valid and put operations can be used on the map.
     */
    public boolean isValid() {
        return root != null;
    }

    /**
     * Checks whether a leaf for the given key exists.
     *
     * @param key
     * 		The key. Cannot be null.
     * @return True if there is a leaf corresponding to this key.
     */
    public boolean containsKey(final K key) {
        return root.containsKey(key);
    }

    /**
     * Gets the value associated with the given key.
     *
     * @param key
     * 		The key. This must not be null.
     * @return The value. The value may be null, or will be read only.
     */
    public V get(final K key) {
        return root.get(key);
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
        root.put(key, value);
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
        return root.remove(key);
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
     *  @param key key of the leaf to warm
     */
    public void warm(final K key) {
        root.warm(key);
    }
}
