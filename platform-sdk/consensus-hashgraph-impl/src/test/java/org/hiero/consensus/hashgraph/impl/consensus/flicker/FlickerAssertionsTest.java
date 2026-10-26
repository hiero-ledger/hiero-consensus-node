// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusSnapshots.snapshotAtRound;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerAssertions.assertJudges;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerAssertions.judgeNames;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.MetadataCleared;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.RoundCreatedSet;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.ConsensusTraceLog;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** D4: the helpers a fixture uses to say what it means, rather than restating it in hashes and list indices. */
class FlickerAssertionsTest {

    private static final RosterWrapper ROSTER = LadderGraph.roster();

    private static List<NamedEvent> graph() {
        return LadderGraph.build(ROSTER);
    }

    private static FlickerIntake run(final List<NamedEvent> graph, final ConsensusTraceLog traceLog) {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FlickerIntake intake = new FlickerIntake(configuration, ROSTER, traceLog);
        for (final NamedEvent named : graph) {
            intake.add(named.name(), named.event().copyGossipedData());
        }
        return intake;
    }

    @Nested
    @DisplayName("assertJudges")
    class Judges {

        @Test
        @DisplayName("names a round's judges the way the graph names them")
        void judgesComeBackAsNames() {
            final List<NamedEvent> graph = graph();
            final ConsensusSnapshot snapshot = snapshotAtRound(run(graph, null).getConsensusRounds(), 1);

            assertThat(judgeNames(snapshot, graph))
                    .as("round 1's judges are the four genesis events, one per node")
                    .containsExactlyInAnyOrder("a0", "b0", "c0", "d0");
        }

        @Test
        @DisplayName("composes, so a fixture states only what its scenario requires")
        void theAssertionComposes() {
            final List<NamedEvent> graph = graph();
            final ConsensusSnapshot snapshot = snapshotAtRound(run(graph, null).getConsensusRounds(), 1);

            assertJudges(snapshot, graph).contains("a0").doesNotContain("a1");
        }

        @Test
        @DisplayName("a judge the graph does not contain is shown, not dropped")
        void anUnknownJudgeIsVisible() {
            final ConsensusSnapshot snapshot =
                    snapshotAtRound(run(graph(), null).getConsensusRounds(), 1);

            // The same snapshot read against a graph that is missing its judges: every name is unresolvable, and the
            // list says so rather than coming back short.
            assertThat(judgeNames(snapshot, List.of())).hasSize(4).allSatisfy(name -> assertThat(name)
                    .startsWith("not-in-graph:"));
        }
    }

    @Nested
    @DisplayName("trace scoping")
    class Scoping {

        @Test
        @DisplayName("a scope holds the changes of one add, not the run's")
        void aScopeIsOneAdd() {
            final ConsensusTraceLog trace = new ConsensusTraceLog();
            run(graph(), trace);

            final ConsensusTraceLog.Scope scope = trace.during("b2");

            assertThat(scope.addName()).isEqualTo("b2");
            assertThat(scope.changes())
                    .as("b2's own promotion into round 2 is recorded during its add")
                    .isNotEmpty();
            assertThat(scope.changes(RoundCreatedSet.class).map(RoundCreatedSet::name))
                    .as("and the events it touches are only the ones that pass reached")
                    .contains("b2");
            assertThat(scope.changes().size())
                    .as("one add is a fraction of the run")
                    .isLessThan(trace.changes().size());
        }

        @Test
        @DisplayName("the scopes together are the whole run, in order")
        void scopesPartitionTheRun() {
            final ConsensusTraceLog trace = new ConsensusTraceLog();
            final List<NamedEvent> graph = graph();
            run(graph, trace);

            final List<?> fromScopes = graph.stream()
                    .flatMap(named -> trace.during(named.name()).changes().stream())
                    .toList();

            assertThat(fromScopes).as("every change belongs to exactly one add").isEqualTo(trace.changes());
        }

        @Test
        @DisplayName("scoping to an event that was never added says so")
        void anUnknownAddIsRefused() {
            final ConsensusTraceLog trace = new ConsensusTraceLog();
            run(graph(), trace);

            assertThatThrownBy(() -> trace.during("z9"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no event named 'z9'");
        }

        @Test
        @DisplayName("a scope renders only its own changes")
        void renderIsScopedToo() {
            final ConsensusTraceLog trace = new ConsensusTraceLog();
            run(graph(), trace);

            final String scoped = trace.during("b2").render(MetadataCleared.class);

            assertThat(scoped.lines())
                    .as("rendering the run would bury the pass being asserted on")
                    .hasSizeLessThan(
                            (int) trace.render(MetadataCleared.class).lines().count());
        }
    }
}
