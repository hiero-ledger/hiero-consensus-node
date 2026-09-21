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
     * A record representing an entry in the roster history, consisting of a starting round and a corresponding {@link RosterWrapper}.
     *
     * @param startingRound
     * @param roster
     */
    public record Entry(long startingRound, @NonNull RosterWrapper roster) {}

    /** The start round of the current roster. Used for optimization: hot path needs no array access. */
    private final long currentStartingRound;

    /** The current roster. Used for optimization: hot path needs no array access. */
    @NonNull
    private final RosterWrapper current;

    /** The start rounds of all rosters, in strictly descending order. */
    @NonNull
    private final long[] startingRounds;

    /** The rosters corresponding to the start rounds, in strictly descending order. */
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
        // sort the rosters in descending order of starting round
        final List<Entry> sortedEntries = rosterList.stream()
                .sorted((a, b) -> Long.compare(b.startingRound(), a.startingRound()))
                .toList();
        this.rosters = sortedEntries.stream().map(Entry::roster).toArray(RosterWrapper[]::new);
        this.startingRounds =
                sortedEntries.stream().mapToLong(Entry::startingRound).toArray();
        this.current = rosters[0];
        this.currentStartingRound = startingRounds[0];
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
        final List<Entry> rosterWrappers = history.stream()
                .map(pair -> {
                    final Roster roster = rosterMap.get(pair.activeRosterHash());
                    return new Entry(pair.roundNumber(), RosterWrapper.of(roster));
                })
                .toList();
        return new RosterWrapperHistory(rosterWrappers);
    }

    /**
     * Returns the {@link RosterWrapper} for the given round number.
     *
     * @param round the round number
     * @return the {@link RosterWrapper} for the given round number
     * @throws IllegalArgumentException if the round number is before the earliest roster starting round
     */
    @NonNull
    public RosterWrapper rosterForRound(final long round) {
        // optimization: most of the time we want the current roster
        if (round >= currentStartingRound) {
            return current;
        }
        for (int i = 1; i < startingRounds.length; i++) {
            if (round >= startingRounds[i]) {
                return rosters[i];
            }
        }
        throw new IllegalArgumentException("Round " + round + " is before the earliest roster starting round "
                + startingRounds[startingRounds.length - 1]);
    }

    /**
     * Returns the current {@link RosterWrapper}.
     *
     * @return the current {@link RosterWrapper}
     */
    @NonNull
    public RosterWrapper currentRoster() {
        return current;
    }
}
