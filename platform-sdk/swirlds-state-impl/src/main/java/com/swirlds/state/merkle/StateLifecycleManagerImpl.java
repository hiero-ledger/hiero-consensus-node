// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * This class is responsible for maintaining references to the mutable state and the latest immutable state.
 * It also updates these references upon request.
 * <p>
 * Upon construction, a genesis {@link VirtualMapStateImpl} is created eagerly and is immediately available
 * via {@link #getMutableState()}. If the node is restarting from a saved state, calling
 * {@link #loadSnapshot(Path)} will replace the genesis state with the loaded state. If the node is
 * reconnecting, calling {@link #initWithState(VirtualMapState)} will replace the current state with
 * the reconnect state.
 * <p>
 * This implementation is NOT thread-safe. However, it provides the following guarantees:
 * <ul>
 * <li>After construction, calls to {@link #getMutableState()} and {@link #getLatestImmutableState()} will always return
 * non-null values.</li>
 * <li>After {@link #copyMutableState()}, the updated mutable state will be visible and available to all threads via {@link #getMutableState()}, and
 * the updated latest immutable state will be visible and available to all threads via {@link #getLatestImmutableState()}.</li>
 * </ul>
 *
 * <b>Important:</b> {@link #copyMutableState()} is NOT supposed to be called from multiple threads.
 * It only provides the happens-before guarantees that are described above.
 */
public class StateLifecycleManagerImpl implements StateLifecycleManager<VirtualMapState, VirtualMap> {

    private static final Logger log = LogManager.getLogger(StateLifecycleManagerImpl.class);

    /**
     * Metrics for the state object
     */
    private final StateMetrics stateMetrics;

    /**
     * Metrics for the snapshot creation process
     */
    private final MerkleRootSnapshotMetrics snapshotMetrics;

    /**
     * The object for time measurements
     */
    private final Time time;

    /**
     * The metrics registry
     */
    private final Metrics metrics;

    /**
     * reference to the state that reflects all known consensus transactions
     */
    private final AtomicReference<VirtualMapState> stateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<VirtualMapState> latestImmutableStateRef = new AtomicReference<>();

    @NonNull
    private final Configuration configuration;

    /**
     * Constructor. Creates an initial genesis state eagerly, which is immediately available via
     * {@link #getMutableState()}.
     *
     * @param metrics the metrics object to gather state metrics
     * @param time the time object
     * @param configuration the configuration
     */
    public StateLifecycleManagerImpl(
            @NonNull final Metrics metrics, @NonNull final Time time, @NonNull final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.stateMetrics = new StateMetrics(metrics);
        this.snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);

        // Eagerly create a genesis state so getMutableState() is always valid after construction.
        // If the node is restarting from a snapshot, loadSnapshot() will replace this genesis state.
        final VirtualMapStateImpl genesisState = new VirtualMapStateImpl(configuration, metrics);
        genesisState.getRoot().reserve();
        stateRef.set(genesisState);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public VirtualMapState getMutableState() {
        return stateRef.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public VirtualMapState getLatestImmutableState() {
        return latestImmutableStateRef.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public VirtualMapState copyMutableState() {
        final VirtualMapState state = stateRef.get();
        copyAndUpdateStateRefs(state);
        return stateRef.get();
    }

    /**
     * Copies the provided state and updates both the latest immutable state and the mutable state reference.
     *
     * @param stateToCopy the state to copy and update references for
     */
    private void copyAndUpdateStateRefs(final @NonNull VirtualMapState stateToCopy) {
        final long copyStart = System.nanoTime();
        final VirtualMapState newMutableState = ((VirtualMapStateImpl)stateToCopy).copy();
        // Increment the reference count because this reference becomes the new value
        newMutableState.getRoot().reserve();
        final long copyEnd = System.nanoTime();
        stateMetrics.stateCopyMicros((copyEnd - copyStart) * NANOSECONDS_TO_MICROSECONDS);
        // releasing previous immutable previousMutableState
        final State previousImmutableState = latestImmutableStateRef.get();
        if (previousImmutableState != null) {
            assert !previousImmutableState.isDestroyed();
            if (previousImmutableState.isDestroyed()) {
                log.error(EXCEPTION.getMarker(), "previousImmutableState is in destroyed state", new Exception());
            } else {
                previousImmutableState.release();
            }
        }
        stateToCopy.getRoot().reserve();
        latestImmutableStateRef.set(stateToCopy);
        final VirtualMapState previousMutableState = stateRef.get();
        if (previousMutableState != null) {
            assert !previousMutableState.isDestroyed();
            if (previousMutableState.isDestroyed()) {
                log.error(EXCEPTION.getMarker(), "previousMutableState is in destroyed state", new Exception());
            } else {
                previousMutableState.release();
            }
        }
        // Do not increment the reference count because the stateToCopy provided already has a reference count of at
        // least one to represent this reference and to prevent it from being deleted before this reference is set.
        stateRef.set(newMutableState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createSnapshot(final @NonNull VirtualMapState state, final @NonNull Path targetPath) {
        state.throwIfMutable();
        state.throwIfDestroyed();
        final long startTime = time.currentTimeMillis();
        try {
            log.info(STATE_TO_DISK.getMarker(), "Creating a snapshot on demand in {} for {}", targetPath, state);
            VirtualMap virtualMap = state.getRoot();
            virtualMap.createSnapshot(targetPath);
            log.info(
                    STATE_TO_DISK.getMarker(),
                    "Successfully created a snapshot on demand in {}  for {}",
                    targetPath,
                    state);
        } catch (final Throwable e) {
            log.error(
                    EXCEPTION.getMarker(), "Unable to write a snapshot on demand for {} to {}.", state, targetPath, e);
        }

        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Loads a {@link VirtualMap} from the given directory, wraps it in a {@link VirtualMapStateImpl},
     * and replaces the current mutable state (including the eagerly-created genesis state) with the loaded state.
     * The loaded state is immediately available via {@link #getMutableState()} after this call returns.
     *
     * @return the hash of the original immutable snapshot as it was stored on disk (captured before the mutable copy
     *         is made)
     */
    @NonNull
    @Override
    public Hash loadSnapshot(@NonNull final Path targetPath) throws IOException {
        log.info(STARTUP.getMarker(), "Loading snapshot from disk {}", targetPath);
        final VirtualMap virtualMap = VirtualMap.loadFromDirectory(
                targetPath, configuration, () -> new MerkleDbDataSourceBuilder(configuration));

        // Capture the hash of the original immutable snapshot before releasing it
        final Hash originalHash = virtualMap.getHash();

        final VirtualMap mutableCopy = virtualMap.copy();
        virtualMap.release();

        // VirtualMapStateImpl constructor calls registerMetrics internally
        final VirtualMapStateImpl loadedState = new VirtualMapStateImpl(mutableCopy, metrics);
        if (latestImmutableStateRef.get() != null
                && latestImmutableStateRef.get().isDestroyed()) {
            latestImmutableStateRef.set(null);
        }

        if (stateRef.get() != null && stateRef.get().isDestroyed()) {
            stateRef.set(null);
        }

        copyAndUpdateStateRefs(loadedState);

        return originalHash;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Wraps the provided {@link VirtualMap} (received from a reconnect peer) in a {@link VirtualMapStateImpl}
     * and re-initializes the manager with it, replacing the current mutable state.
     */
    @Override
    public void initWithState(@NonNull final VirtualMapState state) {
        requireNonNull(state);
        copyAndUpdateStateRefs(state);
    }


    /**
     *  {@inheritDoc}
     */
    @Override
    public VirtualMapState createStateFrom(@NonNull VirtualMap rootNode) {
        return new VirtualMapStateImpl(rootNode, metrics);
    }
}
