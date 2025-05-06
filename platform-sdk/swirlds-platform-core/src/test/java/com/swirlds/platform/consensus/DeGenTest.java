// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeGen}.
 */
class DeGenTest {

    private List<EventImpl> events;

    /**
     * Test the assignment and clearing of DeGen values in a simple graph.
     */
    @Test
    void simpleGraphTest() {
        final Randotron randotron = Randotron.create();
        // Create a simple graph
        events = SimpleGraphs.graph9e3n(randotron);

        // We pick 3 & 4 to be judges, they and their descendants will have a round of 1
        events.subList(3, 9).forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_FIRST));
        // Events 0, 1 & 2 are not descendants of the judges, so their round is negative infinity
        events.subList(0, 3).forEach(event -> event.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY));

        // Assign DeGen to the events
        for (final EventImpl event : events) {
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
        for (final EventImpl event : events) {
            DeGen.clearDeGen(event);
        }

        // Check that the DeGen values are cleared
        for (final var event : events) {
            assertThat(event.getDeGen())
                    .withFailMessage("Expected DeGen to have been cleared")
                    .isEqualTo(DeGen.GENERATION_UNDEFINED);
        }
    }

    private void assertDeGen(final int eventIndex, final int expectedDeGen) {
        final EventImpl event = events.get(eventIndex);
        assertThat(event).withFailMessage("Event " + eventIndex + " is null").isNotNull();
        assertThat(event.getDeGen())
                .withFailMessage(
                        "Event with index %d is expected to have a DeGen of %d, but has %d",
                        eventIndex, expectedDeGen, event.getDeGen())
                .isEqualTo(expectedDeGen);
    }
}
