// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.Test;

/**
 * Tests which rounds {@link ConsensusRoundPool} deduplicates.
 */
class ConsensusRoundPoolTest {

    @Test
    void identicalRoundsShareOneInstance() {
        final ConsensusRoundPool pool = new ConsensusRoundPool();
        final ConsensusRound firstRound = emptyRound(1L);
        final ConsensusRound identicalRound = emptyRound(1L);

        assertThat(pool.update(firstRound)).isSameAs(firstRound);
        assertThat(pool.update(identicalRound)).isSameAs(firstRound);
    }

    @Test
    void differentRoundsWithTheSameNumberAreKeptApart() {
        final ConsensusRoundPool pool = new ConsensusRoundPool();
        // Mocks are only equal to themselves
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getRoundNum()).thenReturn(1L);
        final ConsensusRound differentRound = mock(ConsensusRound.class);
        when(differentRound.getRoundNum()).thenReturn(1L);

        assertThat(pool.update(round)).isSameAs(round);
        assertThat(pool.update(differentRound)).isSameAs(differentRound);
        assertThat(pool.update(round)).isSameAs(round);
    }

    @Test
    void equalRoundsWithDifferentNumbersAreKeptApart() {
        final ConsensusRoundPool pool = new ConsensusRoundPool();
        // Rounds are equal if their consensus events are equal, so two rounds without events are equal
        final ConsensusRound round1 = emptyRound(1L);
        final ConsensusRound round2 = emptyRound(2L);
        assertThat(round1).isEqualTo(round2);

        assertThat(pool.update(round1)).isSameAs(round1);
        assertThat(pool.update(round2)).isSameAs(round2);
    }

    @NonNull
    private static ConsensusRound emptyRound(final long roundNum) {
        return new ConsensusRound(
                mock(RosterWrapper.class),
                List.of(),
                EventWindow.getGenesisEventWindow(),
                ConsensusSnapshot.newBuilder().round(roundNum).build(),
                false,
                Instant.EPOCH);
    }
}
