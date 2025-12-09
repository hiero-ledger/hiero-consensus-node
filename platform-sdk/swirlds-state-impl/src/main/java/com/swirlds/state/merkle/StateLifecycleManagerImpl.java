// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotWriter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible for maintaining references to the mutable state and the latest immutable state.
 * It also updates these references upon request.
 * This implementation is NOT thread-safe. However, it provides the following guarantees:
 * <ul>
 * <li>After {@link #initState(MerkleNodeState, boolean)}, calls to {@link #getMutableState()} and {@link #getLatestImmutableState()} will always return
 * non-null values.</li>
 * <li>After {@link #copyMutableState()}, the updated mutable state will be visible and available to all threads via {@link #getMutableState()}, and
 * the updated latest immutable state will be visible and available to all threads via {@link #getLatestImmutableState()}.</li>
 * </ul>
 *
 * <b>Important:</b> {@link #initState(MerkleNodeState, boolean)} and {@link #copyMutableState()} are NOT supposed to be called from multiple threads.
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
            @NonNull final Function<VirtualMap, MerkleNodeState> stateSupplier) {
        requireNonNull(metrics);
        requireNonNull(time);
        this.stateSupplier = stateSupplier;
        this.metrics = metrics;
        this.stateMetrics = new StateMetrics(metrics);
        this.snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);
        this.time = time;
    }

    /**
     * {@inheritDoc}
     */
    public void initState(@NonNull final MerkleNodeState state, final boolean onStartup) {
        requireNonNull(state);

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        if (onStartup && stateRef.get() != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

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
                log.error(EXCEPTION.getMarker(), "previousImmutableState is in destroyed state");
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
                log.error(EXCEPTION.getMarker(), "previousImmutableState is in destroyed state");
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
        MerkleTreeSnapshotWriter.createSnapshot(state.getRoot(), targetPath);
        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNodeState loadSnapshot(@NonNull final Path targetPath) {
        final MerkleNode root;
        try {
            root = MerkleTreeSnapshotReader.readStateFileData(targetPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!(root instanceof VirtualMap readVirtualMap)) {
            throw new IllegalStateException(
                    "Root should be a VirtualMap, but it is " + root.getClass().getSimpleName() + " instead");
        }

        final VirtualMap mutableCopy = readVirtualMap.copy();
        mutableCopy.registerMetrics(metrics);
        readVirtualMap.release();
        readVirtualMap = mutableCopy;

        return stateSupplier.apply(readVirtualMap);
    }
}
