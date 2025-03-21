// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static com.swirlds.platform.consensus.ConsensusTestArgs.BIRTH_ROUND_PLATFORM_CONTEXT;
import static com.swirlds.platform.consensus.ConsensusTestArgs.DEFAULT_PLATFORM_CONTEXT;
import static com.swirlds.platform.test.fixtures.event.EventUtils.areEventListsEquivalent;
import static com.swirlds.platform.test.fixtures.event.EventUtils.areGenerationNumbersValid;
import static com.swirlds.platform.test.fixtures.event.EventUtils.isEventOrderValid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.emitter.CollectingEventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.PriorityEventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.ShuffledEventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sanity checks for the event generator utilities.
 */
@DisplayName("Event Emitter Tests")
public class EventEmitterTests {

    /**
     * Assert that two lists of events are distinct but equal objects.
     */
    private void assertEventListEquality(final List<EventImpl> events1, final List<EventImpl> events2) {
        assertEquals(events1.size(), events2.size());
        for (int index = 0; index < events1.size(); index++) {
            final EventImpl event1 = events1.get(index);
            final EventImpl event2 = events2.get(index);

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

        final List<EventImpl> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        emitter.reset();

        final List<EventImpl> events2 = emitter.emitEvents(numberOfEvents);
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

        final List<EventImpl> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final List<EventImpl> events2 = emitterCopy.emitEvents(numberOfEvents);
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

        final List<EventImpl> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final List<EventImpl> events2 = emitterCopy.emitEvents(numberOfEvents);
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
        final List<EventImpl> events1 = emitter.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events1.size());

        final EventEmitter emitterCopy = emitter.cleanCopy();

        emitterCopy.setCheckpoint(numberOfEvents);
        emitterCopy.skip(numberOfEvents);
        emitterCopy.setCheckpoint(numberOfEvents * 2);
        final List<EventImpl> events2 = emitterCopy.emitEvents(numberOfEvents);
        assertEquals(numberOfEvents, events2.size());

        assertEventListEquality(events1, events2);

        emitter.reset();
    }

    /**
     * Check that events emitted by this emitter are in the proper order.
     */
    public void validateEventOrder(final EventEmitter emitter) {
        System.out.println("Validate Event Order");
        final List<EventImpl> events = emitter.emitEvents(1000);
        assertTrue(areGenerationNumbersValid(events, emitter.getGraphGenerator().getNumberOfSources()));
        assertTrue(isEventOrderValid(events));

        emitter.reset();
    }

    public void validateCollectedEvents(EventEmitter emitter) {
        System.out.println("Validate Collected Events");

        emitter = emitter.cleanCopy();

        CollectingEventEmitter collectingEmitter = new CollectingEventEmitter(emitter);
        final List<EventImpl> events = collectingEmitter.emitEvents(1000);
        List<EventImpl> eventsCollected = collectingEmitter.getCollectedEvents();
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
        final List<EventImpl> list1 = emitter1.emitEvents(numberOfEvents);
        final List<EventImpl> list2 = emitter2.emitEvents(numberOfEvents);

        assertTrue(areEventListsEquivalent(list1, list2));
        assertNotEquals(list1, list2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Standard Emitter")
    public void testStandardEmitter(final boolean birthRoundAsAncientThreshold) {
        final PlatformContext platformContext =
                birthRoundAsAncientThreshold ? BIRTH_ROUND_PLATFORM_CONTEXT : DEFAULT_PLATFORM_CONTEXT;
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(platformContext)
                .build();
        emitterSanityChecks(emitter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Shuffled Emitter")
    public void testShuffledEmitter(final boolean birthRoundAsAncientThreshold) {
        final PlatformContext platformContext =
                birthRoundAsAncientThreshold ? BIRTH_ROUND_PLATFORM_CONTEXT : DEFAULT_PLATFORM_CONTEXT;
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(platformContext)
                .build();

        final int numberOfEvents = 1000;

        final ShuffledEventEmitter shuffledEmitter = new ShuffledEventEmitter(standardEmitter.getGraphGenerator(), 0);

        shuffledEmitterSanityChecks(shuffledEmitter);

        standardEmitter.setCheckpoint(numberOfEvents);

        // We expect for the events that come out of this emitter to be the same as a standard emitter,
        // just in a different order.
        assertOrderIsDifferent(shuffledEmitter.cleanCopy(), standardEmitter, numberOfEvents);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Test Priority Emitter")
    public void testPriorityEmitter(final boolean birthRoundAsAncientThreshold) {
        final PlatformContext platformContext =
                birthRoundAsAncientThreshold ? BIRTH_ROUND_PLATFORM_CONTEXT : DEFAULT_PLATFORM_CONTEXT;
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(platformContext)
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Shuffled Emitter Equivalence")
    public void shuffledEmitterEquivalence(final boolean birthRoundAsAncientThreshold) {
        final int numberOfEvents = 100;
        //		int maxSequenceNumber = (int) (numberOfEvents / 8 * 0.9);

        final PlatformContext platformContext =
                birthRoundAsAncientThreshold ? BIRTH_ROUND_PLATFORM_CONTEXT : DEFAULT_PLATFORM_CONTEXT;
        final StandardEventEmitter standardEmitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(platformContext)
                .build();

        final ShuffledEventEmitter shuffledEmitter = new ShuffledEventEmitter(standardEmitter.getGraphGenerator(), 0L);
        shuffledEmitter.setCheckpoint(numberOfEvents);

        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                final ShuffledEventEmitter emitter1 = shuffledEmitter.cleanCopy(i);
                final ShuffledEventEmitter emitter2 = shuffledEmitter.cleanCopy(j);

                final List<EventImpl> list1 = emitter1.emitEvents(numberOfEvents);
                final List<EventImpl> list2 = emitter2.emitEvents(numberOfEvents);

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Collecting Emitter Equivalence")
    public void collectingEmitterTest(final boolean birthRoundAsAncientThreshold) {
        System.out.println("Validate Collected Events");

        final PlatformContext platformContext =
                birthRoundAsAncientThreshold ? BIRTH_ROUND_PLATFORM_CONTEXT : DEFAULT_PLATFORM_CONTEXT;
        final StandardEventEmitter emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(0)
                .setPlatformContext(platformContext)
                .build();

        CollectingEventEmitter collectingEmitter = new CollectingEventEmitter(emitter);

        emitterSanityChecks(collectingEmitter);

        collectingEmitter.reset();

        final List<EventImpl> events = collectingEmitter.emitEvents(1000);
        List<EventImpl> eventsCollected = collectingEmitter.getCollectedEvents();
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
