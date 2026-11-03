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
     * @param firstActiveRound the round in which the roster becomes active
     * @param roster the roster to include in the history
     * @return a {@link RosterWrapperHistory} instance
     */
    @NonNull
    public static RosterWrapperHistory createRosterWrapperHistory(
            final long firstActiveRound, @NonNull final RosterWrapper roster) {
        return new RosterWrapperHistory(List.of(new Entry(firstActiveRound, roster)));
    }

    /**
     * Create a RosterWrapperHistory with two rosters.
     *
     * @param firstActiveRound1 the round in which first roster becomes active
     * @param roster1 the first roster to include in the history
     * @param firstActiveRound2 the round in which second roster becomes active
     * @param roster2 the second roster to include in the history
     * @return a {@link RosterWrapperHistory} instance
     */
    @NonNull
    public static RosterWrapperHistory createRosterWrapperHistory(
            final long firstActiveRound1,
            @NonNull final RosterWrapper roster1,
            final long firstActiveRound2,
            @NonNull final RosterWrapper roster2) {
        return new RosterWrapperHistory(
                List.of(new Entry(firstActiveRound1, roster1), new Entry(firstActiveRound2, roster2)));
    }
}
