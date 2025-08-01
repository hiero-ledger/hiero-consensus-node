// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.QueueState;
import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.Reservable;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotWriter;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskReadableQueueState;
import com.swirlds.state.merkle.disk.OnDiskReadableSingletonState;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableQueueState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.json.JSONObject;

/**
 * An implementation of {@link State} backed by a single Virtual Map.
 */
public abstract class VirtualMapState<T extends VirtualMapState<T>> implements State {

    static final String VM_LABEL = "state";

    private static final Logger logger = LogManager.getLogger(VirtualMapState.class);

    private Time time;

    private Metrics metrics;

    /**
     * Metrics for the snapshot creation process
     */
    private MerkleRootSnapshotMetrics snapshotMetrics = new MerkleRootSnapshotMetrics();

    /**
     * Maintains information about each service, and each state of each service, known by this
     * instance. The key is the "service-name.state-key".
     */
    private final Map<String, Map<String, StateMetadata<?, ?>>> services = new HashMap<>();

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

    private Configuration configuration;

    private LongSupplier roundSupplier;

    private VirtualMap virtualMap;

    /**
     * Used to track the status of the Platform.
     * It is set to {@code true} if Platform status is not {@code PlatformStatus.ACTIVE}
     */
    private boolean startupMode = true;

    public VirtualMapState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        final MerkleDbDataSourceBuilder dsBuilder;
        final MerkleDbConfig merkleDbConfig = configuration.getConfigData(MerkleDbConfig.class);
        final var tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                // FUTURE WORK: drop StateDefinition.maxKeysHint and load VM size from VirtualMapConfig.size instead
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());
        dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, configuration);

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
        this.configuration = from.configuration;
        this.roundSupplier = from.roundSupplier;
        this.startupMode = from.startupMode;
        this.listeners.addAll(from.listeners);

        // Copy over the metadata
        for (final var entry : from.services.entrySet()) {
            this.services.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    public void init(
            Time time,
            Configuration configuration,
            Metrics metrics,
            MerkleCryptography merkleCryptography,
            LongSupplier roundSupplier) {
        this.time = time;
        this.configuration = configuration;
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
        final MerkleNode root = MerkleTreeSnapshotReader.readStateFileData(configuration, targetPath)
                .stateRoot();
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

    // MerkleNodeState interface implementation

    /**
     * @deprecated Should be removed once the MerkleStateRoot is removed along with {@code MerkleNodeState#putServiceStateIfAbsent()}
     */
    @Deprecated
    public <T extends MerkleNode> void putServiceStateIfAbsent(
            @NonNull final StateMetadata<?, ?> md,
            @NonNull final Supplier<T> nodeSupplier,
            @NonNull final Consumer<T> nodeInitializer) {
        throw new UnsupportedOperationException();
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
        stateMetadata.put(def.stateKey(), md);

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
     * Note that unlike the {@link #removeServiceState(String, String)} method in this class,
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
     * @param stateKey The state key
     */
    public void removeServiceState(@NonNull final String serviceName, @NonNull final String stateKey) {
        virtualMap.throwIfImmutable();
        requireNonNull(serviceName);
        requireNonNull(stateKey);

        // Remove the metadata entry
        final var stateMetadata = services.get(serviceName);
        if (stateMetadata != null) {
            stateMetadata.remove(stateKey);
        }

        // Eventually remove the cached WritableState
        final var writableStates = writableStatesMap.get(serviceName);
        if (writableStates != null) {
            writableStates.remove(stateKey);
        }
    }

    // Getters and setters

    public Map<String, Map<String, StateMetadata<?, ?>>> getServices() {
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
        protected final Map<String, StateMetadata<?, ?>> stateMetadata;
        protected final Map<String, ReadableKVState<?, ?>> kvInstances;
        protected final Map<String, ReadableSingletonState<?>> singletonInstances;
        protected final Map<String, ReadableQueueState<?>> queueInstances;
        private final Set<String> stateKeys;

        /**
         * Create a new instance
         *
         * @param stateMetadata cannot be null
         */
        MerkleStates(@NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            this.stateMetadata = requireNonNull(stateMetadata);
            this.stateKeys = Collections.unmodifiableSet(stateMetadata.keySet());
            this.kvInstances = new HashMap<>();
            this.singletonInstances = new HashMap<>();
            this.queueInstances = new HashMap<>();
        }

        @NonNull
        @Override
        public <K, V> ReadableKVState<K, V> get(@NonNull String stateKey) {
            final ReadableKVState<K, V> instance = (ReadableKVState<K, V>) kvInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || md.stateDefinition().singleton()) {
                throw new IllegalArgumentException("Unknown k/v state key '" + stateKey + ";");
            }

            final var ret = createReadableKVState(md);
            kvInstances.put(stateKey, ret);
            return ret;
        }

        @NonNull
        @Override
        public <T> ReadableSingletonState<T> getSingleton(@NonNull String stateKey) {
            final ReadableSingletonState<T> instance = (ReadableSingletonState<T>) singletonInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || !md.stateDefinition().singleton()) {
                throw new IllegalArgumentException("Unknown singleton state key '" + stateKey + "'");
            }

            final var ret = createReadableSingletonState(md);
            singletonInstances.put(stateKey, ret);
            return ret;
        }

        @NonNull
        @Override
        public <E> ReadableQueueState<E> getQueue(@NonNull String stateKey) {
            final ReadableQueueState<E> instance = (ReadableQueueState<E>) queueInstances.get(stateKey);
            if (instance != null) {
                return instance;
            }

            final var md = stateMetadata.get(stateKey);
            if (md == null || !md.stateDefinition().queue()) {
                throw new IllegalArgumentException("Unknown queue state key '" + stateKey + "'");
            }

            final var ret = createReadableQueueState(md);
            queueInstances.put(stateKey, ret);
            return ret;
        }

        @Override
        public boolean contains(@NonNull final String stateKey) {
            return stateMetadata.containsKey(stateKey);
        }

        @NonNull
        @Override
        public Set<String> stateKeys() {
            return stateKeys;
        }

        @NonNull
        protected abstract ReadableKVState createReadableKVState(@NonNull StateMetadata md);

        @NonNull
        protected abstract ReadableSingletonState createReadableSingletonState(@NonNull StateMetadata md);

        @NonNull
        protected abstract ReadableQueueState createReadableQueueState(@NonNull StateMetadata md);

        @NonNull
        static String extractStateKey(@NonNull final StateMetadata<?, ?> md) {
            return md.stateDefinition().stateKey();
        }

        @NonNull
        static Codec<?> extractKeyCodec(@NonNull final StateMetadata<?, ?> md) {
            return md.stateDefinition().keyCodec();
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
        MerkleReadableStates(@NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
        }

        @Override
        @NonNull
        protected ReadableKVState<?, ?> createReadableKVState(@NonNull final StateMetadata md) {
            return new OnDiskReadableKVState<>(md.serviceName(), extractStateKey(md), extractKeyCodec(md), virtualMap);
        }

        @Override
        @NonNull
        protected ReadableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            return new OnDiskReadableSingletonState<>(md.serviceName(), extractStateKey(md), virtualMap);
        }

        @NonNull
        @Override
        protected ReadableQueueState createReadableQueueState(@NonNull StateMetadata md) {
            return new OnDiskReadableQueueState(md.serviceName(), extractStateKey(md), virtualMap);
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
                @NonNull final String serviceName, @NonNull final Map<String, StateMetadata<?, ?>> stateMetadata) {
            super(stateMetadata);
            this.serviceName = requireNonNull(serviceName);
        }

        /**
         * Copies and releases the {@link VirtualMap} for the given state key. This ensures
         * data is continually flushed to disk
         *
         * @param stateKey the state key
         */
        public void copyAndReleaseVirtualMap(@NonNull final String stateKey) {
            final var md = stateMetadata.get(stateKey);
            final var mutableCopy = virtualMap.copy();
            if (metrics != null) {
                mutableCopy.registerMetrics(metrics);
            }
            virtualMap.release();

            virtualMap = mutableCopy; // so createReadableKVState below will do the job with updated map (copy)
            kvInstances.put(stateKey, createReadableKVState(md));
        }

        @NonNull
        @Override
        public <K, V> WritableKVState<K, V> get(@NonNull String stateKey) {
            return (WritableKVState<K, V>) super.get(stateKey);
        }

        @NonNull
        @Override
        public <T> WritableSingletonState<T> getSingleton(@NonNull String stateKey) {
            return (WritableSingletonState<T>) super.getSingleton(stateKey);
        }

        @NonNull
        @Override
        public <E> WritableQueueState<E> getQueue(@NonNull String stateKey) {
            return (WritableQueueState<E>) super.getQueue(stateKey);
        }

        @Override
        @NonNull
        protected WritableKVState<?, ?> createReadableKVState(@NonNull final StateMetadata md) {
            final var state =
                    new OnDiskWritableKVState<>(md.serviceName(), extractStateKey(md), extractKeyCodec(md), virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(MAP)) {
                    registerKVListener(serviceName, state, listener);
                }
            });
            return state;
        }

        @Override
        @NonNull
        protected WritableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            final var state = new OnDiskWritableSingletonState<>(md.serviceName(), extractStateKey(md), virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(SINGLETON)) {
                    registerSingletonListener(serviceName, state, listener);
                }
            });
            return state;
        }

        @NonNull
        @Override
        protected WritableQueueState<?> createReadableQueueState(@NonNull final StateMetadata md) {
            final var state = new OnDiskWritableQueueState<>(md.serviceName(), extractStateKey(md), virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(QUEUE)) {
                    registerQueueListener(serviceName, state, listener);
                }
            });
            return state;
        }

        @Override
        public void commit() {
            for (final ReadableKVState kv : kvInstances.values()) {
                ((WritableKVStateBase) kv).commit();
            }
            if (startupMode) {
                for (final ReadableSingletonState s : singletonInstances.values()) {
                    ((WritableSingletonStateBase) s).commit();
                }
            }
            for (final ReadableQueueState q : queueInstances.values()) {
                ((WritableQueueStateBase) q).commit();
            }
            readableStatesMap.remove(serviceName);
        }

        /**
         * This method is called when a state is removed from the state merkle tree. It is used to
         * remove the cached instances of the state.
         *
         * @param stateKey the state key
         */
        public void remove(String stateKey) {
            if (!Map.of().equals(stateMetadata)) {
                stateMetadata.remove(stateKey);
            }
            kvInstances.remove(stateKey);
            singletonInstances.remove(stateKey);
            queueInstances.remove(stateKey);
        }

        private <V> void registerSingletonListener(
                @NonNull final String serviceName,
                @NonNull final WritableSingletonStateBase<V> singletonState,
                @NonNull final StateChangeListener listener) {
            final var stateId = listener.stateIdFor(serviceName, singletonState.getStateKey());
            singletonState.registerListener(value -> listener.singletonUpdateChange(stateId, value));
        }

        private <V> void registerQueueListener(
                @NonNull final String serviceName,
                @NonNull final WritableQueueStateBase<V> queueState,
                @NonNull final StateChangeListener listener) {
            final var stateId = listener.stateIdFor(serviceName, queueState.getStateKey());
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

        private <K, V> void registerKVListener(
                @NonNull final String serviceName, WritableKVStateBase<K, V> state, StateChangeListener listener) {
            final var stateId = listener.stateIdFor(serviceName, state.getStateKey());
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
        final VirtualMapMetadata virtualMapMetadata = virtualMap.getState();

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
                final String stateKey = stateDefinition.stateKey();

                if (stateDefinition.singleton()) {
                    final Bytes keyBytes = StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(keyBytes);
                    if (leafBytes != null) {
                        final var hash = recordAccessor.findHash(leafBytes.path());
                        final JSONObject singletonJson = new JSONObject();
                        singletonJson.put("hash", hash);
                        singletonJson.put("path", leafBytes.path());
                        try {
                            final VirtualMapValue virtualMapValue =
                                    VirtualMapValue.PROTOBUF.parse(leafBytes.valueBytes());
                            final var typedSingletonValue = stateDefinition
                                    .valueCodec()
                                    .getDefaultInstance()
                                    .getClass()
                                    .cast(virtualMapValue.value().as());
                            singletonJson.put("value", typedSingletonValue);
                        } catch (ParseException e) {
                            singletonJson.put("value", "ParseException: " + e.getMessage());
                        }

                        singletons.put(StateUtils.computeLabel(serviceName, stateKey), singletonJson);
                    }
                } else if (stateDefinition.queue()) {
                    final Bytes keyBytes = StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey);
                    final VirtualLeafBytes<?> leafBytes = recordAccessor.findLeafRecord(keyBytes);
                    if (leafBytes != null) {
                        try {
                            final VirtualMapValue virtualMapValue =
                                    VirtualMapValue.PROTOBUF.parse(leafBytes.valueBytes());
                            final QueueState queueState = virtualMapValue.queueState();
                            final JSONObject queueJson = new JSONObject();
                            queueJson.put("head", queueState.head());
                            queueJson.put("tail", queueState.tail());
                            queueJson.put("path", leafBytes.path());
                            queues.put(StateUtils.computeLabel(serviceName, stateKey), queueJson);
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
        });

        rootJson.put("Singletons", singletons);
        rootJson.put("Queues (Queue States)", queues);

        return rootJson.toString();
    }
}
