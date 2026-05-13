// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.hiero.consensus.roster.WritableRosterStore.MAXIMUM_ROSTER_HISTORY_SIZE;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A utility class for roster operations that depend on the State API.
 * This class is separate from RosterUtils to isolate dependencies on swirlds-state-api.
 */
public final class RosterStateUtils {
    private RosterStateUtils() {}

    /**
     * Creates the Roster History to be used by Platform.
     *
     * @param state the state containing the active roster history.
     * @return the roster history if roster store contains active rosters, otherwise NullPointerException is thrown.
     */
    @NonNull
    public static RosterHistory createRosterHistory(@NonNull final State state) {
        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME));
        return createRosterHistory(rosterStore);
    }

    /**
     * Creates the effective {@link RosterHistory} that would result from adopting the given candidate roster in the
     * given round, without changing the underlying state.
     *
     * @param state the state containing the active roster history
     * @param candidateRoster the candidate roster to preview as adopted
     * @param roundNumber the round number where the candidate roster would become active
     * @return the effective roster history after the candidate roster adoption
     */
    @NonNull
    public static RosterHistory createRosterHistoryWithCandidateAdoption(
            @NonNull final State state, @NonNull final Roster candidateRoster, final long roundNumber) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(candidateRoster);
        RosterValidator.validate(candidateRoster);
        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME));
        final List<RoundRosterPair> currentRosterHistory = rosterStore.getRosterHistory();
        final var candidateRosterHash = RosterUtils.hash(candidateRoster).getBytes();
        final var activeRosterPair = currentRosterHistory.getFirst();
        if (activeRosterPair.activeRosterHash().equals(candidateRosterHash)) {
            return createRosterHistory(rosterStore);
        }
        if (roundNumber < 0 || roundNumber <= activeRosterPair.roundNumber()) {
            throw new IllegalArgumentException("incoming round number = " + roundNumber
                    + " must be greater than the round number of the current active roster = "
                    + activeRosterPair.roundNumber() + ".");
        }

        final List<RoundRosterPair> effectiveRosterHistory = new ArrayList<>(MAXIMUM_ROSTER_HISTORY_SIZE);
        effectiveRosterHistory.add(new RoundRosterPair(roundNumber, candidateRosterHash));
        for (final var pair : currentRosterHistory) {
            if (effectiveRosterHistory.size() == MAXIMUM_ROSTER_HISTORY_SIZE) {
                break;
            }
            effectiveRosterHistory.add(pair);
        }

        final Map<Bytes, Roster> rosterMap = new HashMap<>();
        rosterMap.put(candidateRosterHash, candidateRoster);
        for (final RoundRosterPair pair : effectiveRosterHistory) {
            rosterMap.computeIfAbsent(pair.activeRosterHash(), hash -> Objects.requireNonNull(rosterStore.get(hash)));
        }
        return new RosterHistory(effectiveRosterHistory, rosterMap);
    }

    private static RosterHistory createRosterHistory(@NonNull final ReadableRosterStore rosterStore) {
        final List<RoundRosterPair> roundRosterPairs = rosterStore.getRosterHistory();
        final Map<Bytes, Roster> rosterMap = new HashMap<>();
        for (final RoundRosterPair pair : roundRosterPairs) {
            rosterMap.put(pair.activeRosterHash(), Objects.requireNonNull(rosterStore.get(pair.activeRosterHash())));
        }
        return new RosterHistory(roundRosterPairs, rosterMap);
    }

    /**
     * Sets the active Roster in a given State.
     *
     * @param state a state to set a Roster in
     * @param roster a Roster to set as active
     * @param round a round number since which the roster is considered active
     */
    public static void setActiveRoster(@NonNull final State state, @NonNull final Roster roster, final long round) {
        final WritableStates writableStates = state.getWritableStates(RosterStateId.SERVICE_NAME);
        final WritableRosterStore writableRosterStore = new WritableRosterStore(writableStates);
        writableRosterStore.putActiveRoster(roster, round);
        ((CommittableWritableStates) writableStates).commit();
    }
}
