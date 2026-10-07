// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.WitnessFound;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.ConsensusTraceLog;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts on the algorithm trace log that {@code RecordingEventImpl} writes.
 * <p>
 * Every claim here is unreachable through {@code TestIntake}. Witness-ness lives on {@code EventImpl}, no consensus
 * output carries it, and {@code recalculateAndVote} clears and recomputes it on every decided round — so the question
 * "which event was the first one promoted into round 2" has no answer that survives to the end of the run. That is what
 * the recorder seam is for, and these tests are the evidence it earns its keep.
 */
class ConsensusTraceTest {

    /** Fixed so the graph - and therefore every content hash in it - is reproducible. */
    private static final long SEED = 20260925L;

    private ConsensusTraceLog runLadderGraph() {
        final RosterWrapper roster = LadderGraph.roster();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final ConsensusTraceLog traceLog = new ConsensusTraceLog();
        final FlickerIntake intake = new FlickerIntake(configuration, roster, traceLog);

        for (final NamedEvent named : LadderGraph.build(roster)) {
            intake.add(named.name(), named.event());
        }
        return traceLog;
    }

    @Test
    @DisplayName("b2 is the first event promoted into round 2")
    void firstRoundTwoWitnessIsB2() {
        final ConsensusTraceLog traceLog = runLadderGraph();

        // Derivation, in the vocabulary of concepts/rounds-and-witnesses.md and strongly-seeing.md.
        //
        // The four genesis events a0..d0 have no parents, so they are round 1 and each is its creator's round-1
        // witness. Each layer-1 event sees all four of them but strongly sees none. Strongly seeing a0 needs
        // intermediate events by a supermajority of distinct creators, each one a descendant of a0 and an ancestor of
        // the event doing the seeing. For a1 those intermediates are a0 and a1, both created by A; for b1 they are a0
        // and b1, creators A and B. One or two creators out of four is short of the three required. So layer 1 is
        // round 1 throughout.
        //
        // An event is promoted to round 2 when it strongly sees a supermajority - 3 of these 4 equal-weight nodes - of
        // the round-1 witnesses. Layer 2 is fed in the order a2, b2, c2, d2:
        //
        //   a2  self-parent a1, other-parent b1 only. It sees a0..d0 through two creators, A and B. Two of four is
        //       short of the supermajority, so a2 is not promoted. It stays in round 1, and it is not a witness there
        //       either, because a0 is already A's round-1 witness.
        //   b2  self-parent b1, other-parents a1, c1, d1. All four creators' layer-1 events are ancestors, and each of
        //       them sees every genesis event, so b2 strongly sees all four round-1 witnesses. It is promoted to
        //       round 2, and as B's first round-2 event it is marked a witness there.
        //
        // So b2 is the first event in the run to be marked a witness while sitting in round 2.
        assertThat(traceLog.firstWitnessInRound(2))
                .withFailMessage(
                        "Expected b2 to be the first event marked a witness in round 2.%nWitness trace:%n%s",
                        traceLog.render(WitnessFound.class))
                .hasValueSatisfying(change -> assertThat(change.name()).isEqualTo("b2"));
    }

    @Test
    @DisplayName("an event is marked a witness repeatedly, so \"first\" has to mean global-first")
    void witnessDiscoveryIsRepeatedOnEveryDecidedRound() {
        final ConsensusTraceLog traceLog = runLadderGraph();

        // recalculateAndVote clears and recalculates every non-terminal event on each decided round, so an event that
        // is genuinely a witness is marked one again on every subsequent pass. Nothing distinguishes the re-marks from
        // the original in the record itself - only the sequence number does, which is why the log carries one and why
        // firstWitnessInRound means first-ever rather than first-per-event.
        final List<WitnessFound> b2MarkedWitness = traceLog.changes(WitnessFound.class)
                .filter(change -> "b2".equals(change.name()))
                .toList();

        assertThat(b2MarkedWitness)
                .withFailMessage(
                        "Expected b2 to be marked a witness more than once - the ladder graph decides round 1, which "
                                + "triggers a recalculation pass.%nWitness trace:%n%s",
                        traceLog.render(WitnessFound.class))
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("A's round-2 witness is a3, a layer above the other three")
    void starvedNodeIsPromotedALayerLate() {
        final ConsensusTraceLog traceLog = runLadderGraph();

        // Unlike "which event was promoted first", this is a structural fact rather than a consequence of feed order:
        // reordering layer 2 does not move A's round-2 witness off a3.
        //
        // a2's only other-parent is b1, so the intermediate events between it and the round-1 witnesses come from two
        // creators - A via a1, B via b1 - which is short of the three-of-four supermajority. a2 is therefore not
        // promoted, and it is not a witness in round 1 either, because a0 is already A's round-1 witness. A's
        // promotion waits for a3, whose other-parents b2, c2 and d2 restore the missing creators.
        //
        // This is the claim the graph was shaped to produce, and it is the one worth asserting: the harness has to
        // observe a promotion somewhere other than the layer where the other three happen.
        assertThat(traceLog.changes(WitnessFound.class)
                        .filter(change -> change.roundCreated() == 2)
                        .filter(change -> change.name().startsWith("a"))
                        .findFirst())
                .withFailMessage(
                        "Expected a3 to be A's round-2 witness.%nWitness trace:%n%s",
                        traceLog.render(WitnessFound.class))
                .hasValueSatisfying(change -> assertThat(change.name()).isEqualTo("a3"));

        assertThat(traceLog.changes(WitnessFound.class).map(WitnessFound::name))
                .withFailMessage(
                        "a2 is starved of the promotion supermajority and a0 is already A's round-1 witness, so a2 "
                                + "should never be marked a witness.%nWitness trace:%n%s",
                        traceLog.render(WitnessFound.class))
                .doesNotContain("a2");
    }
}
