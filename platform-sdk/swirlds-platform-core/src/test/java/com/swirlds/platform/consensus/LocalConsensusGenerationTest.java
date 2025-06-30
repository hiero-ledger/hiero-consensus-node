// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.event.linking.SimpleLinker;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for the {@link LocalConsensusGeneration} class.
 */
class LocalConsensusGenerationTest {
    private List<EventImpl> events;

    /**
     * Test the assignment and clearing of cGen values in a simple graph.
     */
    @Test
    void simpleGraphTest() {
        final Randotron randotron = Randotron.create();
        // We need a linker to created EventImpl objects that hold the cGen value
        final SimpleLinker linker = new SimpleLinker();
        // We need an orphan buffer to assign nGen values to the events
        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                TestPlatformContextBuilder.create().build(), Mockito.mock(IntakeEventCounter.class));
        // Create a simple graph
        events = SimpleGraphs.graph8e4n(randotron).stream()
                .peek(orphanBuffer::handleEvent)
                .map(linker::linkEvent)
                .collect(Collectors.toCollection(ArrayList::new));

        // Shuffle the events to simulate random order
        final List<EventImpl> shuffledEvents = new ArrayList<>(events);
        Collections.shuffle(shuffledEvents, randotron);

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
        LocalConsensusGeneration.clearCGen(events);

        // Check that the cGen values are cleared
        for (final var event : events) {
            assertThat(event.getCGen())
                    .withFailMessage("Expected CGen to have been cleared")
                    .isEqualTo(LocalConsensusGeneration.GENERATION_UNDEFINED);
        }
    }

    private void assertCGen(final int eventIndex, final int expectedCGen) {
        final EventImpl event = events.get(eventIndex);
        assertThat(event).withFailMessage("Event " + eventIndex + " is null").isNotNull();
        assertThat(event.getCGen())
                .withFailMessage(
                        "Event with index %d is expected to have a cGen of %d, but has %d",
                        eventIndex, expectedCGen, event.getCGen())
                .isEqualTo(expectedCGen);
    }
}
