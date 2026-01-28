// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pcli.graph.EventGraphPipeline.EventFilter;
import org.hiero.consensus.pcli.graph.EventGraphPipeline.EventMapper;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventGraphPipeline}.
 */
class EventGraphPipelineTest {

    @Test
    void processWithAllComponents() {
        final List<PlatformEvent> receivedByEventSink = new ArrayList<>();
        final List<List<PlatformEvent>> receivedByBatchSink = new ArrayList<>();

        final EventGraphPipeline pipeline = new EventGraphPipeline(
                new ListEventGraphSource(() -> createMockEvents(5)),
                event -> true, // keep all
                event -> event, // pass through unchanged
                receivedByEventSink::add,
                receivedByBatchSink::add);

        pipeline.process();

        assertEquals(5, receivedByEventSink.size());
        assertEquals(1, receivedByBatchSink.size());
        assertEquals(5, receivedByBatchSink.getFirst().size());
    }

    @Test
    void processWithNullFilterKeepsAllEvents() {
        final List<PlatformEvent> received = new ArrayList<>();

        final EventGraphPipeline pipeline = new EventGraphPipeline(
                new ListEventGraphSource(() -> createMockEvents(3)),
                null, // no filter - keep all
                null, // no mapper
                received::add,
                null);

        pipeline.process();

        assertEquals(3, received.size());
    }

    @Test
    void processWithFilterExcludesEvents() {
        final List<PlatformEvent> inputEvents = createMockEvents(10);
        final List<PlatformEvent> received = new ArrayList<>();

        // Filter that keeps only even-indexed events (using object identity)
        final List<PlatformEvent> evenEvents = new ArrayList<>();
        for (int i = 0; i < inputEvents.size(); i += 2) {
            evenEvents.add(inputEvents.get(i));
        }
        final EventFilter filter = evenEvents::contains;

        final EventGraphPipeline pipeline =
                new EventGraphPipeline(new ListEventGraphSource(() -> inputEvents), filter, null, received::add, null);

        pipeline.process();

        assertEquals(5, received.size());
    }

    @Test
    void processWithMapperTransformsEvents() {
        final List<PlatformEvent> mappedEvents = createMockEvents(3); // different mock instances
        final List<PlatformEvent> received = new ArrayList<>();

        // Mapper that replaces each event with corresponding mapped event
        final Iterator<PlatformEvent> mappedIterator = mappedEvents.iterator();
        final EventMapper mapper = event -> mappedIterator.next();

        final EventGraphPipeline pipeline = new EventGraphPipeline(
                new ListEventGraphSource(() -> createMockEvents(3)), null, mapper, received::add, null);

        pipeline.process();

        assertEquals(3, received.size());
        assertEquals(mappedEvents, received);
    }

    @Test
    void processWithOnlyBatchSink() {
        final List<List<PlatformEvent>> batchReceived = new ArrayList<>();

        final EventGraphPipeline pipeline = new EventGraphPipeline(
                new ListEventGraphSource(() -> createMockEvents(4)),
                null,
                null,
                null, // no per-event sink
                batchReceived::add);

        pipeline.process();

        assertEquals(1, batchReceived.size());
        assertEquals(4, batchReceived.getFirst().size());
    }

    @Test
    void processWithNoSinksDoesNotFail() {

        final EventGraphPipeline pipeline =
                new EventGraphPipeline(new ListEventGraphSource(() -> createMockEvents(2)), null, null, null, null);

        // Should not throw
        pipeline.process();
    }

    @Test
    void processWithEmptySource() {
        final List<PlatformEvent> received = new ArrayList<>();
        final List<List<PlatformEvent>> batchReceived = new ArrayList<>();

        final EventGraphPipeline pipeline = new EventGraphPipeline(
                new ListEventGraphSource(List::of), null, null, received::add, batchReceived::add);

        pipeline.process();

        assertTrue(received.isEmpty());
        assertEquals(1, batchReceived.size());
        assertTrue(batchReceived.getFirst().isEmpty());
    }

    @Test
    void eventSinkReceivesEventsInOrder() {
        final List<PlatformEvent> inputEvents = createMockEvents(5);
        final List<PlatformEvent> received = new ArrayList<>();

        final EventGraphPipeline pipeline =
                new EventGraphPipeline(new ListEventGraphSource(() -> inputEvents), null, null, received::add, null);

        pipeline.process();

        assertEquals(inputEvents, received);
    }

    private static List<PlatformEvent> createMockEvents(int count) {
        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(mock(PlatformEvent.class));
        }
        return events;
    }
}
