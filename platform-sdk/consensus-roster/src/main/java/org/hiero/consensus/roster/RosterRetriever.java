// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A utility class to help retrieve a Roster instance from the state.
 */
public final class RosterRetriever {

    private RosterRetriever() {}

    private static final String ROSTER_SERVICE = "RosterService";

    /**
     * Retrieve an active Roster from the state for a given round.
     * <p>
     * This method first checks the RosterState/RosterMap entities,
     * and if they contain a roster for the given round, then returns it.
     * If there's not a roster defined for a given round, then null is returned.
     *
     * @return an active Roster for the given round of the state, or null
     */
    @Nullable
    public static Roster retrieveActive(@NonNull final State state, final long round) {
        return retrieveInternal(state, getActiveRosterHash(state, round));
    }

    /**
     * Retrieve a hash of the active roster for a given round of the state,
     * or null if the roster is unknown for that round.
     * A roster may be unknown if the RosterState hasn't been populated yet,
     * or the given round of the state predates the implementation of the Roster.
     *
     * @param state a state
     * @param round a round number
     * @return a Bytes object with the roster hash, or null
     */
    @Nullable
    public static Bytes getActiveRosterHash(@NonNull final State state, final long round) {
        final ReadableSingletonState<RosterState> rosterState =
                state.getReadableStates(ROSTER_SERVICE).getSingleton(RosterStateId.ROSTER_STATE_STATE_ID);
        // replace with binary search when/if the list size becomes unreasonably large (100s of entries or more)
        final var roundRosterPairs = requireNonNull(rosterState.get()).roundRosterPairs();
        for (final var roundRosterPair : roundRosterPairs) {
            if (roundRosterPair.roundNumber() <= round) {
                return roundRosterPair.activeRosterHash();
            }
        }
        return null;
    }

    @Nullable
    private static Roster retrieveInternal(@NonNull final State state, @Nullable final Bytes activeRosterHash) {
        if (activeRosterHash != null) {
            final ReadableKVState<ProtoBytes, Roster> rosterMap =
                    state.getReadableStates(ROSTER_SERVICE).get(RosterStateId.ROSTERS_STATE_ID);
            return rosterMap.get(new ProtoBytes(activeRosterHash));
        }
        return null;
    }
}
