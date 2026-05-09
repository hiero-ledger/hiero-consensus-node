// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

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
        return new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME))
                .createRosterHistory();
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
