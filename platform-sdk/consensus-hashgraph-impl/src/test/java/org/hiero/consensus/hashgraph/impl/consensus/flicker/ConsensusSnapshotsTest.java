// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.snapshotAtRound;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the snapshot helpers that anchor the restart / roster-change family of Flicker tests.
 */
class ConsensusSnapshotsTest {

    /** The roster used to build the ladder graph. */
    private static final RosterWrapper ROSTER = LadderGraph.roster();

    /** The ladder graph, which is the simplest graph that produces a snapshot. */
    private static final List<NamedEvent> GRAPH = LadderGraph.build(ROSTER);

    /**
     * Run the LadderGraph and return its intake.
     *
     * @return the intake that ran the graph
     */
    private static FlickerIntake runLadderGraph() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FlickerIntake intake = new FlickerIntake(configuration, ROSTER);
        for (final NamedEvent named : GRAPH) {
            intake.add(named.name(), named.event().copyGossipedData());
        }
        return intake;
    }

    @Test
    @DisplayName("snapshotAtRound returns the snapshot of the named round")
    void returnsTheNamedRoundsSnapshot() {
        final List<Bytes> genesisHashes = GRAPH.stream()
                .filter(named -> List.of("a0", "b0", "c0", "d0").contains(named.name()))
                .map(named -> requireNonNull(named.event().getHash()).getBytes())
                .toList();

        final ConsensusSnapshot snapshot1 = snapshotAtRound(runLadderGraph().getConsensusRounds(), 1);

        assertThat(snapshot1.round()).isEqualTo(1);
        assertThat(snapshot1.judgeIds().stream().map(JudgeId::judgeHash))
                .as("round 1's judges are the four genesis events, one per node")
                .containsExactlyInAnyOrderElementsOf(genesisHashes);
    }

    @Test
    @DisplayName("snapshotAtRound names the rounds available when the requested one is missing")
    void reportsWhichRoundsExist() {
        final FlickerIntake intake = runLadderGraph();

        // The ladder graph decides round 1 only, so round 7 is a fixture-authoring mistake. The failure should say so
        // rather than return an empty Optional the caller has to interpret.
        assertThatThrownBy(() -> snapshotAtRound(intake.getConsensusRounds(), 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Round 7 did not reach consensus")
                .hasMessageContaining("Rounds available: 1");
    }
}
