// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.platform.state.SwirldStateManagerUtils.fastCopy;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.freeze.FreezePeriodChecker;
import com.swirlds.platform.metrics.StateMetrics;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Manages all interactions with the state object required by {@link ConsensusStateEventHandler}.
 */
public class SwirldStateManager implements FreezePeriodChecker {

    /**
     * Stats relevant to the state operations.
     */
    private final StateMetrics stats;

    /**
     * reference to the state that reflects all known consensus transactions
     */
    private final AtomicReference<MerkleNodeState> stateRef = new AtomicReference<>();

    /**
     * The most recent immutable state. No value until the first fast copy is created.
     */
    private final AtomicReference<MerkleNodeState> latestImmutableState = new AtomicReference<>();

    /**
     * Handle transactions by applying them to a state
     */
    private final TransactionHandler transactionHandler;

    /**
     * The current software version.
     */
    private final SemanticVersion softwareVersion;

    private final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;

    private final PlatformStateFacade platformStateFacade;

    /**
     * Constructor.
     *
     * @param platformContext       the platform context
     * @param roster                the current roster
     * @param selfId                this node's id
     * @param statusActionSubmitter enables submitting platform status actions
     * @param softwareVersion       the current software version
     * @param consensusStateEventHandler       the state lifecycles
     */
    public SwirldStateManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            @NonNull final PlatformStateFacade platformStateFacade) {

        requireNonNull(platformContext);
        requireNonNull(roster);
        requireNonNull(selfId);
        requireNonNull(consensusStateEventHandler);

        this.platformStateFacade = requireNonNull(platformStateFacade);
        this.consensusStateEventHandler = consensusStateEventHandler;
        this.stats = new StateMetrics(platformContext.getMetrics());
        requireNonNull(statusActionSubmitter);
        this.softwareVersion = requireNonNull(softwareVersion);
        this.transactionHandler = new TransactionHandler(selfId, stats);
    }

    /**
     * Set the initial State for the platform. This method should only be called once.
     *
     * @param state the initial state
     */
    public void setInitialState(@NonNull final MerkleNodeState state) {
        requireNonNull(state);

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        if (stateRef.get() != null) {
            throw new IllegalStateException("Attempt to set initial state when there is already a state reference.");
        }

        // Create a fast copy so there is always an immutable state to
        // invoke handleTransaction on for pre-consensus transactions
        fastCopyAndUpdateRefs(state);
    }

    /**
     * Handles the events in a consensus round. Implementations are responsible for invoking
     * {@link ConsensusStateEventHandler#onHandleConsensusRound(Round, MerkleNodeState, Consumer)} .
     *
     * @param round the round to handle
     */
    public Queue<ScopedSystemTransaction<StateSignatureTransaction>> handleConsensusRound(final ConsensusRound round) {
        final MerkleNodeState state = stateRef.get();
        return transactionHandler.handleRound(round, consensusStateEventHandler, state);
    }

    /**
     * Seals the platform's state changes for the given round.
     * @param round the round to seal
     */
    public boolean sealConsensusRound(@NonNull final Round round) {
        requireNonNull(round);
        final MerkleNodeState state = stateRef.get();
        return consensusStateEventHandler.onSealConsensusRound(round, state);
    }

    /**
     * Returns the consensus state. The consensus state could become immutable at any time. Modifications must not be
     * made to the returned state.
     */
    public MerkleNodeState getConsensusState() {
        return stateRef.get();
    }

    /**
     * Invoked when a signed state is about to be created for the current freeze period.
     * <p>
     * Invoked only by the consensus handling thread, so there is no chance of the state being modified by a concurrent
     * thread.
     * </p>
     */
    public void savedStateInFreezePeriod() {
        // set current DualState's lastFrozenTime to be current freezeTime
        platformStateFacade.updateLastFrozenTime(stateRef.get());
    }

    /**
     * Loads all necessary data from the {@code reservedSignedState}.
     *
     * @param signedState the signed state to load
     */
    public void loadFromSignedState(@NonNull final SignedState signedState) {
        MerkleNodeState state = signedState.getState();

        state.throwIfDestroyed("state must not be destroyed");
        state.throwIfImmutable("state must be mutable");

        fastCopyAndUpdateRefs(state);
    }

    private void fastCopyAndUpdateRefs(final MerkleNodeState state) {
        final MerkleNodeState newState = fastCopy(state, stats, softwareVersion, platformStateFacade);

        // Set latest immutable first to prevent the newly immutable stateRoot from being deleted between setting the
        // stateRef and the latestImmutableState
        setLatestImmutableState(state);
        setState(newState);
    }

    /**
     * Sets the consensus state to the state provided. Must be mutable and have a reference count of at least 1.
     *
     * @param state a new mutable state
     */
    private void setState(final MerkleNodeState state) {
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
     * {@inheritDoc}
     */
    @Override
    public boolean isInFreezePeriod(final Instant timestamp) {
        return PlatformStateFacade.isInFreezePeriod(
                timestamp,
                platformStateFacade.freezeTimeOf(getConsensusState()),
                platformStateFacade.lastFrozenTimeOf(getConsensusState()));
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
