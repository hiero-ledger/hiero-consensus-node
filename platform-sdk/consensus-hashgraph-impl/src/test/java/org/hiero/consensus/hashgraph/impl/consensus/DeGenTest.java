// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraphs;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal.SimpleEventImplGraph;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeGen}.
 */
class DeGenTest {

    private SimpleGraph<EventImpl> graph;

    private final SimpleGraphs<EventImpl> graphs = new SimpleGraphs<>(SimpleEventImplGraph::new);

    /**
     * Test the assignment and clearing of DeGen values in a simple graph.
     */
    @Test
    void simpleGraphTest() {
        final Randotron randotron = Randotron.create();
        // Create a simple graph
        graph = graphs.graph9e3n(randotron);

        // We pick 3 & 4 to be judges, they and their descendants will have a round of 1
        graph.events().subList(3, 9).forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_FIRST));
        // Events 0, 1 & 2 are not descendants of the judges, so their round is negative infinity
        graph.events()
                .subList(0, 3)
                .forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY));

        // Assign DeGen to the events
        for (final EventImpl event : graph.events()) {
            DeGen.calculateDeGen(event);
        }

        // Check that the DeGen values are as expected
        assertDeGen(0, 1);
        assertDeGen(1, 1);
        assertDeGen(2, 1);
        assertDeGen(3, 1);
        assertDeGen(4, 1);
        assertDeGen(5, 2);
        assertDeGen(6, 2);
        assertDeGen(7, 2);
        assertDeGen(8, 3);

        // Clear DeGen from the events
        for (final EventImpl event : graph.events()) {
            DeGen.clearDeGen(event);
        }

        // Check that the DeGen values are cleared
        for (final var event : graph.events()) {
            assertThat(event.getDeGen())
                    .withFailMessage("Expected DeGen to have been cleared")
                    .isEqualTo(DeGen.GENERATION_UNDEFINED);
        }
    }

    /**
     * Test the assignment and clearing of DeGen values in a graph with multiple other parents.
     */
    @Test
    void mopGraphTest() {
        final Randotron randotron = Randotron.create();
        // Create a simple graph
        graph = graphs.mopGraph(randotron);

        // We pick 4 & 7 to be judges, they and their descendants will have a round of 1
        graph.events(4, 7, 8, 9, 10, 11).forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_FIRST));
        // Events 0,1,2,3,5,6 are not descendants of the judges, so their round is negative infinity
        graph.events(0, 1, 2, 3, 5, 6)
                .forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY));

        // Assign DeGen to the events
        for (final EventImpl event : graph.events()) {
            DeGen.calculateDeGen(event);
        }

        // Check that the DeGen values are as expected
        assertDeGen(0, 1);
        assertDeGen(1, 1);
        assertDeGen(2, 1);
        assertDeGen(3, 1);
        assertDeGen(5, 1);
        assertDeGen(6, 1);

        assertDeGen(4, 1);
        assertDeGen(7, 1);

        assertDeGen(8, 2);
        assertDeGen(9, 2);
        assertDeGen(10, 2);
        assertDeGen(11, 2);

        // Clear DeGen from the events
        for (final EventImpl event : graph.events()) {
            DeGen.clearDeGen(event);
        }

        // Check that the DeGen values are cleared
        for (final var event : graph.events()) {
            assertThat(event.getDeGen())
                    .withFailMessage("Expected DeGen to have been cleared")
                    .isEqualTo(DeGen.GENERATION_UNDEFINED);
        }
    }

    private void assertDeGen(final int eventIndex, final int expectedDeGen) {
        final EventImpl event = graph.events().get(eventIndex);
        assertThat(event).withFailMessage("Event " + eventIndex + " is null").isNotNull();
        assertThat(event.getDeGen())
                .withFailMessage(
                        "Event with index %d is expected to have a DeGen of %d, but has %d",
                        eventIndex, expectedDeGen, event.getDeGen())
                .isEqualTo(expectedDeGen);
    }
}
