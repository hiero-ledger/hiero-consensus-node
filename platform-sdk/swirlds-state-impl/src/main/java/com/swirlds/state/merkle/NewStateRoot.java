/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.merkle;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
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
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NewStateRoot implements State {

    private static final Logger logger = LogManager.getLogger(NewStateRoot.class);

    /**
     * Used when asked for a service's readable states that we don't have
     */
    private static final ReadableStates EMPTY_READABLE_STATES = new EmptyReadableStates();

    private MerkleCryptography merkleCryptography;

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
    private final Map<String, NewStateRoot.MerkleWritableStates> writableStatesMap = new HashMap<>();

    /**
     * Listeners to be notified of state changes on {@link NewStateRoot.MerkleWritableStates#commit()} calls for any service.
     */
    private final List<StateChangeListener> listeners = new ArrayList<>();

    private Configuration configuration;

    private VirtualMap virtualMap;

    // not sure if it is needed though!
    /**
     * Protected constructor for fast-copy.
     *
     * @param from The other state to fast-copy from. Cannot be null.
     */
    protected NewStateRoot(@NonNull final NewStateRoot from) {
        this.virtualMap = from.virtualMap.copy(); // not sure in this

        this.listeners.addAll(from.listeners);

        // Copy over the metadata
        for (final var entry : from.services.entrySet()) {
            this.services.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    // This is how MerkleStateRoot was init (except configuration) -- maybe need to work out new way here
    public void init(Configuration configuration, Time time, Metrics metrics, MerkleCryptography merkleCryptography) {
        this.configuration = configuration;
        this.time = time;
        this.metrics = metrics;
        this.merkleCryptography = merkleCryptography;
        snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);
    }

    // State interface impl

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return readableStatesMap.computeIfAbsent(serviceName, s -> {
            final var stateMetadata = services.get(s);
            return stateMetadata == null ? EMPTY_READABLE_STATES : new NewStateRoot.MerkleReadableStates(stateMetadata);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public WritableStates getWritableStates(@NonNull final String serviceName) {
        throwIfImmutable();
        return writableStatesMap.computeIfAbsent(serviceName, s -> {
            final var stateMetadata = services.getOrDefault(s, Map.of());
            return new NewStateRoot.MerkleWritableStates(serviceName, stateMetadata);
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
    public NewStateRoot copy() {
        // TODO: double check
        /* this.virtualMap handles this:
        throwIfImmutable();
        throwIfDestroyed();
        setImmutable(true);
         */
        return new NewStateRoot(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHash() {
        requireNonNull(
                merkleCryptography,
                "MerkleStateRoot has to be initialized before hashing. merkleCryptography is not set.");
        throwIfMutable("Hashing should only be done on immutable states");
        throwIfDestroyed("Hashing should not be done on destroyed states");
        if (getHash() != null) {
            return;
        }
        try {
            merkleCryptography.digestTreeAsync(virtualMap).get(); // TODO: double check
        } catch (final ExecutionException e) {
            logger.error(EXCEPTION.getMarker(), "Exception occurred during hashing", e);
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Interrupted while hashing state. Expect buggy behavior.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createSnapshot(@NonNull final Path targetPath) {
        requireNonNull(time);
        requireNonNull(snapshotMetrics);
        throwIfMutable();
        throwIfDestroyed();
        final long startTime = time.currentTimeMillis();
        MerkleTreeSnapshotWriter.createSnapshot(virtualMap, targetPath, getCurrentRound()); // TODO: double check
        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleStateRoot<?> loadSnapshot(@NonNull Path targetPath) throws IOException {
        requireNonNull(configuration);
        return (MerkleStateRoot<?>) MerkleTreeSnapshotReader.readStateFileData(configuration, targetPath)
                .stateRoot();
    }

    // Getters and setters

    // TODO: update two methods below (most likely after closing of
    // https://github.com/hashgraph/hedera-services/issues/17357)

    public @Nullable PlatformState getPlatformState() {
        ReadableStates readableStates = getReadableStates("PlatformStateService");
        return readableStates.isEmpty()
                ? null
                : (PlatformState) readableStates.getSingleton("PLATFORM_STATE").get();
    }

    /**
     * Returns the round number from the consensus snapshot, or the genesis round if there is no consensus snapshot.
     */
    public long getCurrentRound() {
        return (getPlatformState() == null || getPlatformState().consensusSnapshot() == null)
                ? 0
                : getPlatformState().consensusSnapshot().round();
    }

    /**
     * Sets the time for this state.
     *
     * @param time the time to set
     */
    public void setTime(final Time time) {
        this.time = time;
    }

    public Map<String, Map<String, StateMetadata<?, ?>>> getServices() {
        return services;
    }

    // Clean up

    /**
     * To be called ONLY at node shutdown -- attempts to gracefully close the Virtual Map.
     */
    public void close() {
        logger.info("Closing NewStateRoot"); // TODO: update class name
        try {
            virtualMap.getDataSource().close();
        } catch (IOException e) {
            logger.warn("Unable to close data source for the Virtual Map", e);
        }
    }

    // State API related ops

    // TODO: unify names of those three methods below: initializeState, unregisterService, removeServiceState

    /**
     * Initializes the defined service state.
     *
     * @param md The metadata associated with the state.
     * @throws IllegalArgumentException if md doesn't have a label, or if the label isn't right.
     */
    public void initializeState(@NonNull final StateMetadata<?, ?> md) {
        // Validate the inputs
        throwIfImmutable();
        requireNonNull(md);

        // Put this metadata into the map
        final var def = md.stateDefinition();
        final var serviceName = md.serviceName();
        final var stateMetadata = services.computeIfAbsent(serviceName, k -> new HashMap<>());
        stateMetadata.put(def.stateKey(), md);

        // We also need to add/update the metadata of the service in the writableStatesMap so that
        // it isn't stale or incomplete (e.g. in a genesis case)
        readableStatesMap.put(serviceName, new NewStateRoot.MerkleReadableStates(stateMetadata));
        writableStatesMap.put(serviceName, new NewStateRoot.MerkleWritableStates(serviceName, stateMetadata));
    }

    /**
     * Unregister a service without removing its nodes from the state.
     *
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
     *
     * To prevent this and to allow the system to initialize all the services,
     * we unregister the PlatformStateService and RosterService after the validation is performed.
     *
     * Note that unlike the MerkleStateRoot.removeServiceState() method below in this class,
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
        throwIfImmutable();
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
            return new OnDiskReadableKVState<>(
                    md.serviceName(),
                    extractStateKey(md),
                    Objects.requireNonNull(md.stateDefinition().keyCodec()),
                    md.stateDefinition().valueCodec(),
                    virtualMap);
        }

        @Override
        @NonNull
        protected ReadableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            return new OnDiskReadableSingletonState<>(
                    md.serviceName(), extractStateKey(md), md.stateDefinition().valueCodec(), virtualMap);
        }

        @NonNull
        @Override
        protected ReadableQueueState createReadableQueueState(@NonNull StateMetadata md) {
            return new OnDiskReadableQueueState(
                    md.serviceName(), extractStateKey(md), md.stateDefinition().valueCodec(), virtualMap);
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
            final var state = new OnDiskWritableKVState<>(
                    md.serviceName(),
                    extractStateKey(md),
                    Objects.requireNonNull(md.stateDefinition().keyCodec()),
                    md.stateDefinition().valueCodec(),
                    virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(MAP)) {
                    registerKVListener(md.serviceName(), state, listener);
                }
            });
            return state;
        }

        @Override
        @NonNull
        protected WritableSingletonState<?> createReadableSingletonState(@NonNull final StateMetadata md) {
            final var state = new OnDiskWritableSingletonState<>(
                    md.serviceName(), extractStateKey(md), md.stateDefinition().valueCodec(), virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(SINGLETON)) {
                    registerSingletonListener(md.serviceName(), state, listener);
                }
            });
            return state;
        }

        @NonNull
        @Override
        protected WritableQueueState<?> createReadableQueueState(@NonNull final StateMetadata md) {
            final var state = new OnDiskWritableQueueState<>(
                    md.serviceName(), extractStateKey(md), md.stateDefinition().valueCodec(), virtualMap);
            listeners.forEach(listener -> {
                if (listener.stateTypes().contains(QUEUE)) {
                    registerQueueListener(md.serviceName(), state, listener);
                }
            });
            return state;
        }

        @Override
        public void commit() {
            for (final ReadableKVState kv : kvInstances.values()) {
                ((WritableKVStateBase) kv).commit();
            }
            for (final ReadableSingletonState s : singletonInstances.values()) {
                ((WritableSingletonStateBase) s).commit();
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
            stateMetadata.remove(stateKey);
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
}
