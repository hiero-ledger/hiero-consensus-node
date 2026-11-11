// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.ConsensusSnapshots.snapshotAtRound;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerAssertions.assertJudges;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.ConsensusTraceLog;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SCN-001 — a round's judge exempted from clearing has another same-round judge in its ancestry.
 * <p>
 * Reproduces the scenario in {@code docs/consensus-layer/scenarios/SCN-001-*.md} and asserts INV-001
 * ({@code roundCreated(child) >= roundCreated(parent)}) holds across the roster change.
 * <p>
 * Before the fix, every judge of the just-decided round was exempt from metadata clearing. When two same-round judges
 * sat on one ancestry chain, the non-judge between them was cleared and recalculated under the new roster — and could
 * be promoted past the descendant judge, which had kept its old round. That inverts INV-001 and consensus stalls.
 * <p>
 * {@link Scn001Graph} builds the shape; its javadoc derives why each event lands where it does.
 */
class Scn001Test {

    @Test
    @DisplayName("INV-001 survives a roster change that promotes a non-judge between two same-round judges")
    void roundCreatedStaysMonotonicAcrossARosterChange() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final RosterWrapper rosterOld = Scn001Graph.rosterOld();
        final RosterWrapper rosterNew = Scn001Graph.rosterNew();
        final List<NamedEvent> graph = Scn001Graph.build();

        // phase 1 - build the anchor under the old roster
        final FlickerIntake pre = new FlickerIntake(configuration, rosterOld);
        for (final NamedEvent named : graph) {
            pre.add(named.name(), named.event().copyGossipedData());
        }
        final ConsensusSnapshot snapshot = snapshotAtRound(pre.getConsensusRounds(), Scn001Graph.ANCHOR_ROUND);

        // The premise the scenario rests on: round 2's judges include both ends of the chain. Asserted rather than
        // assumed, because a graph edit that quietly dropped one would leave the test passing while testing nothing.
        assertJudges(snapshot, graph)
                .contains(Scn001Graph.J1, Scn001Graph.J2)
                .as("the event between the two judges must not itself be one")
                .doesNotContain(Scn001Graph.MID);

        // phase 2 - restart under the new roster and replay the same DAG
        final ConsensusTraceLog trace = new ConsensusTraceLog();
        final FlickerIntake post = new FlickerIntake(configuration, rosterNew, trace);
        post.loadSnapshot(snapshot);
        for (final NamedEvent named : graph) {
            post.add(named.name(), named.event().copyGossipedData());
        }

        final EventImpl mid = post.event(Scn001Graph.MID);
        final EventImpl j2 = post.event(Scn001Graph.J2);

        assertThat(mid).as("mid was never linked").isNotNull();
        assertThat(j2).as("j2 was never linked").isNotNull();

        // The other premise: the new roster promotes mid. Without it, the inequality below holds trivially, and a
        // graph or roster edit that left mid in round 2 would leave the test passing without exercising the bug.
        assertThat(mid.getRoundCreated())
                .as("%s must be promoted past the round of the judges around it", Scn001Graph.MID)
                .isEqualTo(Scn001Graph.ANCHOR_ROUND + 1);

        assertThat(j2.getRoundCreated())
                .withFailMessage(
                        "INV-001: %s (round %d) is a descendant of %s (round %d), so its round cannot be lower.%n"
                                + "Trace:%n%s",
                        Scn001Graph.J2,
                        j2.getRoundCreated(),
                        Scn001Graph.MID,
                        mid.getRoundCreated(),
                        trace.render(null))
                .isGreaterThanOrEqualTo(mid.getRoundCreated());
    }
}
