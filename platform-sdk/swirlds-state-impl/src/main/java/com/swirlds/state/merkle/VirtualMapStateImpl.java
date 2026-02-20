// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static com.swirlds.state.merkle.StateItem.CODEC;
import static com.swirlds.state.merkle.StateKeyUtils.kvKey;
import static com.swirlds.state.merkle.StateKeyUtils.queueKey;
import static com.swirlds.state.merkle.StateKeyUtils.queueStateKey;
import static com.swirlds.state.merkle.StateKeyUtils.singletonKey;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static com.swirlds.state.merkle.StateUtils.unwrap;
import static com.swirlds.state.merkle.StateUtils.wrapValue;
import static com.swirlds.state.merkle.StateValue.extractStateIdFromStateValueOneOf;
import static com.swirlds.state.merkle.vm.VirtualMapQueueHelper.QUEUE_STATE_VALUE_CODEC;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.getSiblingPath;
import static com.swirlds.virtualmap.internal.Path.isLeft;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.crypto.Cryptography.NULL_HASH;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.Reservable;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.binary.MerkleProof;
import com.swirlds.state.binary.QueueState;
import com.swirlds.state.binary.QueueState.QueueStateCodec;
import com.swirlds.state.binary.SiblingHash;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.vm.VirtualMapReadableKVState;
import com.swirlds.state.merkle.vm.VirtualMapReadableQueueState;
import com.swirlds.state.merkle.vm.VirtualMapReadableSingletonState;
import com.swirlds.state.merkle.vm.VirtualMapWritableKVState;
import com.swirlds.state.merkle.vm.VirtualMapWritableQueueState;
import com.swirlds.state.merkle.vm.VirtualMapWritableSingletonState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.EmptyReadableStates;
import com.swirlds.state.spi.KVChangeListener;
import com.swirlds.state.spi.QueueChangeListener;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.json.JSONObject;

/**
 * An implementation of {@link State} backed by a single Virtual Map.
 */
public class VirtualMapStateImpl implements VirtualMapState {

    private static final Logger logger = LogManager.getLogger(VirtualMapStateImpl.class);

    /**
     * Maintains information about all services known by this instance. Map keys are
     * service names, values are service states by service ID.
     */
    protected final Map<String, Map<Integer, StateMetadata<?, ?>>> services = new HashMap<>();

    /**
     * Cache of used {@link ReadableStates}.
     */
    private final Map<String, ReadableStates> readableStatesMap = new ConcurrentHashMap<>();

    /**
     * Cache of used {@link WritableStates}.
     */
    private final Map<String, MerkleWritableStates> writableStatesMap = new HashMap<>();

    /**
     * Listeners to be notified of state changes on {@link MerkleWritableStates#commit()} calls for any service.
     */
    private final List<StateChangeListener> listeners = new ArrayList<>();

    private final Metrics metrics;

    /**
     * The state storage
     */
    protected VirtualMap virtualMap;

    /**
     * Initializes a {@link VirtualMapStateImpl}.
     *
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     */
    public VirtualMapStateImpl(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        final MerkleDbDataSourceBuilder dsBuilder;
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        dsBuilder = new MerkleDbDataSourceBuilder(
                configuration, merkleDbConfig.initialCapacity(), merkleDbConfig.hashesRamToDiskThreshold());

        this.virtualMap = new VirtualMap(dsBuilder, configuration);
        this.virtualMap.registerMetrics(metrics);
    }

    /**
     * Initializes a {@link VirtualMapStateImpl} with the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map with pre-registered metrics
     * @param metrics    the platform metric instance to use when creating the new instance of state
     */
    public VirtualMapStateImpl(@NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics) {
        this.virtualMap = requireNonNull(virtualMap);
        this.metrics = requireNonNull(metrics);
        this.virtualMap.registerMetrics(metrics);
    }

    /**
     * Protected constructor for fast-copy.
     *
     * @param from The other state to fast-copy from. Cannot be null.
     */
    protected VirtualMapStateImpl(@NonNull final VirtualMapStateImpl from) {
        this.virtualMap = from.virtualMap.copy();
        this.metrics = from.metrics;
        this.listeners.addAll(from.listeners);

        // Copy over the metadata
        for (final var entry : from.services.entrySet()) {
            this.services.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return readableStatesMap.computeIfAbsent(serviceName, s -> {
            final var stateMetadata = services.get(s);
            return stateMetadata == null ? EmptyReadableStates.INSTANCE : new MerkleReadableStates(stateMetadata);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public WritableStates getWritableStates(@NonNull final String serviceName) {
        virtualMap.throwIfImmutable();
        return writableStatesMap.computeIfAbsent(serviceName, s -> {
            final var stateMetadata = services.getOrDefault(s, Map.of());
            return new MerkleWritableStates(serviceName, stateMetadata);
        });
    }

    @Override
    public void registerCommitListener(@NonNull final StateChangeListener listener) {
        requireNonNull(listener);
        listeners.add(listener);
    }

    @Override
    public void unregisterCommitListener(@NonNull final StateChangeListener listener) {
        requireNonNull(listener);
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualMapStateImpl copy() {
        return new VirtualMapStateImpl(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHash() {
        virtualMap.throwIfMutable("Hashing should only be done on immutable states");
        virtualMap.throwIfDestroyed("Hashing should not be done on destroyed states");

        // this call will result in synchronous hash computation
        virtualMap.getHash();
    }

    /**
     * Initializes the defined service state.
     *
     * @param md The metadata associated with the state.
     */
    public void initializeState(@NonNull final StateMetadata<?, ?> md) {
        // Validate the inputs
        virtualMap.throwIfImmutable();
        requireNonNull(md);

        // Put this metadata into the map
        final var def = md.stateDefinition();
        final var serviceName = md.serviceName();
        final var stateMetadata = services.computeIfAbsent(serviceName, k -> new HashMap<>());
        stateMetadata.put(def.stateId(), md);

        // We also need to add/update the metadata of the service in the writableStatesMap so that
        // it isn't stale or incomplete (e.g. in a genesis case)
        readableStatesMap.put(serviceName, new MerkleReadableStates(stateMetadata));
        writableStatesMap.put(serviceName, new MerkleWritableStates(serviceName, stateMetadata));
    }

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateId     The state ID
     */
    public void removeServiceState(@NonNull final String serviceName, final int stateId) {
        virtualMap.throwIfImmutable();
        requireNonNull(serviceName);

        // Remove the metadata entry
        final var stateMetadata = services.get(serviceName);
        if (stateMetadata != null) {
            stateMetadata.remove(stateId);
        }

        // Eventually remove the cached WritableState
        final var writableStates = writableStatesMap.get(serviceName);
        if (writableStates != null) {
            writableStates.remove(stateId);
        }
    }

    // Getters and setters

    public Map<String, Map<Integer, StateMetadata<?, ?>>> getServices() {
        return services;
    }

    /**
     * Get the virtual map behind {@link VirtualMapStateImpl}. For more detailed docs, see
     * {@code VirtualMapStateImpl#getRoot()}.
     */
    public VirtualMap getRoot() {
        return virtualMap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Hash getHash() {
        return virtualMap.getHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(Hash hash) {
        throw new UnsupportedOperationException("VirtualMap is self hashing");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMutable() {
        return virtualMap.isMutable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return virtualMap.isImmutable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        return virtualMap.isDestroyed();
    }

    /**
     * Release a reservation on a Virtual Map.
     * For more detailed docs, see {@link Reservable#release()}.
     *
     * @return true if this call to release() caused the Virtual Map to become destroyed
     */
    public boolean release() {
        return virtualMap.release();
    }

    // Clean up

    /**
     * To be called ONLY at node shutdown. Attempts to gracefully close the Virtual Map.
     */
    public void close() {
        logger.info("Closing VirtualMapStateImpl");
        try {
            virtualMap.getDataSource().close();
        } catch (IOException e) {
            logger.warn("Unable to close data source for the Virtual Map", e);
        }
    }

    /**
     * Base class implementation for states based on MerkleTree
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private abstract static class MerkleStates implements ReadableStates {

        protected final Map<Integer, StateMetadata<?, ?>> stateMetadata;
        protected final Map<Integer, ReadableKVState<?, ?>> kvInstances;
        protected final Map<Integer, ReadableSingletonState<?>> singletonInstances;
        protected final Map<Integer, ReadableQueueState<?>> queueInstances;
        private final Set<Integer> stateIds;

        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleStates(@NonNull final Map<Integer, StateMetadata<?, ?>> stateMetadata) {
            this.stateMetadata = requireNonNull(stateMetadata);
            this.stateIds = Collections.unmodifiableSet(stateMetadata.keySet());
            this.kvInstances = new HashMap<>();
            this.singletonInstances = new HashMap<>();
            this.queueInstances = new HashMap<>();
        }

        @NonNull
        @Override
        public <K, V> ReadableKVState<K, V> get(final int stateId) {
            final ReadableKVState<K, V> instance = (ReadableKVState<K, V>) kvInstances.get(stateId);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateId);
            if (md == null || !md.stateDefinition().keyValue()) {
                throw new IllegalArgumentException("Unknown k/v state ID '" + stateId + ";");
            }

            final var ret = createReadableKVState(md);
            kvInstances.put(stateId, ret);
            return ret;
        }

        @NonNull
        @Override
        public <V> ReadableSingletonState<V> getSingleton(final int stateId) {
            final ReadableSingletonState<V> instance = (ReadableSingletonState<V>) singletonInstances.get(stateId);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateId);
            if (md == null || !md.stateDefinition().singleton()) {
                throw new IllegalArgumentException("Unknown singleton state ID '" + stateId + "'");
            }

            final var ret = createReadableSingletonState(md);
            singletonInstances.put(stateId, ret);
            return ret;
        }

        @NonNull
        @Override
        public <E> ReadableQueueState<E> getQueue(final int stateId) {
            final ReadableQueueState<E> instance = (ReadableQueueState<E>) queueInstances.get(stateId);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateId);
            if (md == null || !md.stateDefinition().queue()) {
                throw new IllegalArgumentException("Unknown queue state ID '" + stateId + "'");
            }

            final var ret = createReadableQueueState(md);
            queueInstances.put(stateId, ret);
            return ret;
        }

        @Override
        public boolean contains(final int stateId) {
            return stateMetadata.containsKey(stateId);
        }

        @NonNull
        @Override
        public Set<Integer> stateIds() {
            return stateIds;
        }

        @NonNull
        protected abstract ReadableKVState createReadableKVState(@NonNull StateMetadata md);

        @NonNull
        protected abstract ReadableSingletonState createReadableSingletonState(@NonNull StateMetadata md);

        @NonNull
        protected abstract ReadableQueueState createReadableQueueState(@NonNull StateMetadata md);

        static int extractStateId(@NonNull final StateMetadata<?, ?> md) {
            return md.stateDefinition().stateId();
        }

        @NonNull
        static String extractStateKey(@NonNull final StateMetadata<?, ?> md) {
            return md.stateDefinition().stateKey();
        }

        @NonNull
        static <K> Codec<K> extractKeyCodec(@NonNull final StateMetadata<K, ?> md) {
            return Objects.requireNonNull(md.stateDefinition().keyCodec(), "Key codec is null");
        }

        @NonNull
        static <V> Codec<V> extractValueCodec(@NonNull final StateMetadata<?, V> md) {
            return md.stateDefinition().valueCodec();
        }
    }

    /**
     * An implementation of {@link ReadableStates} based on the merkle tree.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public final class MerkleReadableStates extends MerkleStates {

        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleReadableStates(@NonNull final Map<Integer, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
        }

        @Override
        @NonNull
        protected ReadableKVState<?, ?> createReadableKVState(@NonNull final StateMetadata md) {
            return new VirtualMapReadableKVState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractKeyCodec(md),
                    extractValueCodec(md),
                    virtualMap);
        }

        @Override
        @NonNull
        protected ReadableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            return new VirtualMapReadableSingletonState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractValueCodec(md),
                    virtualMap);
        }

        @NonNull
        @Override
        protected ReadableQueueState createReadableQueueState(@NonNull StateMetadata md) {
            return new VirtualMapReadableQueueState(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractValueCodec(md),
                    virtualMap);
        }
    }

    /**
     * An implementation of {@link WritableStates} based on the merkle tree.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public final class MerkleWritableStates extends MerkleStates implements WritableStates, CommittableWritableStates {

        private final String serviceName;

        /**
         * Create a new instance
         *
         * @param serviceName   cannot be null
         * @param stateMetadata cannot be null
         */
        MerkleWritableStates(
                @NonNull final String serviceName, @NonNull final Map<Integer, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
            this.serviceName = requireNonNull(serviceName);
        }

        /**
         * Copies and releases the {@link VirtualMap} for the given state key. This ensures
         * data is continually flushed to disk
         *
         * @param stateId the state ID
         */
        public void copyAndReleaseVirtualMap(final int stateId) {
            final var md = stateMetadata.get(stateId);
            final var mutableCopy = virtualMap.copy();
            mutableCopy.registerMetrics(metrics);
            virtualMap.release();

            virtualMap = mutableCopy; // so createReadableKVState below will do the job with updated map (copy)
            kvInstances.put(stateId, createReadableKVState(md));
        }

        @NonNull
        @Override
        public <K, V> WritableKVState<K, V> get(final int stateId) {
            return (WritableKVState<K, V>) super.get(stateId);
        }

        @NonNull
        @Override
        public <V> WritableSingletonState<V> getSingleton(final int stateId) {
            return (WritableSingletonState<V>) super.getSingleton(stateId);
        }

        @NonNull
        @Override
        public <E> WritableQueueState<E> getQueue(final int stateId) {
            return (WritableQueueState<E>) super.getQueue(stateId);
        }

        @Override
        @NonNull
        protected WritableKVState<?, ?> createReadableKVState(@NonNull final StateMetadata md) {
            final var state = new VirtualMapWritableKVState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractKeyCodec(md),
                    extractValueCodec(md),
                    virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(MAP)) {
                    registerKVListener(state, listener);
                }
            });
            return state;
        }

        @Override
        @NonNull
        protected WritableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            final var state = new VirtualMapWritableSingletonState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractValueCodec(md),
                    virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(SINGLETON)) {
                    registerSingletonListener(state, listener);
                }
            });
            return state;
        }

        @NonNull
        @Override
        protected WritableQueueState<?> createReadableQueueState(@NonNull final StateMetadata md) {
            final var state = new VirtualMapWritableQueueState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractValueCodec(md),
                    virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(QUEUE)) {
                    registerQueueListener(state, listener);
                }
            });
            return state;
        }

        @Override
        public void commit() {
            // Ensure all commits always happen in lexicographic order by state ID
            kvInstances.keySet().stream().sorted().forEach(stateId -> ((WritableKVStateBase) kvInstances.get(stateId))
                    .commit());
            singletonInstances.keySet().stream()
                    .sorted()
                    .forEach(stateId -> ((WritableSingletonStateBase) singletonInstances.get(stateId)).commit());
            queueInstances.keySet().stream()
                    .sorted()
                    .forEach(stateId -> ((WritableQueueStateBase) queueInstances.get(stateId)).commit());
            readableStatesMap.remove(serviceName);
        }

        /**
         * This method is called when a state is removed from the state merkle tree. It is used to
         * remove the cached instances of the state.
         *
         * @param stateId the state ID
         */
        public void remove(final int stateId) {
            if (!Map.of().equals(stateMetadata)) {
                stateMetadata.remove(stateId);
            }
            kvInstances.remove(stateId);
            singletonInstances.remove(stateId);
            queueInstances.remove(stateId);
        }

        private <V> void registerSingletonListener(
                @NonNull final WritableSingletonStateBase<V> singletonState,
                @NonNull final StateChangeListener listener) {
            final var stateId = singletonState.getStateId();
            singletonState.registerListener(value -> listener.singletonUpdateChange(stateId, value));
        }

        private <V> void registerQueueListener(
                @NonNull final WritableQueueStateBase<V> queueState, @NonNull final StateChangeListener listener) {
            final var stateId = queueState.getStateId();
            queueState.registerListener(new QueueChangeListener<>() {
                @Override
                public void queuePushChange(@NonNull final V value) {
                    listener.queuePushChange(stateId, value);
                }

                @Override
                public void queuePopChange() {
                    listener.queuePopChange(stateId);
                }
            });
        }

        private <K, V> void registerKVListener(WritableKVStateBase<K, V> state, StateChangeListener listener) {
            final var stateId = state.getStateId();
            state.registerListener(new KVChangeListener<>() {
                @Override
                public void mapUpdateChange(@NonNull final K key, @NonNull final V value) {
                    listener.mapUpdateChange(stateId, key, value);
                }

                @Override
                public void mapDeleteChange(@NonNull final K key) {
                    listener.mapDeleteChange(stateId, key);
                }
            });
        }
    }

    /**
     * Commit all singleton states for every registered service.
     */
    public void commitSingletons() {
        services.forEach((serviceKey, serviceStates) -> serviceStates.entrySet().stream()
                .filter(stateMetadata ->
                        stateMetadata.getValue().stateDefinition().singleton())
                .forEach(service -> {
                    WritableStates writableStates = getWritableStates(serviceKey);
                    WritableSingletonStateBase<?> writableSingleton =
                            (WritableSingletonStateBase<?>) writableStates.getSingleton(service.getKey());
                    writableSingleton.commit();
                }));
    }

    /**
     * {@inheritDoc}}
     */
    public long getSingletonPath(final int stateId) {
        return virtualMap.getRecords().findPath(getStateKeyForSingleton(stateId));
    }

    /**
     * {@inheritDoc}}
     */
    @Override
    public long getQueueElementPath(final int stateId, @NonNull final Bytes expectedValue) {
        final StateValue<QueueState> queueStateValue =
                virtualMap.get(StateKeyUtils.queueStateKey(stateId), QUEUE_STATE_VALUE_CODEC);
        if (queueStateValue == null) {
            return INVALID_PATH;
        }
        final QueueState queueState = queueStateValue.value();

        for (long i = queueState.head(); i < queueState.tail(); i++) {
            final Bytes stateKey = StateUtils.getStateKeyForQueue(stateId, i);
            VirtualLeafBytes<?> leafRecord = virtualMap.getRecords().findLeafRecord(stateKey);
            if (leafRecord == null) {
                continue;
            }
            Bytes actualValue = unwrap(leafRecord.valueBytes());
            if (actualValue.equals(expectedValue)) {
                return leafRecord.path();
            }
        }

        return INVALID_PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getKvPath(final int stateId, @NonNull final Bytes key) {
        return virtualMap.getRecords().findPath(kvKey(stateId, key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHashForPath(long path) {
        return path == 0
                ? virtualMap.getRecords().rootHash()
                : virtualMap.getRecords().findHash(path);
    }

    @Override
    public MerkleProof getMerkleProof(final long path) {
        if (!isHashed()) {
            throw new IllegalStateException("Cannot get Merkle proof for unhashed virtual map");
        }

        VirtualLeafBytes<?> leafRecord = virtualMap.getRecords().findLeafRecord(path);
        if (leafRecord == null) {
            return null;
        }

        final List<SiblingHash> siblingHashes = new ArrayList<>();
        final List<Hash> innerParentHashes = new ArrayList<>();

        long currentPath = path;
        while (currentPath > 0) {
            final long siblingPath = getSiblingPath(currentPath);
            final boolean isSiblingLeft = isLeft(siblingPath);
            final Hash hashForPath = getHashForPath(siblingPath);
            final Hash normalizedHashForPath = hashForPath == null ? NULL_HASH : hashForPath;

            siblingHashes.add(new SiblingHash(isSiblingLeft, normalizedHashForPath));

            innerParentHashes.add(getHashForPath(currentPath));

            currentPath = getParentPath(currentPath);
        }

        assert virtualMap.getHash() != null;

        // add root hash
        innerParentHashes.add(virtualMap.getHash());

        StateItem stateItem = new StateItem(leafRecord.keyBytes(), leafRecord.valueBytes());
        return new MerkleProof(CODEC.toBytes(stateItem), siblingHashes, innerParentHashes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHashed() {
        return virtualMap.isHashed();
    }

    @Override
    public String getInfoJson() {
        final JSONObject rootJson = new JSONObject();

        final RecordAccessor recordAccessor = virtualMap.getRecords();
        final VirtualMapMetadata virtualMapMetadata = virtualMap.getMetadata();

        final JSONObject virtualMapMetadataJson = new JSONObject();
        virtualMapMetadataJson.put("firstLeafPath", virtualMapMetadata.getFirstLeafPath());
        virtualMapMetadataJson.put("lastLeafPath", virtualMapMetadata.getLastLeafPath());

        rootJson.put("VirtualMapMetadata", virtualMapMetadataJson);

        final JSONObject singletons = new JSONObject();
        final JSONObject queues = new JSONObject();

        services.forEach((key, value) -> {
            value.forEach((s, stateMetadata) -> {
                final String serviceName = stateMetadata.serviceName();
                final StateDefinition<?, ?> stateDefinition = stateMetadata.stateDefinition();
                final int stateId = stateDefinition.stateId();
                final String stateKey = stateDefinition.stateKey();

                if (stateDefinition.singleton()) {
                    final Bytes singletonKey = StateKeyUtils.singletonKey(stateId);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(singletonKey);
                    if (leafBytes != null) {
                        final var hash = recordAccessor.findHash(leafBytes.path());
                        final JSONObject singletonJson = new JSONObject();
                        if (hash != null) {
                            singletonJson.put("mnemonic", Mnemonics.generateMnemonic(hash));
                        }
                        singletonJson.put("path", leafBytes.path());
                        singletons.put(computeLabel(serviceName, stateKey), singletonJson);
                    }
                } else if (stateDefinition.queue()) {
                    final Bytes queueStateKey = StateKeyUtils.queueStateKey(stateId);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(queueStateKey);

                    if (leafBytes != null) {
                        final StateValue.StateValueCodec<QueueState> queueStateCodec = new StateValue.StateValueCodec<>(
                                extractStateIdFromStateValueOneOf(leafBytes.valueBytes()), new QueueStateCodec());
                        try {
                            final QueueState queueState = queueStateCodec
                                    .parse(leafBytes.valueBytes())
                                    .value();
                            final JSONObject queueJson = new JSONObject();
                            queueJson.put("head", queueState.head());
                            queueJson.put("tail", queueState.tail());
                            queueJson.put("path", leafBytes.path());
                            queues.put(computeLabel(serviceName, stateKey), queueJson);
                        } catch (ParseException e) {
                            throw new UncheckedParseException(e);
                        }
                    }
                }
            });
        });

        rootJson.put("Singletons", singletons);
        rootJson.put("Queues (Queue States)", queues);

        return rootJson.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes getKv(final int stateId, @NonNull final Bytes key) {
        final Bytes stateKey = kvKey(stateId, key);
        final Bytes stored = virtualMap.getBytes(stateKey);
        return stored == null ? null : unwrap(stored);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes getSingleton(final int singletonId) {
        try {
            final Bytes stateKey = getStateKeyForSingleton(singletonId);
            final Bytes stored = virtualMap.getBytes(stateKey);
            return stored == null ? null : unwrap(stored);
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            // Invalid state IDs (negative or too large) may cause index errors
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public QueueState getQueueState(final int stateID) {
        final Bytes queueStateKey = StateKeyUtils.queueStateKey(stateID);
        final Bytes queueStateBytes = virtualMap.getBytes(queueStateKey);
        if (queueStateBytes == null) {
            return null;
        }
        try {
            final Bytes unwrapped = unwrap(queueStateBytes);
            return QueueStateCodec.INSTANCE.parse(unwrapped);
        } catch (ParseException e) {
            throw new IllegalStateException("Failed to parse queue state for stateID: " + stateID, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes peekQueueHead(final int stateId) {
        final QueueState state = getQueueState(stateId);
        if (state == null || state.head() >= state.tail()) {
            return null; // Empty queue
        }
        final Bytes elementKey = queueKey(stateId, (int) state.head());
        final Bytes stored = virtualMap.getBytes(elementKey);
        return stored == null ? null : unwrap(stored);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes peekQueueTail(final int stateId) {
        final QueueState state = getQueueState(stateId);
        if (state == null || state.head() >= state.tail()) {
            return null; // Empty queue
        }
        // Tail points to the next position to write, so tail-1 is the last element
        final Bytes elementKey = queueKey(stateId, (int) (state.tail() - 1));
        final Bytes stored = virtualMap.getBytes(elementKey);
        return stored == null ? null : unwrap(stored);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes peekQueue(final int stateID, final int index) {
        final QueueState state = getQueueState(stateID);
        if (state == null) {
            return null;
        }
        if (index < state.head() || index >= state.tail()) {
            throw new IllegalArgumentException("Index " + index + " is out of bounds. Valid range is [" + state.head()
                    + ", " + (state.tail() - 1) + "]");
        }
        final Bytes elementKey = queueKey(stateID, index);
        final Bytes stored = virtualMap.getBytes(elementKey);
        return stored == null ? null : unwrap(stored);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Bytes> getQueueAsList(final int stateID) {
        final QueueState state = getQueueState(stateID);
        final List<Bytes> result = new ArrayList<>();
        for (long i = state.head(); i < state.tail(); i++) {
            final Bytes elementKey = queueKey(stateID, (int) i);
            final Bytes stored = virtualMap.getBytes(elementKey);
            final Bytes element = stored == null ? null : unwrap(stored);
            if (element != null) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateSingleton(final int stateId, @NonNull final Bytes value) {
        requireNonNull(value, "value must not be null");
        final Bytes key = singletonKey(stateId);
        final Bytes wrapped = wrapValue(stateId, value);
        virtualMap.putBytes(key, wrapped);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSingleton(int stateId) {
        virtualMap.remove(singletonKey(stateId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateKv(final int stateId, @NonNull final Bytes key, @Nullable final Bytes value) {
        requireNonNull(key, "key must not be null");
        final Bytes stateKey = kvKey(stateId, key);
        if (value == null) {
            virtualMap.remove(stateKey);
        } else {
            final Bytes wrapped = wrapValue(stateId, value);
            virtualMap.putBytes(stateKey, wrapped);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeKv(final int stateId, @NonNull final Bytes key) {
        requireNonNull(key, "key must not be null");
        virtualMap.remove(kvKey(stateId, key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushQueue(final int stateId, @NonNull final Bytes value) {
        requireNonNull(value, "value must not be null");
        final Bytes qStateKey = queueStateKey(stateId);
        final Bytes existing = virtualMap.getBytes(qStateKey);
        final QueueState qState;
        if (existing == null) {
            // initialize to 1-based empty queue
            qState = new QueueState(1, 1);
        } else {
            try {
                final Bytes unwrapped = unwrap(existing);
                qState = QueueStateCodec.INSTANCE.parse(unwrapped);
            } catch (com.hedera.pbj.runtime.ParseException e) {
                throw new IllegalStateException("Failed to parse existing queue state", e);
            }
        }

        // store element at current tail
        final Bytes elementKey = queueKey(stateId, (int) qState.tail());
        final Bytes wrappedElement = wrapValue(stateId, value);
        virtualMap.putBytes(elementKey, wrappedElement);

        // increment tail and persist queue state
        final QueueState updated = qState.elementAdded();
        final Bytes rawState = QueueStateCodec.INSTANCE.toBytes(updated);
        final Bytes wrappedState = wrapValue(stateId, rawState);
        virtualMap.putBytes(qStateKey, wrappedState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytes popQueue(final int stateId) {
        final Bytes qStateKey = queueStateKey(stateId);
        final QueueState qState = getQueueState(stateId);
        if (qState == null) return null; // queue not found

        if (qState.head() == qState.tail()) { // queue is empty
            return null;
        }

        final Bytes elementKey = queueKey(stateId, (int) qState.head());
        final Bytes stored = virtualMap.getBytes(elementKey);
        final Bytes value = stored == null ? null : unwrap(stored);
        // remove element (even if stored was null, remove is safe)
        virtualMap.remove(elementKey);

        // increment head
        final QueueState updated = qState.elementRemoved();
        final Bytes rawState = QueueStateCodec.INSTANCE.toBytes(updated);
        final Bytes wrappedState = wrapValue(stateId, rawState);
        virtualMap.putBytes(qStateKey, wrappedState);

        return value;
    }

    /**
     * {@inheritDoc}}
     */
    @Override
    public void removeQueue(int stateId) {
        final Bytes qStateKey = queueStateKey(stateId);
        QueueState qState = getQueueState(stateId);
        if (qState == null) {
            return;
        }
        long tail = qState.tail();
        while (qState.head() < tail) {
            virtualMap.remove(queueKey(stateId, (int) qState.head()));
            qState = qState.elementRemoved();
        }
        virtualMap.remove(qStateKey);
    }
}
