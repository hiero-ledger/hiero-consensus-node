// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.snapshotAtRound;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snapshot helpers that anchor the restart / roster-change family.
 */
class ConsensusSnapshotsTest {

    private static final long SEED = 20260925L;

    private static List<NamedEvent> graph(final RosterWrapper roster) {
        return LadderGraph.build(roster);
    }

    private static RosterWrapper roster() {
        return LadderGraph.roster();
    }

    private static FlickerIntake runLadderGraph(final RosterWrapper roster) {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FlickerIntake intake = new FlickerIntake(configuration, roster);
        for (final NamedEvent named : graph(roster)) {
            intake.add(named.name(), named.event().copyGossipedData());
        }
        return intake;
    }

    @Test
    @DisplayName("snapshotAtRound returns the snapshot of the named round")
    void returnsTheNamedRoundsSnapshot() {
        final FlickerIntake intake = runLadderGraph(roster());

        final ConsensusSnapshot snapshot = snapshotAtRound(intake.getConsensusRounds(), 1);

        assertThat(snapshot.round()).isEqualTo(1);
        assertThat(snapshot.judgeIds())
                .as("round 1's judges are the four genesis events, one per node")
                .hasSize(4);
    }

    @Test
    @DisplayName("snapshotAtRound names the rounds available when the requested one is missing")
    void reportsWhichRoundsExist() {
        final FlickerIntake intake = runLadderGraph(roster());

        // The ladder graph decides round 1 only, so round 7 is a fixture-authoring mistake. The failure should say so
        // rather than return an empty Optional the caller has to interpret.
        assertThatThrownBy(() -> snapshotAtRound(intake.getConsensusRounds(), 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Round 7 did not reach consensus")
                .hasMessageContaining("Rounds available: 1");
    }
}
