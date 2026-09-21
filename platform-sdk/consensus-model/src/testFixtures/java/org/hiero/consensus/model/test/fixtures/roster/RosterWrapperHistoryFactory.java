// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.roster;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.roster.RosterWrapperHistory;
import org.hiero.consensus.model.roster.RosterWrapperHistory.Entry;

/**
 * Factory for creating RosterWrapperHistory instances.
 */
public class RosterWrapperHistoryFactory {

    private RosterWrapperHistoryFactory() {}

    /**
     * Create a RosterWrapperHistory with a single roster.
     *
     * @param startingRound the starting round for the roster
     * @param roster the roster to include in the history
     * @return a {@link RosterWrapperHistory} instance
     */
    @NonNull
    public static RosterWrapperHistory createRosterWrapperHistory(
            final long startingRound, @NonNull final RosterWrapper roster) {
        return new RosterWrapperHistory(List.of(new Entry(startingRound, roster)));
    }

    /**
     * Create a RosterWrapperHistory with two rosters.
     *
     * @param startingRound1 the starting round for the first roster
     * @param roster1 the first roster to include in the history
     * @param startingRound2 the starting round for the second roster
     * @param roster2 the second roster to include in the history
     * @return a {@link RosterWrapperHistory} instance
     */
    @NonNull
    public static RosterWrapperHistory createRosterWrapperHistory(
            final long startingRound1,
            @NonNull final RosterWrapper roster1,
            final long startingRound2,
            @NonNull final RosterWrapper roster2) {
        return new RosterWrapperHistory(
                List.of(new Entry(startingRound1, roster1), new Entry(startingRound2, roster2)));
    }
}
