// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotWriter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.MerkleNodeStateAware;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * This class is responsible for maintaining references to the mutable state and the latest immutable state.
 * It also updates these references upon state signing.
 */
public class StateLifecycleManagerImpl<T extends MerkleNodeStateAware> implements StateLifecycleManager<T> {

    /**
     * Metrics for the state object
     */
    private final StateMetrics stateMetrics;

    /**
     * Metrics for the snapshot creation process
     */
    private final MerkleRootSnapshotMetrics snapshotMetrics;

    private final Time time;

    private final Metrics metrics;
    private final Function<VirtualMap, ? extends MerkleNodeState> stateSupplier;

    /**
     * reference to the state that reflects all known consensus transactions
     */
    private final AtomicReference<MerkleNodeState> stateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<MerkleNodeState> latestImmutableStateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<T> snapshotSource = new AtomicReference<>();

    /**
     * Constructor.
     *
     * @param metrics the metrics object to gather state metrics
     * @param time the time object
     */
    public StateLifecycleManagerImpl(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Function<VirtualMap, ? extends MerkleNodeState> stateSupplier) {
        requireNonNull(metrics);
        this.stateSupplier = stateSupplier;
        this.metrics = metrics;
        this.stateMetrics = new StateMetrics(metrics);
        this.snapshotMetrics = new MerkleRootSnapshotMetrics(metrics);
        this.time = time;
    }

    /**
     * Set the initial State. This method should only be on a startup of after a reconnect.
     *
     * @param state the initial state
     */
    public void initState(@NonNull final MerkleNodeState state, boolean onStartup) {
        requireNonNull(state);

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        if (onStartup && stateRef.get() != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

        copyAndUpdateStateRefs(state);
    }

    @Override
    public void setSnapshotSource(@NonNull T source) {
        requireNonNull(source);
        MerkleNodeState state = source.getState();
        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfMutable("state must be immutable");
        boolean result = snapshotSource.compareAndSet(null, source);
        assert result : "Snapshot source was already set";
    }

    @Override
    public T getSnapshotSource() {
        return snapshotSource.get();
    }

    @Override
    public MerkleNodeState getMutableState() {
        return stateRef.get();
    }

    @Override
    public MerkleNodeState getLatestImmutableState() {
        return latestImmutableStateRef.get();
    }

    @Override
    public MerkleNodeState copyMutableState() {
        final MerkleNodeState state = stateRef.get();
        copyAndUpdateStateRefs(state);
        return stateRef.get();
    }

    /**
     * Copies the provided state and updates both the latest immutable state and the mutable state reference.
     * @param state the state to copy and update references for
     */
    private void copyAndUpdateStateRefs(MerkleNodeState state) {
        // Create a fast copy so there is always an immutable state to
        // invoke handleTransaction on for pre-consensus transactions
        final long copyStart = System.nanoTime();
        // Create a fast copy
        final MerkleNodeState copy = state.copy();
        // Increment the reference count because this reference becomes the new value
        copy.getRoot().reserve();
        final long copyEnd = System.nanoTime();
        stateMetrics.stateCopyMicros((copyEnd - copyStart) * NANOSECONDS_TO_MICROSECONDS);
        // Set latest immutable first to prevent the newly immutable stateRoot from being deleted between setting the
        // stateRef and the latestImmutableState
        setLatestImmutableState(state);
        updateStateRef(copy);
    }

    /**
     * Sets the consensus state to the state provided. Must be mutable and have a reference count of at least 1.
     *
     * @param state a new mutable state
     */
    private void updateStateRef(final MerkleNodeState state) {
        final var currVal = stateRef.get();
        if (currVal != null && !currVal.isDestroyed()) {
            currVal.release();
        }
        // Do not increment the reference count because the state provided already has a reference count of at least
        // one to represent this reference and to prevent it from being deleted before this reference is set.
        stateRef.set(state);
    }

    private void setLatestImmutableState(final MerkleNodeState latestImmutableState) {
        final State currVal = latestImmutableStateRef.get();
        if (currVal != null && !currVal.isDestroyed()) {
            currVal.release();
        }
        latestImmutableState.getRoot().reserve();
        latestImmutableStateRef.set(latestImmutableState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createSnapshot(final @NonNull Path targetPath) {
        requireNonNull(time);
        requireNonNull(snapshotMetrics);
        VirtualMapState<?> state = (VirtualMapState<?>) snapshotSource.get().getState();
        state.throwIfMutable();
        state.throwIfDestroyed();
        final long startTime = time.currentTimeMillis();
        MerkleTreeSnapshotWriter.createSnapshot(state.virtualMap, targetPath, state.getRound());
        snapshotMetrics.updateWriteStateToDiskTimeMetric(time.currentTimeMillis() - startTime);
        snapshotSource.set(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNodeState loadSnapshot(@NonNull Path targetPath) {
        final MerkleNode root;
        try {
            root = MerkleTreeSnapshotReader.readStateFileData(targetPath).stateRoot();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!(root instanceof VirtualMap readVirtualMap)) {
            throw new IllegalStateException(
                    "Root should be a VirtualMap, but it is " + root.getClass().getSimpleName() + " instead");
        }

        final var mutableCopy = readVirtualMap.copy();
        mutableCopy.registerMetrics(metrics);
        readVirtualMap.release();
        readVirtualMap = mutableCopy;

        return stateSupplier.apply(readVirtualMap);
    }
}
