// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;
import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.StateLifecycleManager;
import com.swirlds.state.lifecycle.StateLifecycleMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This is a default implementation of {@link StateLifecycleManager} that manages the lifecycle of a state object.
 * This class is thread-safe.
 *
 * @param <T> the type of the state object
 */
public class StateLifecycleManagerImpl<T extends MerkleStateRoot<T>> implements StateLifecycleManager<T> {
    /**
     * reference to the state that reflects all known consensus transactions
     */
    private T currentState;

    private final StateLifecycleMetrics stateLifecycleMetrics;

    public StateLifecycleManagerImpl(@NonNull final Metrics metrics) {
        requireNonNull(metrics);
        this.stateLifecycleMetrics = new StateLifecycleMetrics(metrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setInitialState(@NonNull final T state) {
        setInitialState(state, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void overwriteExistingState(@NonNull final T state) {
        setInitialState(state, true);
    }

    private void setInitialState(@NonNull final T state, final boolean overwrite) {
        requireNonNull(state);

        if (!overwrite && currentState != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        fastCopyAndUpdateRefs(state);
    }

    private void fastCopyAndUpdateRefs(final T state) {
        final long copyStart = System.nanoTime();

        // Create a fast copy
        final T copy = state.copy();
        // Increment the reference count because this reference becomes the new value
        copy.reserve();

        final long copyEnd = System.nanoTime();

        stateLifecycleMetrics.stateCopyMicros((copyEnd - copyStart) * NANOSECONDS_TO_MICROSECONDS);

        if (currentState != null) {
            currentState.release();
        }
        this.currentState = copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized T getMutableState() {
        return currentState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized T copyMutableState() {
        T currentState = this.currentState;
        // creation of a copy will reduce the reference count of the original state, but we still want the state to be around
        currentState.reserve();
        fastCopyAndUpdateRefs(currentState);
        // returning the original state that became immutable
        return currentState;
    }
}
