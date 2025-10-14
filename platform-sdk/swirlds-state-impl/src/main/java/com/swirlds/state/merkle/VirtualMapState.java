// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static com.swirlds.state.merkle.disk.OnDiskQueueHelper.QUEUE_STATE_VALUE_CODEC;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.Reservable;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotWriter;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskReadableQueueState;
import com.swirlds.state.merkle.disk.OnDiskReadableSingletonState;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableQueueState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.merkle.disk.QueueState;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * An implementation of {@link State} backed by a single Virtual Map.
 */
public abstract class VirtualMapState<T extends VirtualMapState<T>> implements MerkleNodeState {

    public static final String VM_LABEL = "state";

    private static final Logger logger = LogManager.getLogger(VirtualMapState.class);

    private Time time;

    private Metrics metrics;

    /**
     * Metrics for the snapshot creation process
     */
    private MerkleRootSnapshotMetrics snapshotMetrics = new MerkleRootSnapshotMetrics();

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

    private LongSupplier roundSupplier;

    protected VirtualMap virtualMap;

    /**
     * Used to track the status of the Platform.
     * It is set to {@code true} if Platform status is not {@code PlatformStatus.ACTIVE}
     */
    private boolean startupMode = true;

    public VirtualMapState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        final MerkleDbDataSourceBuilder dsBuilder;
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        dsBuilder = new MerkleDbDataSourceBuilder(
                configuration, merkleDbConfig.initialCapacity(), merkleDbConfig.hashesRamToDiskThreshold());

        this.virtualMap = new VirtualMap(VM_LABEL, dsBuilder, configuration);
        this.virtualMap.registerMetrics(metrics);
    }

    /**
     * Initializes a {@link VirtualMapState} with the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map with pre-registered metrics
     */
    public VirtualMapState(@NonNull final VirtualMap virtualMap) {
        this.virtualMap = virtualMap;
    }

    /**
     * Protected constructor for fast-copy.
     *
     * @param from The other state to fast-copy from. Cannot be null.
     */
    protected VirtualMapState(@NonNull final VirtualMapState<T> from) {
        this.virtualMap = from.virtualMap.copy();
        this.roundSupplier = from.roundSupplier;
        this.startupMode = from.startupMode;
        this.listeners.addAll(from.listeners);

        // Copy over the metadata
        for (final var entry : from.services.entrySet()) {
            this.services.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    public void init(Time time, Metrics metrics, MerkleCryptography merkleCryptography, LongSupplier roundSupplier) {
        this.time = time;
        this.metrics = metrics;
        this.snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);
        this.roundSupplier = roundSupplier;
    }

    /**
     * Creates a copy of the instance.
     * @return a copy of the instance
     */
    protected abstract T copyingConstructor();

    /**
     * Creates a new instance.
     * @param virtualMap should have already registered metrics
     */
    protected abstract T newInstance(@NonNull final VirtualMap virtualMap);

    // State interface implementation

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
    public T copy() {
        return copyingConstructor();
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
     * {@inheritDoc}
     */
    @Override
    public void createSnapshot(@NonNull final Path targetPath) {
        requireNonNull(time);
        requireNonNull(snapshotMetrics);
        virtualMap.throwIfMutable();
        virtualMap.throwIfDestroyed();
        final long startTime = time.currentTimeMillis();
        MerkleTreeSnapshotWriter.createSnapshot(virtualMap, targetPath, roundSupplier.getAsLong());
        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T loadSnapshot(@NonNull Path targetPath) throws IOException {
        final MerkleNode root =
                MerkleTreeSnapshotReader.readStateFileData(targetPath).stateRoot();
        if (!(root instanceof VirtualMap readVirtualMap)) {
            throw new IllegalStateException(
                    "Root should be a VirtualMap, but it is " + root.getClass().getSimpleName() + " instead");
        }

        final var mutableCopy = readVirtualMap.copy();
        if (metrics != null) {
            mutableCopy.registerMetrics(metrics);
        }
        readVirtualMap.release();
        readVirtualMap = mutableCopy;

        return newInstance(readVirtualMap);
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
     * Unregister a service without removing its nodes from the state.
     * <p>
     * Services such as the PlatformStateService and RosterService may be registered
     * on a newly loaded (or received via Reconnect) SignedState object in order
     * to access the PlatformState and RosterState/RosterMap objects so that the code
     * can fetch the current active Roster for the state and validate it. Once validated,
     * the state may need to be loaded into the system as the actual state,
     * and as a part of this process, the States API
     * is going to be initialized to allow access to all the services known to the app.
     * However, the States API initialization is guarded by a
     * {@code state.getReadableStates(PlatformStateService.NAME).isEmpty()} check.
     * So if this service has previously been initialized, then the States API
     * won't be initialized in full.
     * <p>
     * To prevent this and to allow the system to initialize all the services,
     * we unregister the PlatformStateService and RosterService after the validation is performed.
     * <p>
     * Note that unlike the {@link #removeServiceState(String, int)} method in this class,
     * the unregisterService() method will NOT remove the merkle nodes that store the states of
     * the services being unregistered. This is by design because these nodes will be used
     * by the actual service states once the app initializes the States API in full.
     *
     * @param serviceName a service to unregister
     */
    public void unregisterService(@NonNull final String serviceName) {
        readableStatesMap.remove(serviceName);
        writableStatesMap.remove(serviceName);

        services.remove(serviceName);
    }

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateId The state ID
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
     * {@inheritDoc}
     */
    @Override
    public boolean isStartUpMode() {
        return startupMode;
    }

    public void disableStartupMode() {
        startupMode = false;
    }

    /**
     * Get the virtual map behind {@link VirtualMapState}.
     * For more detailed docs, see {@code MerkleNodeState#getRoot()}.
     */
    public MerkleNode getRoot() {
        return virtualMap;
    }

    /**
     * Sets the time for this state.
     *
     * @param time the time to set
     */
    public void setTime(final Time time) {
        this.time = time;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
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
        logger.info("Closing VirtualMapState");
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
    private abstract class MerkleStates implements ReadableStates {

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
            if (md == null || md.stateDefinition().singleton()) {
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
            return new OnDiskReadableKVState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractKeyCodec(md),
                    extractValueCodec(md),
                    virtualMap);
        }

        @Override
        @NonNull
        protected ReadableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            return new OnDiskReadableSingletonState<>(
                    extractStateId(md),
                    computeLabel(md.serviceName(), extractStateKey(md)),
                    extractValueCodec(md),
                    virtualMap);
        }

        @NonNull
        @Override
        protected ReadableQueueState createReadableQueueState(@NonNull StateMetadata md) {
            return new OnDiskReadableQueueState(
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
         * @param serviceName cannot be null
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
            if (metrics != null) {
                mutableCopy.registerMetrics(metrics);
            }
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
            final var state = new OnDiskWritableKVState<>(
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
            final var state = new OnDiskWritableSingletonState<>(
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
            final var state = new OnDiskWritableQueueState<>(
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
            if (startupMode) {
                singletonInstances.keySet().stream()
                        .sorted()
                        .forEach(stateId -> ((WritableSingletonStateBase) singletonInstances.get(stateId)).commit());
            }
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
    public long singletonPath(final int stateId) {
        return virtualMap.getRecords().findPath(StateUtils.getStateKeyForSingleton(stateId));
    }

    /**
     * {@inheritDoc}}
     */
    @Override
    public long queueElementPath(final int stateId, @NonNull final Bytes expectedValue) {
        final StateValue<QueueState> queueStateValue =
                virtualMap.get(StateKeyUtils.queueStateKey(stateId), QUEUE_STATE_VALUE_CODEC);
        if (queueStateValue == null) {
            return INVALID_PATH;
        }
        final QueueState queueState = queueStateValue.value();

        for (long i = queueState.head(); i < queueState.tail(); i++) {
            final Bytes stateKey = StateUtils.getStateKeyForQueue(stateId, i);
            long path = virtualMap.getRecords().findPath(stateKey);
            if (path == INVALID_PATH) {
                continue;
            }
            VirtualLeafBytes<?> leafRecord = virtualMap.getRecords().findLeafRecord(path);
            if (leafRecord == null) {
                continue;
            }
            Bytes actualValue = StateValue.StateValueCodec.unwrap(leafRecord.valueBytes());
            if (actualValue.equals(expectedValue)) {
                return path;
            }
        }

        return INVALID_PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long kvPath(final int stateId, @NonNull final Bytes key) {
        return virtualMap.getRecords().findPath(StateKeyUtils.kvKey(stateId, key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHashed() {
        return virtualMap.isHashed();
    }
}
