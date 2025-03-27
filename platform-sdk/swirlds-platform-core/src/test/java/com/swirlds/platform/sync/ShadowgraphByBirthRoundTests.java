// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.shadowgraph.ReservedEventWindow;
import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphInsertionException;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.fixtures.sync.SyncTestUtils;
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
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Shadowgraph By Birth Round Tests")
class ShadowgraphByBirthRoundTests {

    private List<EventImpl> generatedEvents;
    private HashMap<Hash, Set<Hash>> ancestorsMap;
    private Shadowgraph shadowGraph;
    private Map<Long, Set<ShadowEvent>> birthRoundToShadows;
    private long maxBirthRound;
    private StandardEventEmitter emitter;
    private PlatformContext platformContext;

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
        birthRoundToShadows = new HashMap<>();
    }

    private void initShadowGraph(final Random random, final int numEvents, final int numNodes) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)
                .getOrCreateConfig();

        platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();
        emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(random.nextLong())
                .setNumNodes(numNodes)
                .build();

        shadowGraph = new Shadowgraph(platformContext, numNodes, new NoOpIntakeEventCounter());
        shadowGraph.updateEventWindow(EventWindow.getGenesisEventWindow(BIRTH_ROUND_THRESHOLD));

        for (int i = 0; i < numEvents; i++) {
            final EventImpl event = emitter.emitEvent();

            final Hash hash = event.getBaseHash();
            ancestorsMap.put(hash, ancestorsOf(event.getBaseEvent().getAllParents()));
            assertDoesNotThrow(
                    () -> shadowGraph.addEvent(event.getBaseEvent()), "Unable to insert event into shadow graph.");
            assertTrue(
                    shadowGraph.isHashInGraph(hash),
                    "Event that was just added to the shadow graph should still be in the shadow graph.");
            generatedEvents.add(event);
            if (!birthRoundToShadows.containsKey(event.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD))) {
                birthRoundToShadows.put(
                        event.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD), new HashSet<>());
            }
            birthRoundToShadows
                    .get(event.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD))
                    .add(shadowGraph.shadow(event.getBaseEvent().getDescriptor()));
            if (event.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD) > maxBirthRound) {
                maxBirthRound = event.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD);
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

        initShadowGraph(random, numEvents, numNodes);

        final Set<ShadowEvent> generatedShadows = generatedEvents.stream()
                .map(e -> e.getBaseEvent().getDescriptor())
                .map(shadowGraph::shadow)
                .collect(Collectors.toSet());

        final Set<ShadowEvent> generatedShadowsSubset = generatedShadows.stream()
                .filter((hash) -> random.nextDouble() < 0.5)
                .collect(Collectors.toSet());

        final Set<Hash> actualAncestors = shadowGraph.findAncestors(generatedShadowsSubset, (e) -> true).stream()
                .map(ShadowEvent::getEventBaseHash)
                .collect(Collectors.toSet());

        for (final ShadowEvent shadowEvent : generatedShadowsSubset) {
            assertSetsContainSameHashes(ancestorsMap.get(shadowEvent.getEventBaseHash()), actualAncestors);
        }
    }

    @RepeatedTest(10)
    void testFindAncestorsExcludesExpiredEvents() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        final long expireBelowBirthRound = random.nextInt(10) + 1;

        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                expireBelowBirthRound,
                BIRTH_ROUND_THRESHOLD);

        shadowGraph.updateEventWindow(eventWindow);

        final Set<ShadowEvent> allEvents = shadowGraph.findAncestors(shadowGraph.getTips(), (e) -> true);
        for (final ShadowEvent event : allEvents) {
            assertTrue(
                    event.getEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD) >= expireBelowBirthRound,
                    "Ancestors should not include expired events.");
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
        initShadowGraph(random, numEvents, numNodes);

        final ReservedEventWindow r1 = shadowGraph.reserve();
        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "First reservation should reserve birth round 1");
        assertEquals(
                1,
                r1.getReservationCount(),
                "The first call to reserve() after initialization should result in 1 reservation.");

        r1.close();
        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");
    }

    /**
     * This test verifies multiple reservations of the same birth round without any event expiry.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testMultipleReservationsNoExpiry(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, numEvents, numNodes);

        final ReservedEventWindow r1 = shadowGraph.reserve();
        final ReservedEventWindow r2 = shadowGraph.reserve();
        assertEquals(r1.getEventWindow(), r2.getEventWindow());
        assertEquals(
                ROUND_FIRST,
                r2.getEventWindow().getExpiredThreshold(),
                "Second reservation should reserve birth round 1");
        assertEquals(2, r2.getReservationCount(), "The second call to reserve() should result in 2 reservations.");

        r2.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");
    }

    /**
     * This test verifies multiple reservations of the same birth round with event expiry.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testMultipleReservationsWithExpiry(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, numEvents, numNodes);

        final long expireBelowBirthRound = ROUND_FIRST + 1;

        final ReservedEventWindow r1 = shadowGraph.reserve();
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                expireBelowBirthRound,
                BIRTH_ROUND_THRESHOLD);
        shadowGraph.updateEventWindow(eventWindow);

        final ReservedEventWindow r2 = shadowGraph.reserve();
        assertNotEquals(
                r1,
                r2,
                "The call to reserve() after the expire() method is called should not return the same reservation "
                        + "instance.");
        assertEquals(
                expireBelowBirthRound,
                r2.getEventWindow().getExpiredThreshold(),
                "Reservation after call to expire() should reserve the expired birth round + 1");
        assertEquals(
                1, r2.getReservationCount(), "The first reservation after expire() should result in 1 reservation.");

        r2.close();

        assertEquals(
                expireBelowBirthRound,
                r2.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r2.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().getExpiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
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
        initShadowGraph(random, numEvents, numNodes);

        final long expireBelowBirthRound = random.nextInt((int) maxBirthRound) + 2;
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                expireBelowBirthRound,
                BIRTH_ROUND_THRESHOLD);
        shadowGraph.updateEventWindow(eventWindow);

        assertEventsBelowBirthRoundAreExpired(expireBelowBirthRound);
    }

    private void assertEventsBelowBirthRoundAreExpired(final long expireBelowBirthRound) {
        birthRoundToShadows.forEach((birthRound, shadowSet) -> {
            if (birthRound < expireBelowBirthRound) {
                shadowSet.forEach((shadow) -> {
                    assertNull(
                            shadow.getSelfParent(), "Expired events should have their self parent reference nulled.");
                    assertNull(
                            shadow.getOtherParent(), "Expired events should have their other parent reference nulled.");
                    assertFalse(
                            shadowGraph.isHashInGraph(shadow.getEventBaseHash()),
                            "Events in an expire birth round should not be in the shadow graph.");
                });
            } else {
                shadowSet.forEach(shadow -> assertTrue(
                        shadowGraph.isHashInGraph(shadow.getEventBaseHash()),
                        "Events in a non-expired birth round should be in the shadow graph."));
            }
        });
    }

    /**
     * Tests that event expiry works correctly when there are reservations for birth rounds that should be expired.
     *
     * @param numEvents the number of events to put in the shadow graph
     * @param numNodes  the number of nodes in the shadow graph
     */
    @ParameterizedTest
    @MethodSource("graphSizes")
    void testExpireWithReservation(final int numEvents, final int numNodes) {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, numEvents, numNodes);
        SyncTestUtils.printEvents("generated events", generatedEvents);

        final ReservedEventWindow r0 = shadowGraph.reserve();
        shadowGraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                ROUND_FIRST + 1,
                BIRTH_ROUND_THRESHOLD));
        final ReservedEventWindow r1 = shadowGraph.reserve();
        shadowGraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                ROUND_FIRST + 2,
                BIRTH_ROUND_THRESHOLD));
        final ReservedEventWindow r2 = shadowGraph.reserve();

        // release the middle reservation to ensure that birth rounds
        // greater than the lowest reserved birth round are not expired.
        r1.close();

        // release the last reservation to ensure that the reservation
        // list is iterated through in the correct order
        r2.close();

        // Attempt to expire everything up to
        shadowGraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                ROUND_FIRST + 2,
                BIRTH_ROUND_THRESHOLD));

        // No event should have been expired because the first birth round is reserved
        assertEventsBelowBirthRoundAreExpired(0);

        r0.close();
        shadowGraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                ROUND_FIRST + 2,
                BIRTH_ROUND_THRESHOLD));

        // Now that the reservation is closed, ensure that the events below birth round 2 are expired
        assertEventsBelowBirthRoundAreExpired(ROUND_FIRST + 2);
    }

    @Test
    void testShadow() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertNull(shadowGraph.shadow((EventDescriptorWrapper) null), "Passing null should return null.");
        final EventImpl event = emitter.emitEvent();
        assertDoesNotThrow(() -> shadowGraph.addEvent(event.getBaseEvent()), "Adding an tip event should succeed.");
        assertEquals(
                event.getBaseHash(),
                shadowGraph.shadow(event.getBaseEvent().getDescriptor()).getEventBaseHash(),
                "Shadow event hash should match the original event hash.");
    }

    @Test
    void testShadowsNullListThrowsNPE() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertThrows(
                NullPointerException.class,
                () -> shadowGraph.shadows(null),
                "Passing null should cause a NullPointerException.");
    }

    @Test
    void testShadows() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        final List<EventImpl> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(
                () -> shadowGraph.addEvent(e.getBaseEvent()), "Adding new tip events should succeed."));

        final List<Hash> hashes = events.stream().map(EventImpl::getBaseHash).collect(Collectors.toList());
        final List<ShadowEvent> shadows = shadowGraph.shadows(hashes);
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
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        final List<EventImpl> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(
                () -> shadowGraph.addEvent(e.getBaseEvent()), "Adding new tip events should succeed."));

        final List<Hash> knownHashes =
                events.stream().map(EventImpl::getBaseHash).collect(Collectors.toList());
        final List<Hash> unknownHashes =
                emitter.emitEvents(10).stream().map(EventImpl::getBaseHash).collect(Collectors.toList());

        final List<Hash> allHashes = new ArrayList<>(knownHashes.size() + unknownHashes.size());
        allHashes.addAll(knownHashes);
        allHashes.addAll(unknownHashes);
        Collections.shuffle(allHashes);

        final List<ShadowEvent> shadows = shadowGraph.shadows(allHashes);
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
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertThrows(
                NullPointerException.class,
                () -> shadowGraph.addEvent((PlatformEvent) null),
                "A null event should not be added to the shadow graph.");
    }

    @RepeatedTest(10)
    void testAddDuplicateEvent() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 10, 4);
        final EventImpl randomDuplicateEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertThrows(
                ShadowgraphInsertionException.class,
                () -> shadowGraph.addEvent(randomDuplicateEvent.getBaseEvent()),
                "An event that is already in the shadow graph should not be added.");
    }

    /**
     * Test that an event with a birth round that has been expired from the shadow graph is not added to the graph.
     */
    @Test
    void testAddEventWithExpiredBirthRound() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        shadowGraph.updateEventWindow(new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                ROUND_FIRST + 1,
                BIRTH_ROUND_THRESHOLD));
        birthRoundToShadows.get(ROUND_FIRST).forEach(shadow -> {
            shadowGraph.addEvent(shadow.getEvent());
            assertNull(shadowGraph.getEvent(shadow.getEvent().getHash()));
        });
    }

    @Test
    void testAddEventWithUnknownOtherParent() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        newEvent.setOtherParent(emitter.emitEvent());

        assertDoesNotThrow(
                () -> shadowGraph.addEvent(newEvent.getBaseEvent()),
                "Events with an unknown other parent should be added.");
    }

    @Test
    void testAddEventWithUnknownSelfParent() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        newEvent.setSelfParent(emitter.emitEvent());

        assertDoesNotThrow(
                () -> shadowGraph.addEvent(newEvent.getBaseEvent()),
                "Events with an unknown self parent should be added.");
    }

    @Test
    void testAddEventWithExpiredParents() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final EventImpl newEvent = emitter.emitEvent();
        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                newEvent.getBaseEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD),
                BIRTH_ROUND_THRESHOLD);
        shadowGraph.updateEventWindow(eventWindow);

        assertDoesNotThrow(
                () -> shadowGraph.addEvent(newEvent.getBaseEvent()), "Events with expired parents should be added.");
    }

    @Test
    void testAddEventUpdatesTips() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final int tipsSize = shadowGraph.getTips().size();
        final int additionalEvents = 100;

        for (int i = 0; i < additionalEvents; i++) {
            final EventImpl newTip = emitter.emitEvent();
            assertNull(
                    shadowGraph.shadow(newTip.getBaseEvent().getDescriptor()),
                    "The shadow graph should not contain the new event.");
            assertDoesNotThrow(
                    () -> shadowGraph.addEvent(newTip.getBaseEvent()),
                    "The new tip should be added to the shadow graph.");

            final ShadowEvent tipShadow =
                    shadowGraph.shadow(newTip.getBaseEvent().getDescriptor());

            assertEquals(
                    tipsSize,
                    shadowGraph.getTips().size(),
                    "There are no forks, so the number of tips should stay the same.");
            assertTrue(shadowGraph.getTips().contains(tipShadow), "The tips should now contain the new tip.");
            assertFalse(
                    shadowGraph.getTips().contains(tipShadow.getSelfParent()),
                    "The tips should not contain the new tip's self parent.");
        }
    }

    @Test
    void testHashgraphEventWithNullHash() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        assertNull(shadowGraph.hashgraphEvent(null), "Passing a null hash should result in a null return value.");
    }

    @RepeatedTest(10)
    void testHashgraphEventWithExistingHash() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        final EventImpl randomExistingEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertEquals(
                randomExistingEvent.getBaseEvent(),
                shadowGraph.hashgraphEvent(randomExistingEvent.getBaseHash()),
                "Unexpected event returned.");
    }

    @Test
    void testClear() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        ReservedEventWindow r0 = shadowGraph.reserve();
        final ReservedEventWindow r1 = shadowGraph.reserve();
        r0.close();
        r1.close();

        shadowGraph.clear();
        shadowGraph.updateEventWindow(EventWindow.getGenesisEventWindow(BIRTH_ROUND_THRESHOLD));

        assertEquals(0, shadowGraph.getTips().size(), "Shadow graph should not have any tips after being cleared.");
        for (final EventImpl generatedEvent : generatedEvents) {
            assertNull(
                    shadowGraph.shadow(generatedEvent.getBaseEvent().getDescriptor()),
                    "Shadow graph should not have any events after being cleared.");
        }
        r0 = shadowGraph.reserve();
        assertEquals(
                1,
                r0.getEventWindow().getExpiredThreshold(),
                "The first reservation after clearing should reserve birth round 1.");
        assertEquals(
                1, r0.getReservationCount(), "The first reservation after clearing should have a single reservation.");
    }

    @Test
    @DisplayName("Test that clear() disconnect all shadow events in the shadow graph")
    void testClearDisconnects() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        final List<ShadowEvent> tips = shadowGraph.getTips();
        final Set<ShadowEvent> shadows = new HashSet<>();
        for (final ShadowEvent tip : tips) {
            ShadowEvent sp = tip.getSelfParent();
            while (sp != null) {
                shadows.add(sp);
                sp = sp.getSelfParent();
            }
            shadows.add(tip);
        }

        shadowGraph.clear();

        for (final ShadowEvent s : shadows) {
            assertNull(s.getSelfParent(), "after a clear, all parents should be disconnected");
            assertNull(s.getOtherParent(), "after a clear, all parents should be disconnected");
        }
    }

    @RepeatedTest(10)
    void testTipsExpired() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        long oldestTipBirthRound = Long.MAX_VALUE;
        final List<ShadowEvent> tipsToExpire = new ArrayList<>();
        for (final ShadowEvent tip : shadowGraph.getTips()) {
            oldestTipBirthRound =
                    Math.min(oldestTipBirthRound, tip.getEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD));
        }

        for (final ShadowEvent tip : shadowGraph.getTips()) {
            if (tip.getEvent().getAncientIndicator(BIRTH_ROUND_THRESHOLD) == oldestTipBirthRound) {
                tipsToExpire.add(tip);
            }
        }

        final int numTipsBeforeExpiry = shadowGraph.getTips().size();
        assertTrue(numTipsBeforeExpiry > 0, "Shadow graph should have tips after events are added.");

        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                1 /* ignored by shadowgraph */,
                oldestTipBirthRound + 1,
                BIRTH_ROUND_THRESHOLD);
        shadowGraph.updateEventWindow(eventWindow);

        assertEquals(
                numTipsBeforeExpiry - tipsToExpire.size(),
                shadowGraph.getTips().size(),
                "Shadow graph tips should be included in expiry.");
    }
}
