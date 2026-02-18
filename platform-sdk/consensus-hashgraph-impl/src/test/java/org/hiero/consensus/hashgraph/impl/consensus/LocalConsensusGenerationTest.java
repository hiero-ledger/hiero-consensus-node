// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraphs;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal.SimpleEventImplGraph;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link LocalConsensusGeneration} class.
 */
class LocalConsensusGenerationTest {

    private SimpleGraph<EventImpl> graph;
    private final SimpleGraphs<EventImpl> graphs = new SimpleGraphs<>(SimpleEventImplGraph::new);

    /**
     * Test the assignment and clearing of cGen values in a simple graph.
     */
    @Test
    void simpleGraphTest() {
        final Randotron randotron = Randotron.create();
        // Create a simple graph
        graph = graphs.graph8e4n(randotron);
        // Shuffle the events to simulate random order
        final List<EventImpl> shuffledEvents = graph.shuffledEvents(randotron);

        // Assign cGen to the events
        LocalConsensusGeneration.assignCGen(shuffledEvents);

        // Check that the cGen values are as expected
        assertCGen(0, 1);
        assertCGen(1, 1);
        assertCGen(2, 2);
        assertCGen(3, 3);
        assertCGen(4, 3);
        assertCGen(5, 1);
        assertCGen(6, 1);
        assertCGen(7, 2);

        // Clear cGen from the events
        LocalConsensusGeneration.clearCGen(shuffledEvents);

        // Check that the cGen values are cleared
        for (final var event : graph.events()) {
            assertThat(event.getCGen())
                    .withFailMessage("Expected CGen to have been cleared")
                    .isEqualTo(LocalConsensusGeneration.GENERATION_UNDEFINED);
        }
    }

    private void assertCGen(final int eventIndex, final int expectedCGen) {
        final EventImpl event = graph.events().get(eventIndex);
        assertThat(event).withFailMessage("Event " + eventIndex + " is null").isNotNull();
        assertThat(event.getCGen())
                .withFailMessage(
                        "Event with index %d is expected to have a cGen of %d, but has %d",
                        eventIndex, expectedCGen, event.getCGen())
                .isEqualTo(expectedCGen);
    }
}
