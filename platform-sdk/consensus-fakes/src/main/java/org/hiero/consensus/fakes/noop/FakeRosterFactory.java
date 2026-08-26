// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.fakes.noop;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import org.hiero.consensus.roster.RosterHistory;

/**
 * A factory for creating fake Roster-related objects for tests and tools.
 */
public class FakeRosterFactory {

    private FakeRosterFactory() {}

    /**
     * Constructs a fake RosterHistory for utilities that do not require a fully functional object.
     *
     * @return a fake RosterHistory
     */
    public static RosterHistory fakeRosterHistory() {
        final RosterEntry entry = RosterEntry.newBuilder().nodeId(0).weight(1).build();
        final Roster roster = Roster.newBuilder().rosterEntries(entry).build();

        return RosterHistory.fromGenesis(roster);
    }
}
