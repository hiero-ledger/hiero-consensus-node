// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.fakes.noop;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.roster.RosterInputs;

/**
 * A factory for creating fake Roster-related objects for tests and tools.
 */
public class FakeRosterFactory {

    private FakeRosterFactory() {}

    /**
     * Constructs fake {@link RosterInputs} for utilities that do not require a fully functional object.
     *
     * @return a fake {@code RosterInputs}
     */
    public static RosterInputs fakeRosterInputs() {
        final RosterEntry entry = RosterEntry.newBuilder().nodeId(0).weight(1).build();
        final Roster roster = Roster.newBuilder().rosterEntries(entry).build();
        final Bytes fakeHash = Bytes.fromHex("cafe");
        final RoundRosterPair roundRosterPair =
                RoundRosterPair.newBuilder().activeRosterHash(fakeHash).build();

        return new RosterInputs(List.of(roundRosterPair), Map.of(fakeHash, roster), Bytes.EMPTY);
    }
}
