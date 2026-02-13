// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible for maintaining references to the mutable state and the latest immutable state.
 * It also updates these references upon request.
 * This implementation is NOT thread-safe. However, it provides the following guarantees:
 * <ul>
 * <li>After {@link #initState(MerkleNodeState)}, calls to {@link #getMutableState()} and {@link #getLatestImmutableState()} will always return
 * non-null values.</li>
 * <li>After {@link #copyMutableState()}, the updated mutable state will be visible and available to all threads via {@link #getMutableState()}, and
 * the updated latest immutable state will be visible and available to all threads via {@link #getLatestImmutableState()}.</li>
 * </ul>
 *
 * <b>Important:</b> {@link #initState(MerkleNodeState)} and {@link #copyMutableState()} are NOT supposed to be called from multiple threads.
 * They only provide the happens-before guarantees that are described above.
 */
public class StateLifecycleManagerImpl implements StateLifecycleManager {

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
     * A factory object to create an instance of a class implementing {@link MerkleNodeState} from a {@link VirtualMap}
     */
    private final Function<VirtualMap, MerkleNodeState> stateSupplier;

    /**
     * reference to the state that reflects all known consensus transactions
     */
    private final AtomicReference<MerkleNodeState> stateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<MerkleNodeState> latestImmutableStateRef = new AtomicReference<>();

    @NonNull
    private final Configuration configuration;

    /**
     * Constructor.
     *
     * @param metrics the metrics object to gather state metrics
     * @param time the time object
     * @param stateSupplier a factory object to create an instance of a class implementing {@link MerkleNodeState} from a {@link VirtualMap}
     */
    public StateLifecycleManagerImpl(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Function<VirtualMap, MerkleNodeState> stateSupplier,
            @NonNull final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.stateSupplier = stateSupplier;
        this.stateMetrics = new StateMetrics(metrics);
        this.snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNodeState createStateFrom(@NonNull MerkleNode rootNode) {
        return stateSupplier.apply((VirtualMap) rootNode);
    }

    /**
     * {@inheritDoc}
     */
    public void initState(@NonNull final MerkleNodeState state) {
        requireNonNull(state);

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        if (stateRef.get() != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

        copyAndUpdateStateRefs(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initStateOnReconnect(@NonNull MerkleNodeState state) {
        requireNonNull(state);
        state.throwIfDestroyed("rootNode must not be destroyed");
        state.throwIfImmutable("rootNode must be mutable");

        copyAndUpdateStateRefs(state);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNodeState getMutableState() {
        final MerkleNodeState mutableState = stateRef.get();
        if (mutableState == null) {
            throw new IllegalStateException("StateLifecycleManager has not been initialized.");
        }
        return mutableState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MerkleNodeState getLatestImmutableState() {
        final MerkleNodeState latestImmutableState = latestImmutableStateRef.get();
        if (latestImmutableState == null) {
            throw new IllegalStateException("StateLifecycleManager has not been initialized.");
        }
        return latestImmutableState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MerkleNodeState copyMutableState() {
        final MerkleNodeState state = stateRef.get();
        copyAndUpdateStateRefs(state);
        return stateRef.get();
    }

    /**
     * Copies the provided to and updates both the latest immutable to and the mutable to reference.
     * @param stateToCopy the state to copy and update references for
     */
    private void copyAndUpdateStateRefs(final @NonNull MerkleNodeState stateToCopy) {
        final long copyStart = System.nanoTime();
        final MerkleNodeState newMutableState = stateToCopy.copy();
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
        final MerkleNodeState previousMutableState = stateRef.get();
        if (previousMutableState != null) {
            assert !previousMutableState.isDestroyed();
            if (previousMutableState.isDestroyed()) {
                log.error(EXCEPTION.getMarker(), "previousMutableState is in destroyed state", new Exception());
            } else {
                previousMutableState.release();
            }
        }
        // Do not increment the reference count because the stateToCopy provided already has a reference count of at
        // least
        // one to represent this reference and to prevent it from being deleted before this reference is set.
        stateRef.set(newMutableState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createSnapshot(final @NonNull MerkleNodeState state, final @NonNull Path targetPath) {
        state.throwIfMutable();
        state.throwIfDestroyed();
        final long startTime = time.currentTimeMillis();
        try {
            log.info(STATE_TO_DISK.getMarker(), "Creating a snapshot on demand in {} for {}", targetPath, state);
            VirtualMap virtualMap = (VirtualMap) state.getRoot();
            virtualMap.createSnapshot(targetPath);
            log.info(
                    STATE_TO_DISK.getMarker(),
                    "Successfully created a snapshot on demand in {}  for {}",
                    targetPath,
                    state);
        } catch (final Throwable e) {
            log.error(
                    EXCEPTION.getMarker(),
                    "Unable to write a snapshot on demand for {} to {}: {}",
                    state,
                    targetPath,
                    e);
        }

        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Void> createSnapshotAsync(final @NonNull MerkleNodeState state, final @NonNull Path targetPath) {
        state.throwIfMutable();
        state.throwIfDestroyed();

        // includes pipeline queue wait, not just snapshot I/O
        final long startTime = time.currentTimeMillis();
        log.info(STATE_TO_DISK.getMarker(), "Creating a snapshot on demand (async) in {} for {}", targetPath, state);

        final VirtualMap virtualMap = (VirtualMap) state.getRoot();
        return virtualMap.createSnapshotAsync(targetPath).whenComplete((result, error) -> {
            if (error != null) {
                log.error(
                        EXCEPTION.getMarker(),
                        "Unable to write a snapshot on demand (async) for {} to {}: {}",
                        state,
                        targetPath,
                        error);
            } else {
                log.info(
                        STATE_TO_DISK.getMarker(),
                        "Successfully created a snapshot on demand (async) in {} for {}",
                        targetPath,
                        state);
            }
            snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
        });
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNodeState loadSnapshot(@NonNull final Path targetPath) throws IOException {
        final VirtualMap virtualMap;
        log.info(STARTUP.getMarker(), "Loading snapshot from disk {}", targetPath);
        virtualMap = VirtualMap.loadFromDirectory(
                targetPath, configuration, () -> new MerkleDbDataSourceBuilder(configuration));

        final VirtualMap mutableCopy = virtualMap.copy();
        mutableCopy.registerMetrics(metrics);
        virtualMap.release();

        return stateSupplier.apply(mutableCopy);
    }
}
