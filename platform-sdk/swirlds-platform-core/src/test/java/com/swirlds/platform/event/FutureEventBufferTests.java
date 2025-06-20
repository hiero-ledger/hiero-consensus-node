// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomInstant;
import static org.hiero.consensus.event.FutureEventBufferingOption.EVENT_BIRTH_ROUND;
import static org.hiero.consensus.event.FutureEventBufferingOption.PENDING_CONSENSUS_ROUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FutureEventBufferTests {
    public static final Metrics METRICS = new NoOpMetrics();
    public static final Configuration CONFIGURATION = new TestConfigBuilder().getOrCreateConfig();

    /**
     * This test verifies the following:
     * <ul>
     *     <li>Events that are from the future are buffered.</li>
     *     <li>Buffered events are returned in topological order</li>
     *     <li>ancient events are discarded</li>
     *     <li>non-ancient non-future events are returned immediately</li>
     * </ul>
     */
    @Test
    void futureEventsBufferedTest() {
        final Random random = getRandomPrintSeed();

        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        final long nonAncientBirthRound = 100;
        final long pendingConsensusRound = nonAncientBirthRound * 2;
        final long maxFutureRound = nonAncientBirthRound * 3;

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(pendingConsensusRound - 1)
                .setAncientThreshold(nonAncientBirthRound)
                .build();

        futureEventBuffer.updateEventWindow(eventWindow);

        final int count = 1000;
        final List<PlatformEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setBirthRound(random.nextLong(1, maxFutureRound))
                    .setCreatorId(NodeId.of(random.nextInt(100)))
                    .setTimeCreated(randomInstant(random))
                    .build();
            events.add(event);
        }
        // Put the events in topological order
        events.sort(Comparator.comparingLong(PlatformEvent::getBirthRound));

        final List<PlatformEvent> futureEvents = new ArrayList<>();
        for (final PlatformEvent event : events) {
            final PlatformEvent returnedEvent = futureEventBuffer.addEvent(event);
            if (eventWindow.isAncient(event)) {
                // Ancient events should be discarded.
                assertNull(returnedEvent);
            } else if (event.getBirthRound() <= eventWindow.getPendingConsensusRound()) {
                // Non-future events should be returned immediately.
                assertSame(event, returnedEvent);
            } else {
                // Future events should be buffered.
                futureEvents.add(event);
                assertNull(returnedEvent);
            }
        }

        // Gradually shift the window forward and collect buffered events as they stop being future events.
        final List<PlatformEvent> unBufferedEvents = new ArrayList<>();
        for (long newPendingConsensusRound = pendingConsensusRound + 1;
                newPendingConsensusRound <= maxFutureRound;
                newPendingConsensusRound++) {

            final EventWindow newEventWindow = EventWindowBuilder.builder()
                    .setLatestConsensusRound(newPendingConsensusRound - 1)
                    .setAncientThreshold(nonAncientBirthRound)
                    .build();

            final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);

            for (final PlatformEvent event : bufferedEvents) {
                assertEquals(newPendingConsensusRound, event.getBirthRound());
                unBufferedEvents.add(event);
            }
        }

        // When we are finished, we should have all of the future events in the same order that they were inserted.
        assertEquals(futureEvents, unBufferedEvents);

        // Make a big window shift. There should be no events that come out of the buffer.
        final EventWindow newEventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(pendingConsensusRound * 1000)
                .setAncientThreshold(nonAncientBirthRound)
                .build();
        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertTrue(bufferedEvents.isEmpty());
    }

    /**
     * Tests that the clear method removes all buffered events.
     */
    @Test
    void testClear() {
        final StaticTestInput testInput = new StaticTestInput();
        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();
        testInput.allTestEvents().forEach(futureEventBuffer::addEvent);
        futureEventBuffer.clear();
        assertThat(futureEventBuffer.updateEventWindow(testInput.getEventWindowForMaxBirthRound()))
                .isEmpty();
    }

    /**
     * It is plausible that we have a big jump in rounds due to a reconnect. Verify that we don't emit events if they
     * become ancient while buffered.
     */
    @Test
    void eventsGoAncientWhileBufferedTest() {
        final Random random = getRandomPrintSeed();

        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        final long nonAncientBirthRound = 100;
        final long pendingConsensusRound = nonAncientBirthRound * 2;
        final long maxFutureRound = nonAncientBirthRound * 3;

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(pendingConsensusRound - 1)
                .setAncientThreshold(nonAncientBirthRound)
                .build();

        futureEventBuffer.updateEventWindow(eventWindow);

        final int count = 1000;
        final List<PlatformEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setBirthRound(random.nextLong(1, maxFutureRound))
                    .setCreatorId(NodeId.of(random.nextInt(100)))
                    .setTimeCreated(randomInstant(random))
                    .build();
            events.add(event);
        }
        // Put the events in topological order
        events.sort(Comparator.comparingLong(PlatformEvent::getBirthRound));

        for (final PlatformEvent event : events) {
            final PlatformEvent returnedEvent = futureEventBuffer.addEvent(event);
            if (eventWindow.isAncient(event)) {
                // Ancient events should be discarded.
                assertNull(returnedEvent);
            } else if (event.getBirthRound() <= eventWindow.getPendingConsensusRound()) {
                // Non-future events should be returned immediately.
                assertSame(event, returnedEvent);
            } else {
                // Future events should be buffered.
                assertNull(returnedEvent);
            }
        }

        final EventWindow newEventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(pendingConsensusRound * 1000)
                .setAncientThreshold(nonAncientBirthRound * 1000)
                .build();

        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertTrue(bufferedEvents.isEmpty());
    }

    /**
     * Verify that an event that is buffered gets released at the exact moment we expect.
     */
    @Test
    void eventInBufferAreReleasedOnTimeTest() {
        final Random random = getRandomPrintSeed();
        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        final long pendingConsensusRound = random.nextLong(100, 1_000);
        final long nonAncientBirthRound = pendingConsensusRound / 2;

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(pendingConsensusRound - 1)
                .setAncientThreshold(nonAncientBirthRound)
                .build();
        futureEventBuffer.updateEventWindow(eventWindow);

        final long roundsUntilRelease = random.nextLong(10, 20);
        final long eventBirthRound = pendingConsensusRound + roundsUntilRelease;
        final PlatformEvent event = new TestingEventBuilder(random)
                .setBirthRound(eventBirthRound)
                .setCreatorId(NodeId.of(random.nextInt(100)))
                .setTimeCreated(randomInstant(random))
                .build();

        // Event is from the future, we can't release it yet
        assertThat(futureEventBuffer.addEvent(event)).isNull();

        // While the (newPendingConsensusRound-1) is less than the event's birth round, the event should be buffered
        for (long currentConsensusRound = pendingConsensusRound - 1;
                currentConsensusRound < eventBirthRound - 1;
                currentConsensusRound++) {

            final EventWindow newEventWindow = EventWindowBuilder.builder()
                    .setLatestConsensusRound(currentConsensusRound)
                    .setAncientThreshold(nonAncientBirthRound)
                    .build();
            final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
            assertTrue(bufferedEvents.isEmpty());
        }

        // When the pending consensus round is equal to the event's birth round, the event should be released
        // Note: the pending consensus round is equal to the current consensus round + 1, but the argument
        // for an event window takes the current consensus round, not the pending consensus round.
        // To land with the pending consensus round at the exact value as the event's birth round, we need to
        // set the current consensus round to the event's birth round - 1.

        final EventWindow newEventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(eventBirthRound - 1)
                .setAncientThreshold(nonAncientBirthRound)
                .build();
        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertEquals(1, bufferedEvents.size());
        assertSame(event, bufferedEvents.getFirst());
    }

    /**
     * Tests that events are released from the future event buffer in the order that they were received in except that
     * they are released by birth round, assuming the event window is shifted to allow the release of all events.
     * <br>
     * This test adds events in birth round order and verifies that the output is in that same order.
     */
    @Test
    @DisplayName("events are released in order when received in birth round order")
    void eventsAreReleasedInOrder_receivedInBirthRoundOrder() {
        final StaticTestInput testInput = new StaticTestInput();

        // In this test, events are added in groups by birth round, therefore the
        // output order should be the same as the input order
        final List<PlatformEvent> inputOrder = List.of(
                testInput.a1,
                testInput.b1,
                testInput.c1,
                testInput.c2,
                testInput.b2,
                testInput.a3,
                testInput.b3,
                testInput.c3,
                testInput.a3);
        final Queue<PlatformEvent> expectedOutputOrder = new LinkedList<>(inputOrder);
        final Queue<PlatformEvent> actualOutputOrder = new LinkedList<>();

        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        for (final PlatformEvent event : inputOrder) {
            final PlatformEvent returnedEvent = futureEventBuffer.addEvent(event);
            if (returnedEvent != null) {
                actualOutputOrder.add(returnedEvent);
            }
        }

        // Updating the event window to pending round 3 should release all buffered events.
        final EventWindow eventWindow = testInput.eventWindowForPendingRound(3);
        actualOutputOrder.addAll(futureEventBuffer.updateEventWindow(eventWindow));
        assertThat(actualOutputOrder).isEqualTo(expectedOutputOrder);
    }

    private FutureEventBuffer pendingRoundFutureBuffer() {
        return new FutureEventBuffer(METRICS, PENDING_CONSENSUS_ROUND);
    }

    /**
     * Tests that events are released from the future event buffer in the order that they were received in except that
     * they are released by birth round, assuming the event window is shifted to allow the release of all events.
     * <br>
     * This test adds events out of birth round order and verifies that the order of events released, relative to other
     * events with the same birth round, is the same. The event window is updated in incremental round values.
     */
    @Test
    @DisplayName("events not received in birth round order, event window is gradually advanced")
    void eventsAreReleasedInOrder_notReceivedInBirthRoundOrder_noEventWindowJump() {
        final StaticTestInput testInput = new StaticTestInput();

        // In this test, events are not added in order of birth rounds. Therefore the
        // output order will not be the same.
        final List<PlatformEvent> inputOrder = List.of(
                testInput.a1,
                testInput.a2,
                testInput.a3,
                testInput.c1,
                testInput.c2,
                testInput.c3,
                testInput.b2,
                testInput.b3,
                testInput.b1);

        // Events should be released in batches by birth round, but within each batch the events
        // should be ordered according to the order they were received in.
        final Queue<PlatformEvent> expectedOutputOrder = new LinkedList<>(List.of(
                testInput.a1,
                testInput.c1,
                testInput.b1,
                testInput.a2,
                testInput.c2,
                testInput.b2,
                testInput.a3,
                testInput.c3,
                testInput.b3));

        final Queue<PlatformEvent> actualOutputOrder = new LinkedList<>();
        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        for (final PlatformEvent event : inputOrder) {
            final PlatformEvent returnedEvent = futureEventBuffer.addEvent(event);
            if (returnedEvent != null) {
                actualOutputOrder.add(returnedEvent);
            }
        }

        // Update the event window to pending round 2 to release all events with a birth round of 2.
        final EventWindow eventWindow2 = testInput.eventWindowForPendingRound(2);
        actualOutputOrder.addAll(futureEventBuffer.updateEventWindow(eventWindow2));

        // Update the event window to pending round 2 to release all events with a birth round of 3.
        // This should release all remaining buffered events
        final EventWindow eventWindow3 = testInput.eventWindowForPendingRound(23);
        actualOutputOrder.addAll(futureEventBuffer.updateEventWindow(eventWindow3));
        assertThat(actualOutputOrder).isEqualTo(expectedOutputOrder);
    }

    /**
     * Tests that events are released from the future event buffer in the order that they were received in except that
     * they are released by birth round, assuming the event window is shifted to allow the release of all events.
     * <br>
     * This test adds events out of birth round order and verifies that the order of events released, relative to other
     * events with the same birth round, is the same. The event window is updated such that it skips a round.
     */
    @Test
    @DisplayName("events not received in birth round order, event window jumps ahead")
    void eventsAreReleasedInOrder_notReceivedInBirthRoundOrder_eventWindowJump() {
        final StaticTestInput testInput = new StaticTestInput();

        // In this test, events are not added in order of birth rounds. Therefore the
        // output order will not be the same.
        final List<PlatformEvent> inputOrder = List.of(
                testInput.a1,
                testInput.a2,
                testInput.a3,
                testInput.c1,
                testInput.c2,
                testInput.c3,
                testInput.b2,
                testInput.b3,
                testInput.b1);

        // Events should be released in batches by birth round, but within each batch the events
        // should be ordered according to the order they were received in.
        final Queue<PlatformEvent> expectedOutputOrder = new LinkedList<>(List.of(
                testInput.a1,
                testInput.c1,
                testInput.b1,
                testInput.a2,
                testInput.c2,
                testInput.b2,
                testInput.a3,
                testInput.c3,
                testInput.b3));

        final Queue<PlatformEvent> actualOutputOrder = new LinkedList<>();
        final FutureEventBuffer futureEventBuffer = pendingRoundFutureBuffer();

        for (final PlatformEvent event : inputOrder) {
            final PlatformEvent returnedEvent = futureEventBuffer.addEvent(event);
            if (returnedEvent != null) {
                actualOutputOrder.add(returnedEvent);
            }
        }

        // Updating the event window to pending round 3 should release all buffered events (events with
        // birth round 2 and 3)
        final EventWindow eventWindow = testInput.eventWindowForPendingRound(3);
        actualOutputOrder.addAll(futureEventBuffer.updateEventWindow(eventWindow));
        assertThat(actualOutputOrder).isEqualTo(expectedOutputOrder);
    }

    /**
     * The future event buffer has two options for buffering events, this test verifies that both options work as
     * expected.
     */
    @Test
    @DisplayName("Tests both future event buffering options")
    void eventBufferingOptions() {
        final FutureEventBuffer pendingBuffer = new FutureEventBuffer(METRICS, PENDING_CONSENSUS_ROUND);
        final FutureEventBuffer birthRoundBuffer = new FutureEventBuffer(METRICS, EVENT_BIRTH_ROUND);

        final long latestConsensusRound = 1;
        // the latest consensus round is 1, which means pending round is 2
        final long pendingRound = latestConsensusRound + 1;
        final long eventBirthRound = 1;

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setLatestConsensusRound(latestConsensusRound)
                .setNewEventBirthRound(eventBirthRound)
                .build();

        // update the window for both buffers
        pendingBuffer.updateEventWindow(eventWindow);
        birthRoundBuffer.updateEventWindow(eventWindow);

        // add events to both buffers
        final StaticTestInput testInput = new StaticTestInput();
        final List<PlatformEvent> pendingBufferEvents = testInput.allTestEvents().stream()
                .map(pendingBuffer::addEvent)
                .filter(Objects::nonNull)
                .toList();
        final List<PlatformEvent> birthRoundBufferEvents = testInput.allTestEvents().stream()
                .map(birthRoundBuffer::addEvent)
                .filter(Objects::nonNull)
                .toList();

        // validate the events
        assertThat(pendingBufferEvents)
                .withFailMessage(
                        "Events in the buffer configured with the PENDING_CONSENSUS_ROUND options should have a birth round less than or equal to %d"
                                .formatted(pendingRound))
                .map(PlatformEvent::getBirthRound)
                .allMatch(birthRound -> birthRound <= pendingRound);
        assertThat(birthRoundBufferEvents)
                .withFailMessage(
                        "Events in the buffer configured with the EVENT_BIRTH_ROUND options should have a birth round less than or equal to %d"
                                .formatted(eventBirthRound))
                .map(PlatformEvent::getBirthRound)
                .allMatch(birthRound -> birthRound <= eventBirthRound);
    }

    /**
     * A helper class that holds events with various birth round values.
     */
    private static class StaticTestInput {
        public final PlatformEvent a1;
        public final PlatformEvent b1;
        public final PlatformEvent c1;
        public final PlatformEvent a2;
        public final PlatformEvent b2;
        public final PlatformEvent c2;
        public final PlatformEvent a3;
        public final PlatformEvent b3;
        public final PlatformEvent c3;

        public StaticTestInput() {
            final Random random = getRandomPrintSeed();
            final NodeId alice = NodeId.of(0L);
            final NodeId bob = NodeId.of(1L);
            final NodeId carol = NodeId.of(2L);
            a1 = new TestingEventBuilder(random)
                    .setCreatorId(alice)
                    .setBirthRound(1)
                    .build();
            b1 = new TestingEventBuilder(random)
                    .setCreatorId(bob)
                    .setBirthRound(1)
                    .build();
            c1 = new TestingEventBuilder(random)
                    .setCreatorId(carol)
                    .setBirthRound(1)
                    .build();
            a2 = new TestingEventBuilder(random)
                    .setCreatorId(alice)
                    .setBirthRound(2)
                    .build();
            b2 = new TestingEventBuilder(random)
                    .setCreatorId(bob)
                    .setBirthRound(2)
                    .build();
            c2 = new TestingEventBuilder(random)
                    .setCreatorId(carol)
                    .setBirthRound(2)
                    .build();
            a3 = new TestingEventBuilder(random)
                    .setCreatorId(alice)
                    .setBirthRound(3)
                    .build();
            b3 = new TestingEventBuilder(random)
                    .setCreatorId(bob)
                    .setBirthRound(3)
                    .build();
            c3 = new TestingEventBuilder(random)
                    .setCreatorId(carol)
                    .setBirthRound(3)
                    .build();
        }

        public List<PlatformEvent> allTestEvents() {
            return new ArrayList<>(List.of(a1, a2, a3, b1, b2, b3, c1, c2, c3));
        }

        public EventWindow getEventWindowForMaxBirthRound() {
            return EventWindowBuilder.builder().setLatestConsensusRound(3).build();
        }

        /**
         * Creates an event window that will cause all events with a birth round equal to or less than the
         * {@code newPendingRound} to be release from the future event buffer.
         *
         * @param newPendingRound the new pending round number
         * @return the event window
         */
        public EventWindow eventWindowForPendingRound(final long newPendingRound) {
            return EventWindowBuilder.builder()
                    .setLatestConsensusRound(newPendingRound - 1)
                    .build();
        }
    }
}
