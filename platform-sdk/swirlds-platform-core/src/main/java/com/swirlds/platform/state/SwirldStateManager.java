// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.state.SwirldStateManagerUtils.fastCopy;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.StateMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is responsible for maintaining references to the mutable state and the latest immutable state.
 * It also updates these references upon state signing.
 */
public class SwirldStateManager {

    /**
     * Metrics for the state object
     */
    private final StateMetrics stateMetrics;

    /**
     * reference to the state that reflects all known consensus transactions
     */
    private final AtomicReference<MerkleNodeState> stateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<MerkleNodeState> latestImmutableState = new AtomicReference<>();

    /**
     * The current software version.
     */
    private final SemanticVersion softwareVersion;

    private final PlatformStateFacade platformStateFacade;

    /**
     * Constructor.
     *
     * @param platformContext       the platform context
     * @param roster                the current roster
     * @param softwareVersion       the current software version
     */
    public SwirldStateManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final PlatformStateFacade platformStateFacade) {

        requireNonNull(platformContext);
        requireNonNull(roster);
        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.stateMetrics = new StateMetrics(platformContext.getMetrics());
        this.softwareVersion = requireNonNull(softwareVersion);
    }

    /**
     * Set the initial State for the platform. This method should only be called once.
     *
     * @param state the initial state
     */
    public void setState(@NonNull final MerkleNodeState state, boolean onInit) {
        requireNonNull(state);

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        if (onInit && stateRef.get() != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

        // Create a fast copy so there is always an immutable state to
        // invoke handleTransaction on for pre-consensus transactions
        fastCopyAndUpdateRefs(state);
    }

    /**
     * Returns the consensus state. The consensus state could become immutable at any time. Modifications must not be
     * made to the returned state.
     */
    public MerkleNodeState getConsensusState() {
        return stateRef.get();
    }

    private void fastCopyAndUpdateRefs(final MerkleNodeState state) {
        final MerkleNodeState newState = fastCopy(state, stateMetrics, softwareVersion, platformStateFacade);

        // Set latest immutable first to prevent the newly immutable stateRoot from being deleted between setting the
        // stateRef and the latestImmutableState
        setLatestImmutableState(state);
        updateStateRef(newState);
    }

    /**
     * Sets the consensus state to the state provided. Must be mutable and have a reference count of at least 1.
     *
     * @param state a new mutable state
     */
    private void updateStateRef(final MerkleNodeState state) {
        final var currVal = stateRef.get();
        if (currVal != null) {
            currVal.release();
        }
        // Do not increment the reference count because the state provided already has a reference count of at least
        // one to represent this reference and to prevent it from being deleted before this reference is set.
        stateRef.set(state);
    }

    private void setLatestImmutableState(final MerkleNodeState immutableState) {
        final State currVal = latestImmutableState.get();
        if (currVal != null) {
            currVal.release();
        }
        immutableState.getRoot().reserve();
        latestImmutableState.set(immutableState);
    }

    /**
     * <p>Updates the state to a fast copy of itself and returns a reference to the previous state to be used for
     * signing. The reference count of the previous state returned by this is incremented to prevent it from being
     * garbage collected until it is put in a signed state, so callers are responsible for decrementing the reference
     * count when it is no longer needed.</p>
     *
     * <p>Consensus event handling will block until this method returns. Pre-consensus
     * event handling may or may not be blocked depending on the implementation.</p>
     *
     * @return a copy of the state to use for the next signed state
     * @see State#copy()
     */
    public MerkleNodeState getStateForSigning() {
        fastCopyAndUpdateRefs(stateRef.get());
        return latestImmutableState.get();
    }
}
