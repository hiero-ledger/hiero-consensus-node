// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.requireJudgesPresent;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.snapshotAtRound;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.Set;
import org.hiero.base.crypto.Hash;
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

    @Test
    @DisplayName("requireJudgesPresent passes when the replay supplies every judge")
    void acceptsAGraphContainingItsJudges() {
        final RosterWrapper roster = roster();
        final ConsensusSnapshot snapshot =
                snapshotAtRound(runLadderGraph(roster).getConsensusRounds(), 1);

        final List<Hash> allEventHashes =
                graph(roster).stream().map(named -> named.event().getHash()).toList();

        requireJudgesPresent(snapshot, allEventHashes);
    }

    @Test
    @DisplayName("requireJudgesPresent names the judges a replay would be missing")
    void rejectsAGraphMissingAJudge() {
        final RosterWrapper roster = roster();
        final ConsensusSnapshot snapshot =
                snapshotAtRound(runLadderGraph(roster).getConsensusRounds(), 1);

        // Drop the genesis events - which are exactly round 1's judges - from what the replay would supply. Without
        // this check, consensus would simply block forever and the fixture would fail on something unrelated.
        final List<Hash> withoutGenesis = graph(roster).stream()
                .filter(named -> !named.name().endsWith("0"))
                .map(named -> named.event().getHash())
                .toList();

        assertThatThrownBy(() -> requireJudgesPresent(snapshot, withoutGenesis))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names 4 judge(s) that the graph does not supply");
    }

    @Test
    @DisplayName("requireJudgesPresent tolerates a genesis snapshot, which names no judges")
    void acceptsASnapshotWithNoJudges() {
        final ConsensusSnapshot genesis = ConsensusSnapshot.DEFAULT;

        requireJudgesPresent(genesis, Set.of());
    }
}
