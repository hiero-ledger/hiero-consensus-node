// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.snapshotAtRound;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerAssertions.assertJudges;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerAssertions.judgeNames;

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
 * Tests for {@code FlickerAssertions}, which read consensus output back in the names a graph uses.
 */
class FlickerAssertionsTest {

    private static final RosterWrapper ROSTER = LadderGraph.roster();
    private static final List<NamedEvent> GRAPH = LadderGraph.build(ROSTER);

    /**
     * Run the ladder graph and take the snapshot of round 1, the only round it decides.
     *
     * @return round 1's snapshot
     */
    private static ConsensusSnapshot roundOneSnapshot() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FlickerIntake intake = new FlickerIntake(configuration, ROSTER);
        for (final NamedEvent named : GRAPH) {
            intake.add(named.name(), named.event().copyGossipedData());
        }
        return snapshotAtRound(intake.getConsensusRounds(), 1);
    }

    @Test
    @DisplayName("judgeNames names a round's judges the way the graph names them")
    void judgesComeBackAsNames() {
        assertThat(judgeNames(roundOneSnapshot(), GRAPH))
                .as("round 1's judges are the four genesis events, one per node")
                .containsExactlyInAnyOrder("a0", "b0", "c0", "d0");
    }

    @Test
    @DisplayName("assertJudges composes, so a test checks only the judges it cares about")
    void theAssertionComposes() {
        assertJudges(roundOneSnapshot(), GRAPH).contains("a0").doesNotContain("a1");
    }

    @Test
    @DisplayName("a judge the graph does not contain is shown, not dropped")
    void anUnknownJudgeIsVisible() {
        // The snapshot read against a graph that is missing its judges: every name is unresolvable, and the list says
        // so rather than coming back short.
        assertThat(judgeNames(roundOneSnapshot(), List.of())).hasSize(4).allSatisfy(name -> assertThat(name)
                .startsWith("not-in-graph:"));
    }
}
