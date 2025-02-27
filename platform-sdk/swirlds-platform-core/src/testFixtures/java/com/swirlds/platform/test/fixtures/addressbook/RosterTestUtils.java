// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Random;

/**
 * Utilities for manipulating rosters in tests.
 */
public class RosterTestUtils {

    private RosterTestUtils() {}

    /**
     * returns a new roster with the same RosterEntries, minus the RosterEntry matching the given NodeId.
     *
     * @param roster the roster to remove the entry from
     * @param nodeId the nodeId of the entry to remove
     * @return a new roster with the same RosterEntries, minus the RosterEntry matching the given NodeId
     */
    public static Roster dropRosterEntryFromRoster(@NonNull final Roster roster, final long nodeId) {
        final Roster.Builder builder = Roster.newBuilder();

        final ArrayList<RosterEntry> rosterEntries = new ArrayList<>();
        roster.rosterEntries().stream()
                .filter(entry -> entry.nodeId() != nodeId)
                .forEach(rosterEntries::add);

        builder.rosterEntries(rosterEntries);
        return builder.build();
    }

    /**
     * returns a new roster with the same RosterEntries, plus a new RosterEntry with the given NodeId.
     *
     * @param roster the roster to add the entry to
     * @param nodeId the nodeId of the entry to add
     * @param random the random number generator to use
     * @return a new roster with the same RosterEntries, plus a new RosterEntry with the given NodeId
     */
    public static Roster addRandomRosterEntryToRoster(
            @NonNull final Roster roster, final long nodeId, @NonNull final Random random) {
        final Roster.Builder builder = Roster.newBuilder();

        final ArrayList<RosterEntry> rosterEntries = new ArrayList<>(roster.rosterEntries());
        final RosterEntry entry =
                RandomRosterEntryBuilder.create(random).withNodeId(nodeId).build();
        rosterEntries.add(entry);

        builder.rosterEntries(rosterEntries);
        return builder.build();
    }
}
