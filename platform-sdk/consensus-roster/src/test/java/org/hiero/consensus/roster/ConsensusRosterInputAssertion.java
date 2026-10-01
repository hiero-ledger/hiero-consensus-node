// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.roster.ConsensusLayerRosterInputs;

/**
 * A utility class for asserting the correctness of {@link ConsensusLayerRosterInputs} in tests.
 */
public class ConsensusRosterInputAssertion {

    private ConsensusRosterInputAssertion() {}

    public static void assertConsensusLayerRosterInputs(
            @NonNull final ConsensusLayerRosterInputs rosterInputs,
            @NonNull final List<Long> expectedRounds,
            @NonNull final List<Roster> expectedRosters) {
        final List<RoundRosterPair> history = rosterInputs.history();
        final List<Roster> rosters = rosterInputs.rosters().values().stream().toList();

        assertEquals(expectedRounds.size(), history.size(), "History size should match the number of rounds");
        assertEquals(expectedRosters.size(), rosters.size(), "Rosters size should match the number of rosters");

        for (int i = 0; i < expectedRounds.size(); i++) {
            assertEquals(expectedRounds.get(i), history.get(i).roundNumber(), "Round number should match");
            assertEquals(
                    RosterUtils.hash(expectedRosters.get(i)).getBytes(),
                    history.get(i).activeRosterHash(),
                    "Roster hash should match");
            assertEquals(
                    expectedRosters.get(i),
                    rosterInputs.rosters().get(history.get(i).activeRosterHash()),
                    "Roster in map should match the expected roster");
        }
    }
}
