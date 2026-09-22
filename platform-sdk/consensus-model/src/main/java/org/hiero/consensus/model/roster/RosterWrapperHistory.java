// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;

/**
 * A {@code RosterWrapperHistory} is a utility class that allows for efficient lookup of the appropriate {@link RosterWrapper} for a given round number.
 */
public class RosterWrapperHistory {

    /**
     * A record representing an entry in the roster history, consisting a {@link RosterWrapper} and the round in which
     * the roster becomes active.
     *
     * @param firstActiveRound the round number at which the roster becomes active
     * @param roster the {@link RosterWrapper} that is active starting from the specified round
     */
    public record Entry(long firstActiveRound, @NonNull RosterWrapper roster) {}

    /**
     *  The latest first active round, i.e., the round in which the currently active roster became active.
     *  Used for optimization: hot path needs no array access.
     */
    private final long latestFirstActiveRound;

    /** The active roster. Used for optimization: hot path needs no array access. */
    @NonNull
    private final RosterWrapper activeRoster;

    /** The first active rounds of all rosters, in strictly descending order. */
    @NonNull
    private final long[] firstActiveRounds;

    /** The rosters corresponding to the first active rounds, in strictly descending order. */
    @NonNull
    private final RosterWrapper[] rosters;

    /**
     * Constructs a new {@code RosterWrapperHistory} from a list of {@link Entry} objects.
     *
     * @param rosterList the list of {@link Entry} objects
     * @throws IllegalArgumentException if the list is empty
     */
    public RosterWrapperHistory(@NonNull final List<Entry> rosterList) {
        if (rosterList.isEmpty()) {
            throw new IllegalArgumentException(
                    "RosterWrapperHistory cannot be constructed with an empty list of rosters");
        }
        // sort the rosters in descending order of first active round
        final List<Entry> sortedEntries = rosterList.stream()
                .sorted((a, b) -> Long.compare(b.firstActiveRound(), a.firstActiveRound()))
                .toList();
        this.rosters = sortedEntries.stream().map(Entry::roster).toArray(RosterWrapper[]::new);
        this.firstActiveRounds =
                sortedEntries.stream().mapToLong(Entry::firstActiveRound).toArray();
        this.activeRoster = rosters[0];
        this.latestFirstActiveRound = firstActiveRounds[0];
    }

    /**
     * Creates a {@code RosterWrapperHistory} from a list of {@link RoundRosterPair} history and a map of roster hashes to {@link Roster} objects.
     *
     * @param history the list of {@link RoundRosterPair} history
     * @param rosterMap the map of roster hashes to {@link Roster} objects
     * @return a new {@code RosterWrapperHistory} instance
     */
    public static RosterWrapperHistory of(
            @NonNull final List<RoundRosterPair> history, @NonNull final Map<Bytes, Roster> rosterMap) {
        final List<Entry> entries = history.stream()
                .map(pair -> {
                    final Roster roster = rosterMap.get(pair.activeRosterHash());
                    return new Entry(pair.roundNumber(), RosterWrapper.of(roster));
                })
                .toList();
        return new RosterWrapperHistory(entries);
    }

    /**
     * Returns the {@link RosterWrapper} that is active during a given round number.
     *
     * @param round the round number
     * @return the {@code RosterWrapper} that is active during the given round number
     * @throws IllegalArgumentException if the round number is before the earliest known first active round
     */
    @NonNull
    public RosterWrapper rosterForRound(final long round) {
        // optimization: most of the time we want the active roster
        if (round >= latestFirstActiveRound) {
            return activeRoster;
        }
        for (int i = 1; i < firstActiveRounds.length; i++) {
            if (round >= firstActiveRounds[i]) {
                return rosters[i];
            }
        }
        throw new IllegalArgumentException("Round " + round + " is before the earliest known first active round "
                + firstActiveRounds[firstActiveRounds.length - 1]);
    }

    /**
     * Returns the active {@link RosterWrapper}.
     *
     * @return the active {@code RosterWrapper}
     */
    @NonNull
    public RosterWrapper activeRoster() {
        return activeRoster;
    }
}
