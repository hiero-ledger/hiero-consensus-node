// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.EventUtils.areBirthRoundNumbersValid;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.EventUtils.areEventListsEquivalent;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.EventUtils.isEventOrderValid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import java.util.List;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.CollectingEventEmitter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.EventEmitter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.EventEmitterBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.PriorityEventEmitter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.ShuffledEventEmitter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.StandardEventEmitter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sanity checks for the event generator utilities.
 */
@DisplayName("Event Emitter Tests")
public class EventEmitterTests {

    private static final PlatformContext DEFAULT_PLATFORM_CONTEXT =
            TestPlatformContextBuilder.create().build();

    /**
     * Assert that two lists of events are distinct but equal objects.
     */
    private void assertEventListEquality(final List<PlatformEvent> events1, final List<PlatformEvent> events2) {
        assertEquals(events1.size(), events2.size());
        for (int index = 0; index < events1.size(); index++) {
            final PlatformEvent event1 = events1.get(index);
            final PlatformEvent event2 = events2.get(index);

            assertNotSame(event1, event2);
            assertEquals(event1, event2);
        }
    }

    /**
     * Ensure that a generator has same output after a reset.
     */
    public void validateReset(final EventEmitter emitter) {
        System.out.println("Validate Reset");
        final int numberOfEvents = 1000;

        emitter.reset();

        final List<PlatformEvent> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        emitter.reset();

        final List<PlatformEvent> events2 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events2.size());

        assertEventListEquality(events1, events2);

        emitter.reset();
    }

    /**
     * Ensure that a copy made of a new emitter has same output.
     */
    public void validateCopyOfNewEmitter(final EventEmitter emitter) {
        System.out.println("Validate Copy of New Emitter");
        final EventEmitter emitterCopy = emitter.copy();

        final int numberOfEvents = 1000;

        final List<PlatformEvent> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final List<PlatformEvent> events2 = emitterCopy.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events2.size());

        assertEventListEquality(events1, events2);

        emitter.reset();
    }

    /**
     * Ensure that a copy made of an active emitter has same output.
     */
    public void validateCopyOfActiveEmitter(final EventEmitter emitter) {
        System.out.println("Validate Copy of Active Emitter");

        final int numberOfEvents = 10;

        emitter.skip(numberOfEvents);
        final EventEmitter emitterCopy = emitter.copy();

        final List<PlatformEvent> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final List<PlatformEvent> events2 = emitterCopy.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events2.size());

        assertEventListEquality(events1, events2);

        emitter.reset();
    }

    /**
     * Ensure that a clean copy made of an active emitter has same output.
     */
    public void validateCleanCopyOfActiveGenerator(final EventEmitter emitter) {
        System.out.println("Validate Clean Copy of Active Emitter");

        final int numberOfEvents = 1000;

        emitter.setCheckpoint(numberOfEvents);
        emitter.skip(numberOfEvents);
        emitter.setCheckpoint(numberOfEvents * 2);
        final List<PlatformEvent> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final EventEmitter emitterCopy = emitter.cleanCopy();

        emitterCopy.setCheckpoint(numberOfEvents);
        emitterCopy.skip(numberOfEvents);
        emitterCopy.setCheckpoint(numberOfEvents * 2);
        final List<PlatformEvent> events2 = emitterCopy.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events2.size());

        assertEventListEquality(events1, events2);

        emitter.reset();
    }

    /**
     * Check that events emitted by this emitter are in the proper order.
     */
    public void validateEventOrder(final EventEmitter emitter) {
        System.out.println("Validate Event Order");
        final List<PlatformEvent> events = emitter.emitEvents(1000);
        assertTrue(areBirthRoundNumbersValid(events, emitter.getGraphGenerator().getNumberOfSources()));
        assertTrue(isEventOrderValid(events));

        emitter.reset();
    }

    public void validateCollectedEvents(EventEmitter emitter) {
        System.out.println("Validate Collected Events");

        emitter = emitter.cleanCopy();

        CollectingEventEmitter collectingEmitter = new CollectingEventEmitter(emitter);
        final List<PlatformEvent> events = collectingEmitter.emitEvents(1000);
        List<PlatformEvent> eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);

        // Resetting the collected generator should produce the same events again
        collectingEmitter.reset();
        collectingEmitter.emitEvents(1000);
        eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);

        // Taking a clean copy of the collected generator should produce the same events again
        collectingEmitter = collectingEmitter.cleanCopy();
        collectingEmitter.emitEvents(1000);
        eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);
    }

    /**
     * Make sure the copy constructor that changes the seed works.
     */
    public void validateCopyWithNewSeed(final EventEmitter emitter) {
        System.out.println("Validate Copy With New Seed");
        final EventEmitter emitter1 = emitter.cleanCopy();
        final EventEmitter emitter2 = emitter.cleanCopy(1234);

        assertNotEquals(emitter1.emitEvents(1000), emitter2.emitEvents(1000));
    }

    /**
     * Run an emitter through a gauntlet of sanity checks.
     */
    public void emitterSanityChecks(final EventEmitter emitter) {
        validateReset(emitter);
        validateCopyOfNewEmitter(emitter);
        validateCopyOfActiveEmitter(emitter);
        validateCleanCopyOfActiveGenerator(emitter);
        validateEventOrder(emitter);
        validateCollectedEvents(emitter);
    }

    public void shuffledEmitterSanityChecks(final EventEmitter emitter) {
        emitterSanityChecks(emitter);
        validateCopyWithNewSeed(emitter);
    }

    /**
     * Assert that two emitters with the same generator emit the same events but in a different order.
     */
    public void assertOrderIsDifferent(
            final EventEmitter emitter1, final EventEmitter emitter2, final int numberOfEvents) {
        emitter1.setCheckpoint(numberOfEvents);
        emitter2.setCheckpoint(numberOfEvents);
        final List<PlatformEvent> list1 = emitter1.emitEvents(numberOfEvents);
        final List<PlatformEvent> list2 = emitter2.emitEvents(numberOfEvents);

        assertTrue(areEventListsEquivalent(list1, list2));
        assertNotEquals(list1, list2);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Standard Emitter")
    public void testStandardEmitter() {
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(DEFAULT_PLATFORM_CONTEXT)
                .build();
        emitterSanityChecks(emitter);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Shuffled Emitter")
    public void testShuffledEmitter() {
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(DEFAULT_PLATFORM_CONTEXT)
                .build();

        final int numberOfEvents = 1000;

        final ShuffledEventEmitter shuffledEmitter = new ShuffledEventEmitter(standardEmitter.getGraphGenerator(), 0);

        shuffledEmitterSanityChecks(shuffledEmitter);

        standardEmitter.setCheckpoint(numberOfEvents);

        // We expect for the events that come out of this emitter to be the same as a standard emitter,
        // just in a different order.
        assertOrderIsDifferent(shuffledEmitter.cleanCopy(), standardEmitter, numberOfEvents);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Priority Emitter")
    public void testPriorityEmitter() {
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(DEFAULT_PLATFORM_CONTEXT)
                .build();

        final int numberOfEvents = 1000;

        final List<Integer> nodePriorities = List.of(0, 1, 2, 3);
        final PriorityEventEmitter priorityEmitter =
                new PriorityEventEmitter(standardEmitter.getGraphGenerator(), nodePriorities);
        priorityEmitter.setCheckpoint(numberOfEvents);

        emitterSanityChecks(priorityEmitter);

        standardEmitter.setCheckpoint(numberOfEvents);

        // We expect for the events that come out of this emitter to be the same as a standard emitter,
        // just in a different order.
        assertOrderIsDifferent(priorityEmitter.cleanCopy(), standardEmitter, numberOfEvents);
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Shuffled Emitter Equivalence")
    public void shuffledEmitterEquivalence() {
        final int numberOfEvents = 100;
        //		int maxSequenceNumber = (int) (numberOfEvents / 8 * 0.9);
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(DEFAULT_PLATFORM_CONTEXT)
                .build();

        final ShuffledEventEmitter shuffledEmitter = new ShuffledEventEmitter(standardEmitter.getGraphGenerator(), 0L);
        shuffledEmitter.setCheckpoint(numberOfEvents);

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                final ShuffledEventEmitter emitter1 = shuffledEmitter.cleanCopy(i);
                final ShuffledEventEmitter emitter2 = shuffledEmitter.cleanCopy(j);

                final List<PlatformEvent> list1 = emitter1.emitEvents(numberOfEvents);
                final List<PlatformEvent> list2 = emitter2.emitEvents(numberOfEvents);

                if (i == j) {
                    // Two instances of the same emitter should produce identical events
                    assertEquals(list1, list2);
                } else {
                    // Two instances with different seeds should produce equivalent but not identical lists
                    assertTrue(areEventListsEquivalent(list1, list2));
                    assertNotEquals(list1, list2);
                }
            }
        }
    }

    @Test
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Collecting Emitter Equivalence")
    public void collectingEmitterTest() {
        System.out.println("Validate Collected Events");
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(DEFAULT_PLATFORM_CONTEXT)
                .build();

        CollectingEventEmitter collectingEmitter = new CollectingEventEmitter(emitter);

        emitterSanityChecks(collectingEmitter);

        collectingEmitter.reset();

        final List<PlatformEvent> events = collectingEmitter.emitEvents(1000);
        List<PlatformEvent> eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);

        // Resetting the collected generator should produce the same events again
        collectingEmitter.reset();
        collectingEmitter.emitEvents(1000);
        eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);

        // Taking a clean copy of the collected generator should produce the same events again
        collectingEmitter = collectingEmitter.cleanCopy();
        collectingEmitter.emitEvents(1000);
        eventsCollected = collectingEmitter.getCollectedEvents();
        assertEquals(events, eventsCollected);
    }
}
