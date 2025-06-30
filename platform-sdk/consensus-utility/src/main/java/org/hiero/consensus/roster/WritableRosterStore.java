// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RosterState.Builder;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;

/**
 * Read-write implementation for accessing rosters states.
 */
public class WritableRosterStore extends ReadableRosterStoreImpl {
    public static final String ROSTER_KEY = "ROSTERS";
    public static final String ROSTER_STATES_KEY = "ROSTER_STATE";

    /**
     * The maximum number of active rosters to keep in the roster state.
     */
    public static final int MAXIMUM_ROSTER_HISTORY_SIZE = 2;

    /**
     * The roster state singleton. This is the state that holds the candidate roster hash and the list of pairs of
     * active roster hashes and the round number in which those rosters became active.
     */
    private final WritableSingletonState<RosterState> rosterState;

    private final WritableKVState<ProtoBytes, Roster> rosterMap;

    /**
     * Constructs a new {@link WritableRosterStore} instance.
     *
     * @param writableStates the readable states
     */
    public WritableRosterStore(@NonNull final WritableStates writableStates) {
        super(writableStates);
        requireNonNull(writableStates);
        this.rosterState = writableStates.getSingleton(ROSTER_STATES_KEY);
        this.rosterMap = writableStates.get(ROSTER_KEY);
    }

    /**
     * Adopts the candidate roster as the active roster, starting in the given round.
     *
     * @param roundNumber the round number in which the candidate roster should be adopted as the active roster
     */
    public void adoptCandidateRoster(final long roundNumber) {
        putActiveRoster(requireNonNull(getCandidateRoster()), roundNumber);
    }

    /**
     * Sets the candidate roster in state.
     * Setting the candidate roster indicates that this roster should be adopted as the active roster when required.
     *
     * @param candidateRoster a candidate roster to set. It must be a valid roster.
     */
    public void putCandidateRoster(@NonNull final Roster candidateRoster) {
        requireNonNull(candidateRoster);
        RosterValidator.validate(candidateRoster);

        final Bytes incomingCandidateRosterHash =
                RosterUtils.hash(candidateRoster).getBytes();

        // update the roster state/map
        final RosterState previousRosterState = rosterStateOrDefault();
        final Bytes previousCandidateRosterHash = previousRosterState.candidateRosterHash();
        final Builder newRosterStateBuilder =
                previousRosterState.copyBuilder().candidateRosterHash(incomingCandidateRosterHash);
        removeRoster(previousCandidateRosterHash);

        rosterState.put(newRosterStateBuilder.build());
        rosterMap.put(ProtoBytes.newBuilder().value(incomingCandidateRosterHash).build(), candidateRoster);
    }

    /**
     * Sets the Active roster.
     * This will be called to store a new Active Roster in the state.
     * The roster must be valid according to rules codified in {@link RosterValidator}.
     *
     * @param roster an active roster to set
     * @param round  the round number in which the roster became active.
     *               It must be a positive number greater than the round number of the current active roster.
     */
    public void putActiveRoster(@NonNull final Roster roster, final long round) {
        requireNonNull(roster);
        RosterValidator.validate(roster);

        final Bytes rosterHash = RosterUtils.hash(roster).getBytes();

        // update the roster state
        final RosterState previousRosterState = rosterStateOrDefault();
        final List<RoundRosterPair> roundRosterPairs = new LinkedList<>(previousRosterState.roundRosterPairs());
        if (!roundRosterPairs.isEmpty()) {
            final RoundRosterPair activeRosterPair = roundRosterPairs.getFirst();

            if (activeRosterPair.activeRosterHash().equals(rosterHash)) {
                // We're trying to set the exact same active roster, maybe even with the same roundNumber.
                // This may happen if, for whatever reason, roster updates come from different code paths.
                // This shouldn't be considered an error because the system wants to use the exact same
                // roster that is currently active anyway. So we silently ignore such a putActiveRoster request
                // because it's a no-op:
                return;
            }

            if (round < 0 || round <= activeRosterPair.roundNumber()) {
                throw new IllegalArgumentException("incoming round number = " + round
                        + " must be greater than the round number of the current active roster = "
                        + activeRosterPair.roundNumber() + ".");
            }
        }
        roundRosterPairs.addFirst(new RoundRosterPair(round, rosterHash));

        if (roundRosterPairs.size() > MAXIMUM_ROSTER_HISTORY_SIZE) {
            final RoundRosterPair lastRemovedRoster = roundRosterPairs.removeLast();
            removeRoster(lastRemovedRoster.activeRosterHash());

            // At this phase of the implementation, the roster state has a fixed size limit for active rosters.
            // Future implementations (e.g. DAB) can modify this.
            if (roundRosterPairs.size() > MAXIMUM_ROSTER_HISTORY_SIZE) {
                // additional safety check to ensure that the roster state does not contain more than set limit.
                throw new IllegalStateException(
                        "Active rosters in the Roster state cannot be more than  " + MAXIMUM_ROSTER_HISTORY_SIZE);
            }
        }

        final Builder newRosterStateBuilder = previousRosterState
                .copyBuilder()
                .candidateRosterHash(Bytes.EMPTY)
                .roundRosterPairs(roundRosterPairs);
        // since a new active roster is being set, the existing candidate roster is no longer valid
        // so we remove it if it meets removal criteria.
        removeRoster(previousRosterState.candidateRosterHash());
        rosterState.put(newRosterStateBuilder.build());
        rosterMap.put(ProtoBytes.newBuilder().value(rosterHash).build(), roster);
    }

    public void updateTransplantInProgress(final boolean inProgress) {
        final RosterState currentRosterState = rosterStateOrDefault();
        final Builder newRosterStateBuilder = currentRosterState.copyBuilder().transplantInProgress(inProgress);
        rosterState.put(newRosterStateBuilder.build());
    }

    /**
     * Reset the roster state to an empty list and remove all entries from the roster map.
     * This method is primarily intended to be used in CLI tools that may need to reset
     * the RosterService states to a vanilla state, for example to reproduce the genesis state.
     */
    public void resetRosters() {
        for (final RoundRosterPair roundRosterPair :
                requireNonNull(rosterState.get()).roundRosterPairs()) {
            rosterMap.remove(new ProtoBytes(roundRosterPair.activeRosterHash()));
        }
        rosterMap.remove(new ProtoBytes(requireNonNull(rosterState.get()).candidateRosterHash()));
        rosterState.put(RosterState.DEFAULT);
    }

    /**
     * Returns the roster state; or the default roster state if the roster state is not yet set at genesis.
     *
     * @return the roster state
     */
    @NonNull
    private RosterState rosterStateOrDefault() {
        RosterState state;
        return (state = rosterState.get()) == null ? RosterState.DEFAULT : state;
    }

    /**
     * Removes a roster from the roster map, but only if it doesn't match any of the active roster hashes in
     * the roster state. The check ensures we don't inadvertently remove a roster still in use.
     *
     * @param rosterHash the hash of the roster
     */
    private void removeRoster(@NonNull final Bytes rosterHash) {
        if (rosterHash.equals(Bytes.EMPTY)) {
            return;
        }
        final List<RoundRosterPair> activeRosterHistory = rosterStateOrDefault().roundRosterPairs();
        if (activeRosterHistory.stream()
                .noneMatch(rosterPair -> rosterPair.activeRosterHash().equals(rosterHash))) {
            this.rosterMap.remove(ProtoBytes.newBuilder().value(rosterHash).build());
        }
    }
}
