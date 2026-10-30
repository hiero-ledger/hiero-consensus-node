// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FlickerIntake} to the production hashgraph wiring.
 * <p>
 * {@code FlickerIntake} reaches the algorithm's intermediate state by driving {@code ConsensusImpl}'s collaborators
 * directly, which means skipping {@code DefaultConsensusEngine} - the future event buffer, the freeze controller and
 * the drain loop. That bypass is the whole delta the harness introduces, and it is exactly the kind of shortcut that
 * drifts. This test feeds one graph through both paths and requires the same consensus rounds out of each.
 * <p>
 * The reference side is {@link ProductionHashgraphIntake}, which drives the real {@link DefaultHashgraphModule}, not
 * {@code TestIntake}. {@code TestIntake} would have been the easier choice and is what the design sketch assumed, but
 * it hand-rolls its own wiring and nothing pins <i>it</i> to production - so using it would leave an unchecked
 * approximation in the middle of the chain. See {@code ProductionHashgraphIntake} for what the reference does and does
 * not include.
 */
class FlickerIntakePinningTest {

    /** Fixed so the graph - and therefore every content hash in it - is reproducible. */
    private static final long SEED = 20260925L;

    @Test
    @DisplayName("FlickerIntake and the production hashgraph wiring produce identical consensus rounds")
    void producesTheSameRoundsAsTheProductionHashgraphWiring() {
        final RosterWrapper roster = LadderGraph.roster();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final List<NamedEvent> graph = LadderGraph.build(roster);

        // Each harness gets its own copies. Both hash and assign nGen in place, so sharing the objects would mean the
        // second run saw state the first one left behind.
        final FlickerIntake flicker = new FlickerIntake(configuration, roster);
        for (final NamedEvent named : graph) {
            flicker.add(named.name(), named.event().copyGossipedData());
        }

        final ProductionHashgraphIntake production = new ProductionHashgraphIntake(configuration, roster);
        for (final NamedEvent named : graph) {
            production.addEvent(named.event().copyGossipedData());
        }

        final List<ConsensusRound> flickerRounds = flicker.getConsensusRounds();
        final List<ConsensusRound> productionRounds = production.getConsensusRounds();

        // A pinning test that compares two empty lists pins nothing. The ladder is built five layers deep so that
        // round 1's fame is decided by the round-3 witnesses' counting votes at diff=2.
        assertThat(flickerRounds)
                .withFailMessage("The ladder graph should decide at least one round; FlickerIntake decided none")
                .isNotEmpty();

        assertThat(flickerRounds.stream().map(ConsensusRound::getRoundNum).toList())
                .as("round numbers")
                .isEqualTo(productionRounds.stream()
                        .map(ConsensusRound::getRoundNum)
                        .toList());

        assertThat(flickerRounds.stream().map(ConsensusRound::getSnapshot).toList())
                .as("round snapshots, which carry the judge set and the consensus timestamp")
                .isEqualTo(productionRounds.stream()
                        .map(ConsensusRound::getSnapshot)
                        .toList());

        assertThat(flickerRounds.stream()
                        .map(FlickerIntakePinningTest::consensusOrder)
                        .toList())
                .as("intra-round consensus order")
                .isEqualTo(productionRounds.stream()
                        .map(FlickerIntakePinningTest::consensusOrder)
                        .toList());
    }

    /**
     * The hashes of a round's consensus events, in the order the algorithm ordered them. Compared separately from the
     * {@link ConsensusSnapshot} because the snapshot carries the judge set but not the ordering the judges produced.
     */
    private static List<String> consensusOrder(final ConsensusRound round) {
        return round.getConsensusEvents().stream()
                .map(PlatformEvent::getHash)
                .map(Object::toString)
                .toList();
    }
}
