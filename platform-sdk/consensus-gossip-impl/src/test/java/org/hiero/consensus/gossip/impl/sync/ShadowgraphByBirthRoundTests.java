// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.sync;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.platform.test.fixtures.graph.SimpleGraph;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
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
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ReservedEventWindow;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowEvent;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.Shadowgraph;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowgraphInsertionException;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.EventEmitterBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.StandardEventEmitter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Shadowgraph By Birth Round Tests")
class ShadowgraphByBirthRoundTests {
    /**
     * In some tests we need to retry multiple times to emit an event to get the type of event we need. This constant
     * puts a limit on the number of retries to avoid an infinite loop. If everything works as expected, this limit
     * should never be reached.
     */
    private static final int EMIT_RETRIES = 1000;

    private List<PlatformEvent> generatedEvents;
    private HashMap<Hash, Set<Hash>> ancestorsMap;
    private Shadowgraph shadowGraph;
    private Map<Long, Set<ShadowEvent>> birthRoundToShadows;
    private long maxBirthRound;
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
        birthRoundToShadows = new HashMap<>();
    }

    private void initShadowGraph(final Random random, final int numEvents, final int numNodes) {
        emitter = EventEmitterBuilder.newBuilder()
                .setRandomSeed(random.nextLong())
                .setNumNodes(numNodes)
                .build();

        shadowGraph = new Shadowgraph(new NoOpMetrics(), numNodes, new NoOpIntakeEventCounter());
        shadowGraph.updateEventWindow(EventWindow.getGenesisEventWindow());

        for (int i = 0; i < numEvents; i++) {
            final PlatformEvent event = emitter.emitEvent();

            final Hash hash = event.getHash();
            ancestorsMap.put(hash, ancestorsOf(event.getAllParents()));
            assertDoesNotThrow(() -> shadowGraph.addEvent(event), "Unable to insert event into shadow graph.");
            assertTrue(
                    shadowGraph.isHashInGraph(hash),
                    "Event that was just added to the shadow graph should still be in the shadow graph.");
            generatedEvents.add(event);
            if (!birthRoundToShadows.containsKey(event.getBirthRound())) {
                birthRoundToShadows.put(event.getBirthRound(), new HashSet<>());
            }
            birthRoundToShadows.get(event.getBirthRound()).add(shadowGraph.shadow(event.getDescriptor()));
            if (event.getBirthRound() > maxBirthRound) {
                maxBirthRound = event.getBirthRound();
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
                .map(PlatformEvent::getDescriptor)
                .map(shadowGraph::shadow)
                .collect(Collectors.toSet());

        final Set<ShadowEvent> generatedShadowsSubset = generatedShadows.stream()
                .filter((hash) -> random.nextDouble() < 0.5)
                .collect(Collectors.toSet());

        final Set<Hash> actualAncestors = shadowGraph.findAncestors(generatedShadowsSubset, (e) -> true).stream()
                .map(ShadowEvent::getBaseHash)
                .collect(Collectors.toSet());

        for (final ShadowEvent shadowEvent : generatedShadowsSubset) {
            assertSetsContainSameHashes(ancestorsMap.get(shadowEvent.getBaseHash()), actualAncestors);
        }
    }

    @RepeatedTest(10)
    void testFindAncestorsExcludesExpiredEvents() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 100, 4);

        final long expireBelowBirthRound = random.nextInt(10) + 1;

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setExpiredThreshold(expireBelowBirthRound)
                .build();

        shadowGraph.updateEventWindow(eventWindow);

        final Set<ShadowEvent> allEvents = shadowGraph.findAncestors(shadowGraph.getTips(), (e) -> true);
        for (final ShadowEvent event : allEvents) {
            assertTrue(
                    event.getPlatformEvent().getBirthRound() >= expireBelowBirthRound,
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
                ROUND_FIRST, r1.getEventWindow().expiredThreshold(), "First reservation should reserve birth round 1");
        assertEquals(
                1,
                r1.getReservationCount(),
                "The first call to reserve() after initialization should result in 1 reservation.");

        r1.close();
        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().expiredThreshold(),
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
                ROUND_FIRST, r2.getEventWindow().expiredThreshold(), "Second reservation should reserve birth round 1");
        assertEquals(2, r2.getReservationCount(), "The second call to reserve() should result in 2 reservations.");

        r2.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().expiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().expiredThreshold(),
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
        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setExpiredThreshold(expireBelowBirthRound)
                .build();
        shadowGraph.updateEventWindow(eventWindow);

        final ReservedEventWindow r2 = shadowGraph.reserve();
        assertNotEquals(
                r1,
                r2,
                "The call to reserve() after the expire() method is called should not return the same reservation "
                        + "instance.");
        assertEquals(
                expireBelowBirthRound,
                r2.getEventWindow().expiredThreshold(),
                "Reservation after call to expire() should reserve the expired birth round + 1");
        assertEquals(
                1, r2.getReservationCount(), "The first reservation after expire() should result in 1 reservation.");

        r2.close();

        assertEquals(
                expireBelowBirthRound,
                r2.getEventWindow().expiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                0,
                r2.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().expiredThreshold(),
                "The birth round should not be affected by a reservation being closed.");
        assertEquals(
                1,
                r1.getReservationCount(),
                "Closing the second reservation should decrement the number of reservations.");

        r1.close();

        assertEquals(
                ROUND_FIRST,
                r1.getEventWindow().expiredThreshold(),
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
        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setExpiredThreshold(expireBelowBirthRound)
                .build();
        shadowGraph.updateEventWindow(eventWindow);

        assertEventsBelowBirthRoundAreExpired(expireBelowBirthRound);
    }

    private void assertEventsBelowBirthRoundAreExpired(final long expireBelowBirthRound) {
        birthRoundToShadows.forEach((birthRound, shadowSet) -> {
            if (birthRound < expireBelowBirthRound) {
                shadowSet.forEach((shadow) -> {
                    assertTrue(
                            shadow.getAllParents().isEmpty(),
                            "Expired events should have their parents references nulled.");
                    assertFalse(
                            shadowGraph.isHashInGraph(shadow.getBaseHash()),
                            "Events in an expire birth round should not be in the shadow graph.");
                });
            } else {
                shadowSet.forEach(shadow -> assertTrue(
                        shadowGraph.isHashInGraph(shadow.getBaseHash()),
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
        printEvents(generatedEvents);

        final ReservedEventWindow r0 = shadowGraph.reserve();
        shadowGraph.updateEventWindow(EventWindowBuilder.builder()
                .setExpiredThreshold(ROUND_FIRST + 1)
                .build());
        final ReservedEventWindow r1 = shadowGraph.reserve();
        shadowGraph.updateEventWindow(EventWindowBuilder.builder()
                .setExpiredThreshold(ROUND_FIRST + 2)
                .build());
        final ReservedEventWindow r2 = shadowGraph.reserve();

        // release the middle reservation to ensure that birth rounds
        // greater than the lowest reserved birth round are not expired.
        r1.close();

        // release the last reservation to ensure that the reservation
        // list is iterated through in the correct order
        r2.close();

        // Attempt to expire everything up to
        shadowGraph.updateEventWindow(EventWindowBuilder.builder()
                .setExpiredThreshold(ROUND_FIRST + 2)
                .build());

        // No event should have been expired because the first birth round is reserved
        assertEventsBelowBirthRoundAreExpired(0);

        r0.close();
        shadowGraph.updateEventWindow(EventWindowBuilder.builder()
                .setExpiredThreshold(ROUND_FIRST + 2)
                .build());

        // Now that the reservation is closed, ensure that the events below birth round 2 are expired
        assertEventsBelowBirthRoundAreExpired(ROUND_FIRST + 2);
    }

    private static void printEvents(final Collection<PlatformEvent> events) {
        System.out.println("\n--- " + "generated events" + " ---");
        events.forEach(System.out::println);
    }

    @Test
    void testShadow() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        assertNull(shadowGraph.shadow(null), "Passing null should return null.");
        final PlatformEvent event = emitter.emitEvent();
        assertDoesNotThrow(() -> shadowGraph.addEvent(event), "Adding an tip event should succeed.");
        assertEquals(
                event.getHash(),
                shadowGraph.shadow(event.getDescriptor()).getBaseHash(),
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
        final List<PlatformEvent> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(() -> shadowGraph.addEvent(e), "Adding new tip events should succeed."));

        final List<Hash> hashes = events.stream().map(PlatformEvent::getHash).collect(Collectors.toList());
        final List<ShadowEvent> shadows = shadowGraph.shadows(hashes);
        assertEquals(
                events.size(),
                shadows.size(),
                "The number of shadow events should match the number of events provided.");

        for (final ShadowEvent shadow : shadows) {
            assertTrue(
                    hashes.contains(shadow.getBaseHash()),
                    "Each event provided should have a shadow event with the same hash.");
        }
    }

    @Test
    void testShadowsWithUnknownEvents() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 0, 4);
        final List<PlatformEvent> events = emitter.emitEvents(10);
        events.forEach(e -> assertDoesNotThrow(() -> shadowGraph.addEvent(e), "Adding new tip events should succeed."));

        final List<Hash> knownHashes =
                events.stream().map(PlatformEvent::getHash).toList();
        final List<Hash> unknownHashes =
                emitter.emitEvents(10).stream().map(PlatformEvent::getHash).toList();

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
                        shadows.get(i).getBaseHash(),
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
                () -> shadowGraph.addEvent(null),
                "A null event should not be added to the shadow graph.");
    }

    @RepeatedTest(10)
    void testAddDuplicateEvent() {
        final Random random = RandomUtils.getRandomPrintSeed();
        initShadowGraph(random, 10, 4);
        final PlatformEvent randomDuplicateEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertThrows(
                ShadowgraphInsertionException.class,
                () -> shadowGraph.addEvent(randomDuplicateEvent),
                "An event that is already in the shadow graph should not be added.");
    }

    /**
     * Test that an event with a birth round that has been expired from the shadow graph is not added to the graph.
     */
    @Test
    void testAddEventWithExpiredBirthRound() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        shadowGraph.updateEventWindow(EventWindowBuilder.builder()
                .setExpiredThreshold(ROUND_FIRST + 1)
                .build());
        birthRoundToShadows.get(ROUND_FIRST).forEach(shadow -> {
            shadowGraph.addEvent(shadow.getPlatformEvent());
            assertNull(shadowGraph.getEvent(shadow.getPlatformEvent().getHash()));
        });
    }

    @Test
    void testAddEventWithUnknownOtherParent() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final PlatformEvent parent = emitter.emitEvent();

        // emit events until we get one that has the above event as an other-parent
        PlatformEvent child = null;
        for (int i = 0; i < EMIT_RETRIES; i++) {
            child = emitter.emitEvent();
            final Set<Hash> parentsSet = child.getOtherParents().stream()
                    .map(EventDescriptorWrapper::hash)
                    .collect(Collectors.toSet());
            if (parentsSet.contains(parent.getHash())) {
                break;
            }
        }

        final PlatformEvent finalChild = child;
        assertDoesNotThrow(
                () -> shadowGraph.addEvent(finalChild), "Events with an unknown other parent should be added.");
    }

    @Test
    void testAddEventWithUnknownSelfParent() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final PlatformEvent parent = emitter.emitEvent();
        // emit events until we get one that has the above event as a self-parent
        PlatformEvent child = null;
        for (int i = 0; i < EMIT_RETRIES; i++) {
            child = emitter.emitEvent();
            if (child.getSelfParent() != null && child.getSelfParent().hash().equals(parent.getHash())) {
                break;
            }
        }

        final PlatformEvent finalChild = child;
        assertDoesNotThrow(
                () -> shadowGraph.addEvent(finalChild), "Events with an unknown self parent should be added.");
    }

    @Test
    void testAddEventWithExpiredParents() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final PlatformEvent newEvent = emitter.emitEvent();
        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setExpiredThreshold(newEvent.getBirthRound())
                .build();
        shadowGraph.updateEventWindow(eventWindow);

        assertDoesNotThrow(() -> shadowGraph.addEvent(newEvent), "Events with expired parents should be added.");
    }

    @Test
    void testAddEventUpdatesTips() {
        initShadowGraph(RandomUtils.getRandomPrintSeed(), 100, 4);

        final int tipsSize = shadowGraph.getTips().size();
        final int additionalEvents = 100;

        for (int i = 0; i < additionalEvents; i++) {
            final PlatformEvent newTip = emitter.emitEvent();
            assertNull(
                    shadowGraph.shadow(newTip.getDescriptor()), "The shadow graph should not contain the new event.");
            assertDoesNotThrow(() -> shadowGraph.addEvent(newTip), "The new tip should be added to the shadow graph.");

            final ShadowEvent tipShadow = shadowGraph.shadow(newTip.getDescriptor());

            assertEquals(
                    tipsSize,
                    shadowGraph.getTips().size(),
                    "There are no branches, so the number of tips should stay the same.");
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

        final PlatformEvent randomExistingEvent = generatedEvents.get(random.nextInt(generatedEvents.size()));
        assertEquals(
                randomExistingEvent,
                shadowGraph.hashgraphEvent(randomExistingEvent.getHash()),
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
        shadowGraph.updateEventWindow(EventWindow.getGenesisEventWindow());

        assertEquals(0, shadowGraph.getTips().size(), "Shadow graph should not have any tips after being cleared.");
        for (final PlatformEvent generatedEvent : generatedEvents) {
            assertNull(
                    shadowGraph.shadow(generatedEvent.getDescriptor()),
                    "Shadow graph should not have any events after being cleared.");
        }
        r0 = shadowGraph.reserve();
        assertEquals(
                1,
                r0.getEventWindow().expiredThreshold(),
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
            assertTrue(s.getAllParents().isEmpty(), "after a clear, all parents should be disconnected");
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
                    Math.min(oldestTipBirthRound, tip.getPlatformEvent().getBirthRound());
        }

        for (final ShadowEvent tip : shadowGraph.getTips()) {
            if (tip.getPlatformEvent().getBirthRound() == oldestTipBirthRound) {
                tipsToExpire.add(tip);
            }
        }

        final int numTipsBeforeExpiry = shadowGraph.getTips().size();
        assertTrue(numTipsBeforeExpiry > 0, "Shadow graph should have tips after events are added.");

        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setExpiredThreshold(oldestTipBirthRound + 1)
                .build();
        shadowGraph.updateEventWindow(eventWindow);

        assertEquals(
                numTipsBeforeExpiry - tipsToExpire.size(),
                shadowGraph.getTips().size(),
                "Shadow graph tips should be included in expiry.");
    }

    /**
     * Checks that the shadowgraph works correctly when events have multiple other-parents.
     */
    @Test
    void testMultipleOtherParents() {
        final Randotron randotron = Randotron.create();
        initShadowGraph(randotron, 0, 4);
        final SimpleGraph graph = SimpleGraphs.mopGraph(randotron);

        // add all events to shadow graph
        graph.events().forEach(shadowGraph::addEvent);

        // check the tips
        final Set<PlatformEvent> tips = shadowGraph.getTips().stream()
                .map(ShadowEvent::getPlatformEvent)
                .collect(Collectors.toSet());
        assertEquals(graph.eventSet(8, 9, 10, 11), tips, "Tips should be the last row of events in the MOP graph");

        checkAncestors(1, graph);
        checkAncestors(5, graph, 0, 1, 2);
        checkAncestors(10, graph, 0, 1, 2, 3, 5, 6, 7);
    }

    /**
     * Check that the ancestors of the event at the given index match the expected ancestors.
     *
     * @param indexToCheck            the index of the event to check
     * @param graph                   the graph of all events
     * @param expectedAncestorIndices the indices of the expected ancestors
     */
    private void checkAncestors(
            final int indexToCheck, @NonNull final SimpleGraph graph, final int... expectedAncestorIndices) {

        final ShadowEvent shadow = shadowGraph.shadow(graph.event(indexToCheck).getDescriptor());
        assertNotNull(shadow, "Shadow event for event %d should exist in the shadow graph".formatted(indexToCheck));
        final Set<PlatformEvent> ancestors = shadowGraph.findAncestors(List.of(shadow), e -> true).stream()
                .map(ShadowEvent::getPlatformEvent)
                .collect(Collectors.toSet());

        assertEquals(
                graph.eventSet(expectedAncestorIndices),
                ancestors,
                "Ancestors for event %d do not match expected ancestors".formatted(indexToCheck));
    }
}
