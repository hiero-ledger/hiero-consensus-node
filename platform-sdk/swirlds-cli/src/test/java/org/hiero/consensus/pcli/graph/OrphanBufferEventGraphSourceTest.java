// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static org.hiero.consensus.pcli.graph.utils.TestEventUtils.generateEvents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.PlatformTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OrphanBufferEventGraphSource}.
 */
class OrphanBufferEventGraphSourceTest {

    private static final int NUM_NODES = 4;
    private static final int NUM_EVENTS = 100;
    private static final long SEED = 12345L;

    private PlatformContext context;
    private List<PlatformEvent> rawEvents;

    @BeforeEach
    void setUp() {
        context = PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

        final Roster roster =
                RandomRosterBuilder.create(new Random(SEED)).withSize(NUM_NODES).build();

        // Generate raw events
        rawEvents = generateEvents(Randotron.create(), NUM_EVENTS, context, roster, null);
    }

    @Test
    void orphanBufferSourceReturnsHashedEvents() {
        final ListEventGraphSource rawSource = new ListEventGraphSource(() -> rawEvents);
        final OrphanBufferEventGraphSource orphanBufferSource = new OrphanBufferEventGraphSource(rawSource, context);

        assertTrue(orphanBufferSource.hasNext(), "Expected orphanBuffer source to have events");

        final PlatformEvent event = orphanBufferSource.next();
        assertNotNull(event);
        assertNotNull(event.getHash(), "orphanBuffer events should have computed hash");
    }

    @Test
    void getAllEventsReturnsAllOrphanBufferEvents() {
        final ListEventGraphSource rawSource = new ListEventGraphSource(() -> rawEvents);
        final OrphanBufferEventGraphSource orphanBufferSource = new OrphanBufferEventGraphSource(rawSource, context);

        final List<PlatformEvent> allEvents = new ArrayList<>();
        orphanBufferSource.forEachRemaining(allEvents::add);
        assertNotNull(allEvents);
        assertFalse(allEvents.isEmpty(), "Expected orphanBuffer events");

        // forEachRemaining consumes the source (streaming behavior)
        assertFalse(orphanBufferSource.hasNext(), "Source should be exhausted after forEachRemaining");
    }

    @Test
    void eventsHaveNgenComputed() {
        final ListEventGraphSource rawSource = new ListEventGraphSource(() -> rawEvents);
        final OrphanBufferEventGraphSource orphanBufferSource = new OrphanBufferEventGraphSource(rawSource, context);

        final List<PlatformEvent> allEvents = new ArrayList<>();
        orphanBufferSource.forEachRemaining(allEvents::add);
        assertNotNull(allEvents);

        // All orphanBuffer events should have sequence number computed (>= 0)
        for (final PlatformEvent event : allEvents) {
            assertTrue(
                    event.getSequenceNumber() >= EventConstants.FIRST_SEQUENCE_NUMBER,
                    "orphanBuffer events should have sequence number computed");
        }
    }

    @Test
    void resetRestartsIterationFromTheBeginning() {
        final ListEventGraphSource rawSource = new ListEventGraphSource(() -> rawEvents);
        final OrphanBufferEventGraphSource orphanBufferSource = new OrphanBufferEventGraphSource(rawSource, context);

        // Consume the source fully.
        final List<PlatformEvent> firstPass = new ArrayList<>();
        orphanBufferSource.forEachRemaining(firstPass::add);
        assertFalse(firstPass.isEmpty(), "Expected events on the first pass");
        assertFalse(orphanBufferSource.hasNext(), "Source should be exhausted before reset");

        // Reset and consume again.
        orphanBufferSource.reset();
        assertTrue(orphanBufferSource.hasNext(), "Source should have events again after reset");

        final List<PlatformEvent> secondPass = new ArrayList<>();
        orphanBufferSource.forEachRemaining(secondPass::add);

        // The reset source must reproduce the same events in the same order.
        assertEquals(firstPass.size(), secondPass.size(), "Reset should reproduce the same number of events");
        for (int i = 0; i < firstPass.size(); i++) {
            assertEquals(
                    firstPass.get(i).getDescriptor(),
                    secondPass.get(i).getDescriptor(),
                    "Reset should reproduce the same events in the same order");
        }
    }

    @Test
    void eventsHaveSequenceNumberComputed() {
        final ListEventGraphSource rawSource = new ListEventGraphSource(() -> rawEvents);
        final OrphanBufferEventGraphSource orphanBufferSource = new OrphanBufferEventGraphSource(rawSource, context);

        final List<PlatformEvent> allEvents = new ArrayList<>();
        orphanBufferSource.forEachRemaining(allEvents::add);
        assertNotNull(allEvents);

        // All orphanBuffer events should have sequence number assigned
        for (final PlatformEvent event : allEvents) {
            assertTrue(event.hasSequenceNumber(), "orphanBuffer events should have sequence number assigned");
        }
    }
}
