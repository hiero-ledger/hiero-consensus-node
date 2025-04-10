// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.model.event.EventConstants.FIRST_GENERATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.shadowgraph.ReservedEventWindow;
import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphInsertionException;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.fixtures.sync.SyncTestUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Shadowgraph Tests")
class ShadowgraphTest {

    private List<EventImpl> generatedEvents;
    private HashMap<Hash, Set<Hash>> ancestorsMap;
    private Shadowgraph shadowgraph;
    private Map<Long, Set<ShadowEvent>> genToShadows;
    private long maxGen;
    private StandardEventEmitter emitter;

    private static Stream<Arguments> graphSizes() {
        return Stream.of(
                Arguments.of(10, 4),
                Arguments.of(100, 4),
                Arguments.of(1000, 4),
                Arguments.of(10, 10),
                Arguments.of(100, 10),
                Arguments.of(1000, 10));
    }

    @BeforeEach
    public void setup() {
        ancestorsMap = new HashMap<>();
        generatedEvents = new ArrayList<>();
        genToShadows = new HashMap<>();
    }

    private void initShadowgraph(final Random random, final int numEvents, final int numNodes) {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(random.nextLong())
                .setNumNodes(numNodes)
                .build();
        shadowgraph = new Shadowgraph(platformContext, numNodes, new NoOpIntakeEventCounter());
        shadowgraph.updateEventWindow(EventWindow.getGenesisEventWindow(GENERATION_THRESHOLD));

        for (int i = 0; i < numEvents; i++) {
            final EventImpl event = emitter.emitEvent();

            final Hash hash = event.getBaseHash();
            ancestorsMap.put(hash, ancestorsOf(event.getBaseEvent().getAllParents()));
            assertDoesNotThrow(
                    () -> shadowgraph.addEvent(event.getBaseEvent()), "Unable to insert event into shadow graph.");
            assertTrue(
                    shadowgraph.isHashInGraph(hash),
                    "Event that was just added to the shadow graph should still be in the shadow graph.");
            generatedEvents.add(event);
            if (!genToShadows.containsKey(event.getGeneration())) {
                genToShadows.put(event.getGeneration(), new HashSet<>());
            }
            genToShadows
                    .get(event.getGeneration())
                    .add(shadowgraph.shadow(event.getBaseEvent().getDescriptor()));
            if (event.getGeneration() > maxGen) {
                maxGen = event.getGeneration();
            }
        }
    }

    /**
     * Tests that the {@link Shadowgraph#findAncestors(Iterable, Predicate)} returns the correct set of ancestors.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testFindAncestorsForMultipleEvents(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();

        initShadowgraph(random, numEvents, numNodes);

        final Set<ShadowEvent> generatedShadows = generatedEvents.stream()
                .map(EventImpl::getBaseEvent)
                .map(PlatformEvent::getDescriptor)
                .map(shadowgraph::shadow)
                .collect(Collectors.toSet());

        final Set<ShadowEvent> generatedShadowsSubset = generatedShadows.stream()
                .filter((hash) -> random.nextDouble() < 0.5)
                .collect(Collectors.toSet());

        final Set<Hash> actualAncestors = shadowgraph.findAncestors(generatedShadowsSubset, (e) -> true).stream()
                .map(ShadowEvent::getEventBaseHash)
                .collect(Collectors.toSet());

        for (final ShadowEvent shadowEvent : generatedShadowsSubset) {
            assertSetsContainSameHashes(ancestorsMap.get(shadowEvent.getEventBaseHash()), actualAncestors);
        }
    }

    @RepeatedTest(10)
    void testFindAncestorsExcludesExpiredEvents() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 100, 4);

        final long expireBelowGen = random.nextInt(10) + 1;

        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */, 0 /* ignored by shadowgraph */, expireBelowGen, GENERATION_THRESHOLD);

        shadowgraph.updateEventWindow(eventWindow);

        final Set<ShadowEvent> allEvents = shadowgraph.findAncestors(shadowgraph.getTips(), (e) -> true);
        for (final ShadowEvent event : allEvents) {
            assertTrue(
                    event.getEvent().getGeneration() >= expireBelowGen, "Ancestors should not include expired events.");
        }
    }

    private void assertSetsContainSameHashes(final Set<Hash> expected, final Set<Hash> actual) {
        for (final Hash hash : expected) {
            if (!actual.contains(hash)) {
                fail(String.format("Expected to find an ancestor with hash %s", hash.toHex(4)));
            }
        }
    }

    private Set<Hash> ancestorsOf(final List<EventDescriptorWrapper> parents) {
        final Set<Hash> ancestorSet = new HashSet<>();
        for (final EventDescriptorWrapper parent : parents) {
            ancestorSet.add(parent.hash());
            if (ancestorsMap.containsKey(parent.hash())) {
                ancestorSet.addAll(ancestorsMap.get(parent.hash()));
            }
        }
        return ancestorSet;
    }

    /**
     * This test verifies a single reservation can be made and closed without any event expiry.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testSingleReservation(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, numEvents, numNodes);

        final ReservedEventWindow r1 = shadowgraph.reserve();
        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "First reservation should reserve generation 1");
        assertEquals(
                1,
                r1.getReservationCount(),
                "The first call to reserve() after initialization should result in 1 reservation.");

        r1.close();
        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r1.getEventWindow().getExpiredThreshold(),
                "Closing the second reservation should decrement the number of reservations.");
    }

    /**
     * This test verifies multiple reservations of the same generation without any event expiry.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testMultipleReservationsNoExpiry(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, numEvents, numNodes);

        final ReservedEventWindow r1 = shadowgraph.reserve();
        final ReservedEventWindow r2 = shadowgraph.reserve();
        assertEquals(r1.getEventWindow(), r2.getEventWindow());
        assertEquals(
                FIRST_GENERATION,
                r2.getEventWindow().getExpiredThreshold(),
                "Second reservation should reserve generation 1");
        assertEquals(2, r2.getReservationCount(), "The second call to reserve() should result in 2 reservations.");

        r2.close();

        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");
    }

    /**
     * This test verifies multiple reservations of the same generation with event expiry.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testMultipleReservationsWithExpiry(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, numEvents, numNodes);

        final long expireBelowGen = FIRST_GENERATION + 1;

        final ReservedEventWindow r1 = shadowgraph.reserve();
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */, 0 /* ignored by shadowgraph */, expireBelowGen, GENERATION_THRESHOLD);
        shadowgraph.updateEventWindow(eventWindow);

        final ReservedEventWindow r2 = shadowgraph.reserve();
        assertNotEquals(
                r1,
                r2,
                "The call to reserve() after the expire() method is called should not return the same reservation "
                        + "instance.");
        assertEquals(
                expireBelowGen,
                r2.getEventWindow().getExpiredThreshold(),
                "Reservation after call to expire() should reserve the expired generation + 1");
        assertEquals(
                1, r2.getReservationCount(), "The first reservation after expire() should result in 1 reservation.");

        r2.close();

        assertEquals(
                expireBelowGen,
                r2.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r2.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                FIRST_GENERATION,
                r1.getEventWindow().getExpiredThreshold(),
                "The generation should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");
    }

    /**
     * This test verifies that event expiry works correctly when there are no reservations.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testExpireNoReservations(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, numEvents, numNodes);

        final long expireBelowGen = random.nextInt((int) maxGen) + 2;
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */, 0 /* ignored by shadowgraph */, expireBelowGen, GENERATION_THRESHOLD);
        shadowgraph.updateEventWindow(eventWindow);

        assertEventsBelowGenAreExpired(expireBelowGen);
    }

    private void assertEventsBelowGenAreExpired(final long expireBelowGen) {
        genToShadows.forEach((gen, shadowSet) -> {
            if (gen < expireBelowGen) {
                shadowSet.forEach((shadow) -> {
                    assertNull(
                            shadow.getSelfParent(), "Expired events should have their self parent reference nulled.");
                    assertNull(
                            shadow.getOtherParent(), "Expired events should have their other parent reference nulled.");
                    assertFalse(
                            shadowgraph.isHashInGraph(shadow.getEventBaseHash()),
                            "Events in an expire generation should not be in the shadow graph.");
                });
            } else {
                shadowSet.forEach(shadow -> assertTrue(
                        shadowgraph.isHashInGraph(shadow.getEventBaseHash()),
                        "Events in a non-expired generation should be in the shadow graph."));
            }
        });
    }

    /**
     * Tests that event expiry works correctly when there are reservations for generations that should be expired.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testExpireWithReservation(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, numEvents, numNodes);
        SyncTestUtils.printEvents("generated events", generatedEvents);

        final ReservedEventWindow r0 = shadowgraph.reserve();
        shadowgraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                0 /* ignored by shadowgraph */,
                FIRST_GENERATION + 1,
                GENERATION_THRESHOLD));
        final ReservedEventWindow r1 = shadowgraph.reserve();
        shadowgraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                0 /* ignored by shadowgraph */,
                FIRST_GENERATION + 2,
                GENERATION_THRESHOLD));
        final ReservedEventWindow r2 = shadowgraph.reserve();

        // release the middle reservation to ensure that generations
        // greater than the lowest reserved generation are not expired.
        r1.close();

        // release the last reservation to ensure that the reservation
        // list is iterated through in the correct order
        r2.close();

        // Attempt to expire everything up to
        shadowgraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                0 /* ignored by shadowgraph */,
                FIRST_GENERATION + 2,
                GENERATION_THRESHOLD));

        // No event should have been expired because the first generation is reserved
        assertEventsBelowGenAreExpired(0);

        r0.close();
        shadowgraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                0 /* ignored by shadowgraph */,
                FIRST_GENERATION + 2,
                GENERATION_THRESHOLD));

        // Now that the reservation is closed, ensure that the events in the below generation 2 are expired
        assertEventsBelowGenAreExpired(FIRST_GENERATION + 2);
    }

    @Test
    void testShadow() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertNull(shadowgraph.shadow((EventDescriptorWrapper) null), "Passing null should return null.");
        final EventImpl event = emitter.emitEvent();
        assertDoesNotThrow(() -> shadowgraph.addEvent(event.getBaseEvent()), "Adding an tip event should succeed.");
        assertEquals(
                event.getBaseHash(),
                shadowgraph.shadow(event.getBaseEvent().getDescriptor()).getEventBaseHash(),
                "Shadow event hash should match the original event hash.");
    }

    @Test
    void testShadowsNullListThrowsNPE() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertThrows(
                NullPointerException.class,
                () -> shadowgraph.shadows(null),
                "Passing null should cause a NullPointerException.");
    }

    @Test
    void testShadows() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        final List<EventImpl> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(
                () -> shadowgraph.addEvent(e.getBaseEvent()), "Adding new tip events should succeed."));

        final List<Hash> hashes = events.stream().map(EventImpl::getBaseHash).collect(Collectors.toList());
        final List<ShadowEvent> shadows = shadowgraph.shadows(hashes);
        assertEquals(
                events.size(),
                shadows.size(),
                "The number of shadow events should match the number of events provided.");

        for (final ShadowEvent shadow : shadows) {
            assertTrue(
                    hashes.contains(shadow.getEventBaseHash()),
                    "Each event provided should have a shadow event with the same hash.");
        }
    }

    @Test
    void testShadowsWithUnknownEvents() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        final List<EventImpl> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(
                () -> shadowgraph.addEvent(e.getBaseEvent()), "Adding new tip events should succeed."));

        final List<Hash> knownHashes =
                events.stream().map(EventImpl::getBaseHash).collect(Collectors.toList());
        final List<Hash> unknownHashes =
                emitter.emitEvents(10).stream().map(EventImpl::getBaseHash).collect(Collectors.toList());

        final List<Hash> allHashes = new ArrayList<>(knownHashes.size() + unknownHashes.size());
        allHashes.addAll(knownHashes);
        allHashes.addAll(unknownHashes);
        Collections.shuffle(allHashes);

        final List<ShadowEvent> shadows = shadowgraph.shadows(allHashes);
        assertEquals(
                allHashes.size(),
                shadows.size(),
                "The number of shadow events should match the number of hashes provided.");

        for (int i = 0; i < allHashes.size(); i++) {
            final Hash hash = allHashes.get(i);
            if (knownHashes.contains(hash)) {
                assertEquals(
                        hash,
                        shadows.get(i).getEventBaseHash(),
                        "Each known hash provided should have a shadow event with the same hash.");
            } else {
                assertNull(shadows.get(i), "Each unknown hash provided should have a null shadow event.");
            }
        }
    }

    @Test
    void testAddNullEvent() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertThrows(
                NullPointerException.class,
                () -> shadowgraph.addEvent((PlatformEvent) null),
                "A null event should not be added to the shadow graph.");
    }

    @RepeatedTest(10)
    void testAddDuplicateEvent() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 10, 4);
        final EventImpl randomDuplicateEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertThrows(
                ShadowgraphInsertionException.class,
                () -> shadowgraph.addEvent(randomDuplicateEvent.getBaseEvent()),
                "An event that is already in the shadow graph should not be added.");
    }

    @Test
    void testAddEventWithUnknownOtherParent() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        newEvent.setOtherParent(emitter.emitEvent());

        assertDoesNotThrow(
                () -> shadowgraph.addEvent(newEvent.getBaseEvent()),
                "Events with an unknown other parent should be added.");
    }

    @Test
    void testAddEventWithUnknownSelfParent() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        newEvent.setSelfParent(emitter.emitEvent());

        assertDoesNotThrow(
                () -> shadowgraph.addEvent(newEvent.getBaseEvent()),
                "Events with an unknown self parent should be added.");
    }

    @Test
    void testAddEventWithExpiredParents() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                0 /* ignored by shadowgraph */,
                newEvent.getGeneration(),
                GENERATION_THRESHOLD);
        shadowgraph.updateEventWindow(eventWindow);

        assertDoesNotThrow(
                () -> shadowgraph.addEvent(newEvent.getBaseEvent()), "Events with expired parents should be added.");
    }

    @Test
    void testAddEventUpdatesTips() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final int tipsSize = shadowgraph.getTips().size();
        final int additionalEvents = 100;

        for (int i = 0; i < additionalEvents; i++) {
            final EventImpl newTip = emitter.emitEvent();
            assertNull(
                    shadowgraph.shadow(newTip.getBaseEvent().getDescriptor()),
                    "The shadow graph should not contain the new event.");
            assertDoesNotThrow(
                    () -> shadowgraph.addEvent(newTip.getBaseEvent()),
                    "The new tip should be added to the shadow graph.");

            final ShadowEvent tipShadow =
                    shadowgraph.shadow(newTip.getBaseEvent().getDescriptor());

            assertEquals(
                    tipsSize,
                    shadowgraph.getTips().size(),
                    "There are no forks, so the number of tips should stay the same.");
            assertTrue(shadowgraph.getTips().contains(tipShadow), "The tips should now contain the new tip.");
            assertFalse(
                    shadowgraph.getTips().contains(tipShadow.getSelfParent()),
                    "The tips should not contain the new tip's self parent.");
        }
    }

    @Test
    void testHashgraphEventWithNullHash() {
        initShadowgraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        assertNull(shadowgraph.hashgraphEvent(null), "Passing a null hash should result in a null return value.");
    }

    @RepeatedTest(10)
    void testHashgraphEventWithExistingHash() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 100, 4);

        final EventImpl randomExistingEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertEquals(
                randomExistingEvent.getBaseEvent(),
                shadowgraph.hashgraphEvent(randomExistingEvent.getBaseHash()),
                "Unexpected event returned.");
    }

    @Test
    void testClear() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 100, 4);

        ReservedEventWindow r0 = shadowgraph.reserve();
        final ReservedEventWindow r1 = shadowgraph.reserve();
        r0.close();
        r1.close();

        shadowgraph.clear();
        shadowgraph.updateEventWindow(EventWindow.getGenesisEventWindow(GENERATION_THRESHOLD));

        assertEquals(0, shadowgraph.getTips().size(), "Shadow graph should not have any tips after being cleared.");
        for (final EventImpl generatedEvent : generatedEvents) {
            assertNull(
                    shadowgraph.shadow(generatedEvent.getBaseEvent().getDescriptor()),
                    "Shadow graph should not have any events after being cleared.");
        }
        r0 = shadowgraph.reserve();
        assertEquals(
                0,
                r0.getEventWindow().getExpiredThreshold(),
                "The first reservation after clearing should reserve generation 0.");
        assertEquals(
                1, r0.getReservationCount(), "The first reservation after clearing should have a single reservation.");
    }

    @Test
    @DisplayName("Test that clear() disconnect all shadow events in the shadow graph")
    void testClearDisconnects() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 100, 4);

        final List<ShadowEvent> tips = shadowgraph.getTips();
        final Set<ShadowEvent> shadows = new HashSet<>();
        for (final ShadowEvent tip : tips) {
            ShadowEvent sp = tip.getSelfParent();
            while (sp != null) {
                shadows.add(sp);
                sp = sp.getSelfParent();
            }
            shadows.add(tip);
        }

        shadowgraph.clear();

        for (final ShadowEvent s : shadows) {
            assertNull(s.getSelfParent(), "after a clear, all parents should be disconnected");
            assertNull(s.getOtherParent(), "after a clear, all parents should be disconnected");
        }
    }

    @RepeatedTest(10)
    void testTipsExpired() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowgraph(random, 100, 4);

        long oldestTipGen = Long.MAX_VALUE;
        final List<ShadowEvent> tipsToExpire = new ArrayList<>();
        for (final ShadowEvent tip : shadowgraph.getTips()) {
            oldestTipGen = Math.min(oldestTipGen, tip.getEvent().getGeneration());
        }

        for (final ShadowEvent tip : shadowgraph.getTips()) {
            if (tip.getEvent().getGeneration() == oldestTipGen) {
                tipsToExpire.add(tip);
            }
        }

        final int numTipsBeforeExpiry = shadowgraph.getTips().size();
        assertTrue(numTipsBeforeExpiry > 0, "Shadow graph should have tips after events are added.");

        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */, 0 /* ignored by shadowgraph */, oldestTipGen + 1, GENERATION_THRESHOLD);
        shadowgraph.updateEventWindow(eventWindow);

        assertEquals(
                numTipsBeforeExpiry - tipsToExpire.size(),
                shadowgraph.getTips().size(),
                "Shadow graph tips should be included in expiry.");
    }

    @Test
    @Disabled("It does not make sense to run this test in CCI since the outcome can vary depending on the load."
            + "The purpose of this test is to tune the performance of this method by running the test locally.")
    void findAncestorsPerformance() throws ShadowgraphInsertionException {
        final int numEvents = 200_000;
        final int numNodes = 21;
        final int numRuns = 10;

        final Random random = RandomUtils.getRandomPrintSeed();
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(random.nextLong())
                .setNumNodes(numNodes)
                .build();
        shadowgraph = new Shadowgraph(platformContext, numNodes, new NoOpIntakeEventCounter());
        for (int i = 0; i < numEvents; i++) {
            shadowgraph.addEvent(emitter.emitEvent().getBaseEvent());
        }

        long millisMin = Integer.MAX_VALUE;
        for (int i = 0; i < numRuns; i++) {
            final List<ShadowEvent> tips = shadowgraph.getTips();
            final Instant start = Instant.now();
            final Set<ShadowEvent> ancestors =
                    shadowgraph.findAncestors(tips, s -> s.getEvent().getGeneration() >= 0);
            final long millis = start.until(Instant.now(), ChronoUnit.MILLIS);
            System.out.printf("Run %d took %d ms\n", i, millis);

            millisMin = Math.min(millis, millisMin);
        }
        double eventsPerSec = numEvents / (millisMin / 1000d);

        System.out.println("Min time taken: " + millisMin);
        System.out.printf("Events/sec: %,.3f\n", eventsPerSec);

        assertTrue(
                eventsPerSec > 5_000_000,
                "this was the performance of this test the last time it was run on a Dell Precision 5540");
    }
}
