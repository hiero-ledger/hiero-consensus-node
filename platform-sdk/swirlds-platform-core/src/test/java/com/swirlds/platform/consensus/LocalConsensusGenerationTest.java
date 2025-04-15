package com.swirlds.platform.consensus;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.event.linking.SimpleLinker;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.util.List;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
import org.hiero.consensus.model.event.AncientMode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;

class LocalConsensusGenerationTest {
    private List<EventImpl> events;

    @Test
    void simpleGraphTest(){
        final Randotron randotron = Randotron.create();
        // Create a simple graph with 5 events
        final SimpleLinker linker = new SimpleLinker(AncientMode.BIRTH_ROUND_THRESHOLD);
        events = SimpleGraphs.graph5e2n(randotron)
                .stream()
                .map(linker::linkEvent)
                .collect(Collectors.toCollection(ArrayList::new));

        // Assign cGen to the events
        LocalConsensusGeneration.assignCGen(events);

        // Check that the cGen values are as expected
        assertCGen(0, 1);
        assertCGen(1, 1);
        assertCGen(2, 2);
        assertCGen(3, 3);
        assertCGen(4, 3);

        // Clear cGen from the events
        LocalConsensusGeneration.clearCGen(events);

        // Check that the cGen values are cleared
        for (final var event : events) {
            assertThat(event.getCGen())
                    .isEqualTo(0);
        }
    }

    private void assertCGen(
            final int eventIndex,
            final int expectedCGen) {
        final EventImpl event = events.get(eventIndex);
        assertThat(event).isNotNull();
        assertThat(event.getCGen())
                .withFailMessage(
                        "Event with index %d is expected to have a cGen of %d, but has %d",
                        eventIndex,
                        expectedCGen,
                        event.getCGen())
                .isEqualTo(expectedCGen);
    }

}