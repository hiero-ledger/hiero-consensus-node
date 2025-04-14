// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomInstant;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class FutureEventBufferTests {
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

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final FutureEventBuffer futureEventBuffer = new DefaultFutureEventBuffer(platformContext);

        final long nonAncientBirthRound = 100;
        final long pendingConsensusRound = nonAncientBirthRound * 2;
        final long maxFutureRound = nonAncientBirthRound * 3;

        final EventWindow eventWindow =
                new EventWindow(pendingConsensusRound - 1, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);

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
        events.sort(Comparator.comparingLong(a -> a.getBirthRound()));

        final List<PlatformEvent> futureEvents = new ArrayList<>();
        for (final PlatformEvent event : events) {
            final List<PlatformEvent> returnedEvents = futureEventBuffer.addEvent(event);
            assertTrue(returnedEvents == null || returnedEvents.size() == 1);
            final PlatformEvent returnedEvent = returnedEvents == null ? null : returnedEvents.get(0);
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

            final EventWindow newEventWindow =
                    new EventWindow(newPendingConsensusRound - 1, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);

            final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);

            for (final PlatformEvent event : bufferedEvents) {
                assertEquals(newPendingConsensusRound, event.getBirthRound());
                unBufferedEvents.add(event);
            }
        }

        // When we are finished, we should have all of the future events in the same order that they were inserted.
        assertEquals(futureEvents, unBufferedEvents);

        // Make a big window shift. There should be no events that come out of the buffer.
        final EventWindow newEventWindow =
                new EventWindow(pendingConsensusRound * 1000, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);
        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertTrue(bufferedEvents.isEmpty());
    }

    /**
     * It is plausible that we have a big jump in rounds due to a reconnect. Verify that we don't emit events if they
     * become ancient while buffered.
     */
    @Test
    void eventsGoAncientWhileBufferedTest() {
        final Random random = getRandomPrintSeed();

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final FutureEventBuffer futureEventBuffer = new DefaultFutureEventBuffer(platformContext);

        final long nonAncientBirthRound = 100;
        final long pendingConsensusRound = nonAncientBirthRound * 2;
        final long maxFutureRound = nonAncientBirthRound * 3;

        final EventWindow eventWindow =
                new EventWindow(pendingConsensusRound - 1, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);

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
        events.sort(Comparator.comparingLong(a -> a.getBirthRound()));

        for (final PlatformEvent event : events) {
            final List<PlatformEvent> returnedEvents = futureEventBuffer.addEvent(event);
            assertTrue(returnedEvents == null || returnedEvents.size() == 1);
            final PlatformEvent returnedEvent = returnedEvents == null ? null : returnedEvents.get(0);
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

        final EventWindow newEventWindow =
                new EventWindow(pendingConsensusRound * 1000, nonAncientBirthRound * 1000, 1, BIRTH_ROUND_THRESHOLD);

        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertTrue(bufferedEvents.isEmpty());
    }

    /**
     * Verify that an event that is buffered gets released at the exact moment we expect.
     */
    @Test
    void eventInBufferIsReleasedOnTimeTest() {
        final Random random = getRandomPrintSeed();

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        final FutureEventBuffer futureEventBuffer = new DefaultFutureEventBuffer(platformContext);

        final long pendingConsensusRound = random.nextLong(100, 1_000);
        final long nonAncientBirthRound = pendingConsensusRound / 2;

        final EventWindow eventWindow =
                new EventWindow(pendingConsensusRound - 1, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);
        futureEventBuffer.updateEventWindow(eventWindow);

        final long roundsUntilRelease = random.nextLong(10, 20);
        final long eventBirthRound = pendingConsensusRound + roundsUntilRelease;
        final PlatformEvent event = new TestingEventBuilder(random)
                .setBirthRound(eventBirthRound)
                .setCreatorId(NodeId.of(random.nextInt(100)))
                .setTimeCreated(randomInstant(random))
                .build();

        // Event is from the future, we can't release it yet
        assertNull(futureEventBuffer.addEvent(event));

        // While the (newPendingConsensusRound-1) is less than the event's birth round, the event should be buffered
        for (long currentConsensusRound = pendingConsensusRound - 1;
                currentConsensusRound < eventBirthRound - 1;
                currentConsensusRound++) {

            final EventWindow newEventWindow =
                    new EventWindow(currentConsensusRound, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);
            final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
            assertTrue(bufferedEvents.isEmpty());
        }

        // When the pending consensus round is equal to the event's birth round, the event should be released
        // Note: the pending consensus round is equal to the current consensus round + 1, but the argument
        // for an event window takes the current consensus round, not the pending consensus round.
        // To land with the pending consensus round at the exact value as the event's birth round, we need to
        // set the current consensus round to the event's birth round - 1.

        final EventWindow newEventWindow =
                new EventWindow(eventBirthRound - 1, nonAncientBirthRound, 1, BIRTH_ROUND_THRESHOLD);
        final List<PlatformEvent> bufferedEvents = futureEventBuffer.updateEventWindow(newEventWindow);
        assertEquals(1, bufferedEvents.size());
        assertSame(event, bufferedEvents.getFirst());
    }
}
