// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRoster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

class RosterWrapperHistoryTest {

    private static final Randotron RANDOTRON = Randotron.create(42L);

    private static final long ROUND_1 = 1097534987L;
    private static final long ROUND_2 = 2983745987L;

    private static final RosterWrapper ROSTER_1;
    private static final RosterWrapper ROSTER_2;

    static {
        ROSTER_1 = randomRoster(RANDOTRON, 3);
        ROSTER_2 = randomRoster(RANDOTRON, 4);
    }

    @Test
    void testConstructorVariant() {
        final List<RosterWrapperHistory.Entry> entries = List.of(
                new RosterWrapperHistory.Entry(ROUND_1, ROSTER_1), new RosterWrapperHistory.Entry(ROUND_2, ROSTER_2));
        final RosterWrapperHistory rosterHistory = new RosterWrapperHistory(entries);

        assertRosterHistory(rosterHistory);
    }

    @Test
    void testMethodFactoryVariant() {
        final Bytes hash1 = RANDOTRON.nextHashBytes();
        final Bytes hash2 = RANDOTRON.nextHashBytes();

        final List<RoundRosterPair> pairs =
                List.of(new RoundRosterPair(ROUND_1, hash1), new RoundRosterPair(ROUND_2, hash2));
        final Map<Bytes, Roster> rosterMap = Map.of(hash1, ROSTER_1.toPbj(), hash2, ROSTER_2.toPbj());

        final RosterWrapperHistory rosterHistory = RosterWrapperHistory.of(pairs, rosterMap);

        assertRosterHistory(rosterHistory);
    }

    private static void assertRosterHistory(@NonNull final RosterWrapperHistory rosterHistory) {
        assertThat(rosterHistory.activeRoster()).isEqualTo(ROSTER_2);

        assertThat(rosterHistory.rosterForRound(ROUND_2 + 1)).isEqualTo(ROSTER_2);
        assertThat(rosterHistory.rosterForRound(ROUND_2)).isEqualTo(ROSTER_2);

        assertThat(rosterHistory.rosterForRound(ROUND_2 - 1)).isEqualTo(ROSTER_1);

        assertThat(rosterHistory.rosterForRound(ROUND_1 + 1)).isEqualTo(ROSTER_1);
        assertThat(rosterHistory.rosterForRound(ROUND_1)).isEqualTo(ROSTER_1);

        Assertions.assertThatThrownBy(() -> rosterHistory.rosterForRound(ROUND_1 - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
