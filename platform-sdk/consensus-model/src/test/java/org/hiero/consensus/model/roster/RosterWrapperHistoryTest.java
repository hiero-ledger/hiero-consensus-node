// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.randomRoster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RosterWrapperHistoryTest {

    private static final long ROUND_1 = 1097534987L;
    private static final long ROUND_2 = 2983745987L;

    private RosterWrapper roster1;
    private RosterWrapper roster2;
    private RosterWrapperHistory rosterHistory;

    @BeforeEach
    void setup() {
        final Randotron randotron = Randotron.create(42L);

        roster1 = randomRoster(randotron, 3);
        roster2 = randomRoster(randotron, 4);

        final Bytes hash1 = randotron.nextHashBytes();
        final Bytes hash2 = randotron.nextHashBytes();

        final List<RoundRosterPair> pairs =
                List.of(new RoundRosterPair(ROUND_1, hash1), new RoundRosterPair(ROUND_2, hash2));
        final Map<Bytes, Roster> rosterMap = Map.of(hash1, roster1.toPbj(), hash2, roster2.toPbj());

        rosterHistory = RosterWrapperHistory.of(pairs, rosterMap);
    }

    @Test
    void testCurrentRoster() {
        assertThat(rosterHistory.currentRoster()).isEqualTo(roster2);
    }

    @Test
    void testRosterForRound() {
        assertThat(rosterHistory.rosterForRound(ROUND_2 + 1)).isEqualTo(roster2);
        assertThat(rosterHistory.rosterForRound(ROUND_2)).isEqualTo(roster2);

        assertThat(rosterHistory.rosterForRound(ROUND_2 - 1)).isEqualTo(roster1);

        assertThat(rosterHistory.rosterForRound(ROUND_1 + 1)).isEqualTo(roster1);
        assertThat(rosterHistory.rosterForRound(ROUND_1)).isEqualTo(roster1);

        Assertions.assertThatThrownBy(() -> rosterHistory.rosterForRound(ROUND_1 - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
