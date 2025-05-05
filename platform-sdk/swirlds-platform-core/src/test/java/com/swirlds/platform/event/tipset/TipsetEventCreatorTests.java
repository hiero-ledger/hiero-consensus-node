// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.assignNGenAndDistributeEvent;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.buildEventCreator;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.buildSimulatedNodes;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.createTestEvent;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.distributeEvent;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.generateRandomTransactions;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.registerEvent;
import static com.swirlds.platform.event.tipset.TipsetEventCreatorTestUtils.validateNewEvent;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TipsetEventCreatorImpl Tests")
class TipsetEventCreatorTests {

    /**
     * Nodes take turns creating events in a round-robin fashion.
     */
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Round Robin Test")
    void roundRobinTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                final PlatformEvent event = eventCreator.maybeCreateEvent();

                // In this test, it should be impossible for a node to be unable to create an event.
                assertNotNull(event);

                assignNGenAndDistributeEvent(nodes, events, event);

                if (advancingClock) {
                    assertEquals(event.getTimeCreated(), time.now());
                }

                validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);
            }
        }
    }

    /**
     * Each cycle, randomize the order in which nodes are asked to create events.
     */
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Random Order Test")
    void randomOrderTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {

            final List<RosterEntry> addresses = new ArrayList<>(roster.rosterEntries());
            Collections.shuffle(addresses, random);

            boolean atLeastOneEventCreated = false;

            for (final RosterEntry address : addresses) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                final PlatformEvent event = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (event == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                assignNGenAndDistributeEvent(nodes, events, event);

                if (advancingClock) {
                    assertEquals(event.getTimeCreated(), time.now());
                }
                validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);
            }

            assertTrue(atLeastOneEventCreated);
        }
    }

    /**
     * This test is very similar to the {@link #randomOrderTest(boolean, boolean)}, except that we repeat the test
     * several times using the same event creator. This fails when we do not clear the event creator in between runs,
     * but should not fail if we have cleared the vent creator.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Clear Test")
    void clearTest(final boolean advancingClock) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes =
                buildSimulatedNodes(random, time, roster, transactionSupplier::get, AncientMode.GENERATION_THRESHOLD);

        for (int i = 0; i < 5; i++) {
            final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

            for (int eventIndex = 0; eventIndex < 100; eventIndex++) {

                final List<RosterEntry> addresses = new ArrayList<>(roster.rosterEntries());
                Collections.shuffle(addresses, random);

                boolean atLeastOneEventCreated = false;

                for (final RosterEntry address : addresses) {
                    if (advancingClock) {
                        time.tick(Duration.ofMillis(10));
                    }

                    transactionSupplier.set(generateRandomTransactions(random));

                    final NodeId nodeId = NodeId.of(address.nodeId());
                    final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                    final PlatformEvent event = eventCreator.maybeCreateEvent();

                    // It's possible a node may not be able to create an event. But we are guaranteed
                    // to be able to create at least one event per cycle.
                    if (event == null) {
                        continue;
                    }
                    atLeastOneEventCreated = true;

                    assignNGenAndDistributeEvent(nodes, events, event);

                    if (advancingClock) {
                        assertEquals(event.getTimeCreated(), time.now());
                    }
                    validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);
                }

                assertTrue(atLeastOneEventCreated);
            }
            // Reset the test by calling clear. This test fails in the second iteration if we don't clear things
            // out.
            for (final SimulatedNode node : nodes.values()) {
                node.eventCreator().clear();

                // There are copies of these data structures inside the event creator. We maintain these ones
                // to sanity check the behavior of the event creator.
                node.tipsetTracker().clear();
                node.tipsetWeightCalculator().clear();
            }
            transactionSupplier.set(null);
        }
    }

    /**
     * Each node creates many events in a row without allowing others to take a turn. Eventually, a node should be
     * unable to create another event without first receiving an event from another node.
     */
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Create Many Events In A Row Test")
    void createManyEventsInARowTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {

                int count = 0;
                while (true) {
                    if (advancingClock) {
                        time.tick(Duration.ofMillis(10));
                    }

                    transactionSupplier.set(generateRandomTransactions(random));

                    final NodeId nodeId = NodeId.of(address.nodeId());
                    final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                    final PlatformEvent event = eventCreator.maybeCreateEvent();

                    if (count == 0) {
                        // The first time we attempt to create an event we should be able to do so.
                        assertNotNull(event);
                    } else if (event == null) {
                        // we can't create any more events
                        break;
                    }

                    assignNGenAndDistributeEvent(nodes, events, event);

                    if (advancingClock) {
                        assertEquals(event.getTimeCreated(), time.now());
                    }
                    validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);

                    // At best, we can create a genesis event and one event per node in the network.
                    // We are unlikely to create this many, but we definitely shouldn't be able to go beyond this.
                    assertTrue(count < networkSize);
                    count++;
                }
            }
        }
    }

    /**
     * The tipset algorithm must still build on top of zero weight nodes, even though they don't help consensus to
     * advance.
     */
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Zero Weight Node Test")
    void zeroWeightNodeTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        Roster roster = RandomRosterBuilder.create(random).withSize(networkSize).build();

        final NodeId zeroWeightNode = NodeId.of(roster.rosterEntries().get(0).nodeId());

        roster = Roster.newBuilder()
                .rosterEntries(roster.rosterEntries().stream()
                        .map(entry -> {
                            if (entry.nodeId() == zeroWeightNode.id()) {
                                return entry.copyBuilder().weight(0).build();
                            } else {
                                return entry.copyBuilder().weight(1).build();
                            }
                        })
                        .toList())
                .build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> allEvents = new HashMap<>();

        int zeroWeightNodeOtherParentCount = 0;

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {

            final List<RosterEntry> addresses = new ArrayList<>(roster.rosterEntries());
            Collections.shuffle(addresses, random);

            boolean atLeastOneEventCreated = false;

            for (final RosterEntry address : addresses) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                final PlatformEvent newEvent = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (newEvent == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                final NodeId otherId;
                if (newEvent.hasOtherParents()) {
                    otherId = newEvent.getOtherParents().getFirst().creator();
                } else {
                    otherId = null;
                }

                if (otherId != null && otherId.equals(zeroWeightNode)) {
                    zeroWeightNodeOtherParentCount++;
                }

                assignNGenAndDistributeEvent(nodes, allEvents, newEvent);

                if (advancingClock) {
                    assertEquals(newEvent.getTimeCreated(), time.now());
                }
                validateNewEvent(allEvents, newEvent, transactionSupplier.get(), nodes.get(nodeId), false);
            }

            assertTrue(atLeastOneEventCreated);
        }

        // This is just a heuristic. When running this, I typically see numbers around 100.
        // Essentially, we need to make sure that we are choosing the zero weight node's events
        // as other parents. Precisely how often is less important to this test, as long as we are
        // doing it at least some of the time.
        assertTrue(zeroWeightNodeOtherParentCount > 20);
    }

    /**
     * The tipset algorithm must still build on top of zero weight nodes, even though they don't help consensus to
     * advance. Further disadvantage the zero weight node by delaying the propagation of its events, so that others find
     * that they do not get transitive tipset score improvements by using it.
     */
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Zero Weight Slow Node Test")
    void zeroWeightSlowNodeTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        Roster roster = RandomRosterBuilder.create(random).withSize(networkSize).build();

        final NodeId zeroWeightNode = NodeId.of(roster.rosterEntries().get(0).nodeId());

        roster = Roster.newBuilder()
                .rosterEntries(roster.rosterEntries().stream()
                        .map(entry -> {
                            if (entry.nodeId() == zeroWeightNode.id()) {
                                return entry.copyBuilder().weight(0).build();
                            } else {
                                return entry.copyBuilder().weight(1).build();
                            }
                        })
                        .toList())
                .build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> allEvents = new HashMap<>();
        final List<PlatformEvent> slowNodeEvents = new ArrayList<>();
        int zeroWeightNodeOtherParentCount = 0;

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {

            final List<RosterEntry> addresses = new ArrayList<>(roster.rosterEntries());
            Collections.shuffle(addresses, random);

            boolean atLeastOneEventCreated = false;

            for (final RosterEntry address : addresses) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                final PlatformEvent newEvent = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (newEvent == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                final NodeId otherId;
                if (newEvent.hasOtherParents()) {
                    otherId = newEvent.getOtherParents().getFirst().creator();
                } else {
                    otherId = null;
                }

                if (otherId != null && otherId.equals(zeroWeightNode)) {
                    zeroWeightNodeOtherParentCount++;
                }

                if (nodeId.equals(zeroWeightNode)) {
                    if (random.nextDouble() < 0.1 || slowNodeEvents.size() > 10) {
                        // Once in a while, take all the slow events and distribute them.
                        for (final PlatformEvent slowEvent : slowNodeEvents) {
                            distributeEvent(nodes, slowEvent);
                        }
                        slowNodeEvents.clear();
                        assignNGenAndDistributeEvent(nodes, allEvents, newEvent);
                    } else {
                        // Most of the time, we don't immediately distribute the slow events.
                        registerEvent(nodes.get(nodeId), allEvents, newEvent);
                        // Register the event with the creator node's test tipsetTracker
                        nodes.get(nodeId)
                                .tipsetTracker()
                                .addSelfEvent(newEvent.getDescriptor(), newEvent.getAllParents());
                        slowNodeEvents.add(newEvent);
                    }
                } else {
                    // immediately distribute all events not created by the zero stake node
                    assignNGenAndDistributeEvent(nodes, allEvents, newEvent);
                }

                if (advancingClock) {
                    assertEquals(newEvent.getTimeCreated(), time.now());
                }
                validateNewEvent(allEvents, newEvent, transactionSupplier.get(), nodes.get(nodeId), true);
            }

            assertTrue(atLeastOneEventCreated);
        }

        // This is just a heuristic. When running this, I typically see numbers around 10.
        // Essentially, we need to make sure that we are choosing the zero weight node's events
        // as other parents. Precisely how often is less important to this test, as long as we are
        // doing it at least some of the time.
        assertTrue(zeroWeightNodeOtherParentCount > 1);
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    @DisplayName("Size One Network Test")
    void sizeOneNetworkTest(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 1;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        final RosterEntry address = roster.rosterEntries().get(0);

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            if (advancingClock) {
                time.tick(Duration.ofMillis(10));
            }

            transactionSupplier.set(generateRandomTransactions(random));

            final NodeId nodeId = NodeId.of(address.nodeId());
            final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

            final PlatformEvent newEvent = eventCreator.maybeCreateEvent();

            // In this test, it should be impossible for a node to be unable to create an event.
            assertNotNull(newEvent);

            assignNGenAndDistributeEvent(nodes, events, newEvent);

            if (advancingClock) {
                assertEquals(newEvent.getTimeCreated(), time.now());
            }
        }
    }

    /**
     * There was once a bug that could cause event creation to become frozen. This was because we weren't properly
     * including the advancement weight of the self parent when considering the theoretical advancement weight of a new
     * event.
     */
    @Test
    @DisplayName("Frozen Event Creation Bug")
    void frozenEventCreationBug() {
        final Random random = getRandomPrintSeed();

        final int networkSize = 4;

        final Roster roster = RandomRosterBuilder.create(random)
                .withMinimumWeight(1)
                .withMaximumWeight(1)
                .withSize(networkSize)
                .build();

        final FakeTime time = new FakeTime();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId()); // self
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());
        final NodeId nodeC = NodeId.of(roster.rosterEntries().get(2).nodeId());
        final NodeId nodeD = NodeId.of(roster.rosterEntries().get(3).nodeId());

        // All nodes except for node A (0) are fully mocked. This test is testing how node A behaves.
        final EventCreator eventCreator = buildEventCreator(random, time, roster, nodeA, Collections::emptyList);

        // Create some genesis events
        final PlatformEvent eventA1 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA1);

        final PlatformEvent eventB1 = createTestEvent(random, nodeB, NonDeterministicGeneration.FIRST_GENERATION);
        final PlatformEvent eventC1 = createTestEvent(random, nodeC, NonDeterministicGeneration.FIRST_GENERATION);
        final PlatformEvent eventD1 = createTestEvent(random, nodeD, NonDeterministicGeneration.FIRST_GENERATION);

        eventCreator.registerEvent(eventB1);
        eventCreator.registerEvent(eventC1);
        eventCreator.registerEvent(eventD1);

        // Create the next several events.
        // We should be able to create a total of 3 before we exhaust all possible parents.

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final PlatformEvent eventA2 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA2);

        // This will advance the snapshot, total advancement weight is 2 (2+1/4 > 2/3)
        final PlatformEvent eventA3 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA3);

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final PlatformEvent eventA4 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA4);

        // It should not be possible to create another event since we have exhausted all possible other parents.
        assertNull(eventCreator.maybeCreateEvent());

        // Create an event from one of the other nodes that was updated in the previous snapshot,
        // but has not been updated in the current snapshot.

        final NodeId otherParentId;
        if (eventA2.hasOtherParents()) {
            otherParentId = eventA2.getOtherParents().getFirst().creator();
        } else {
            otherParentId = null;
        }

        final PlatformEvent legalOtherParent = createTestEvent(random, otherParentId, 2);

        eventCreator.registerEvent(legalOtherParent);

        // We should be able to create an event on the new parent.
        assertNotNull(eventCreator.maybeCreateEvent());
    }

    /**
     * Event from nodes not in the address book should not be used as parents for creating new events.
     */
    @Test
    @DisplayName("Not Registering Events From NodeIds Not In AddressBook")
    void notRegisteringEventsFromNodesNotInAddressBook() {
        final Random random = getRandomPrintSeed();

        final int networkSize = 4;

        final Roster roster = RandomRosterBuilder.create(random)
                .withMinimumWeight(1)
                .withMaximumWeight(1)
                .withSize(networkSize)
                .build();

        final FakeTime time = new FakeTime();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId()); // self
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());
        final NodeId nodeC = NodeId.of(roster.rosterEntries().get(2).nodeId());
        final NodeId nodeD = NodeId.of(roster.rosterEntries().get(3).nodeId());
        // Node 4 (E) is not in the address book.
        final NodeId nodeE = NodeId.of(nodeD.id() + 1);

        // All nodes except for node 0 are fully mocked. This test is testing how node 0 behaves.
        final EventCreator eventCreator = buildEventCreator(random, time, roster, nodeA, Collections::emptyList);

        // Create some genesis events
        final PlatformEvent eventA1 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA1);

        final PlatformEvent eventB1 = createTestEvent(random, nodeB, NonDeterministicGeneration.FIRST_GENERATION);
        final PlatformEvent eventC1 = createTestEvent(random, nodeC, NonDeterministicGeneration.FIRST_GENERATION);
        final PlatformEvent eventD1 = createTestEvent(random, nodeD, NonDeterministicGeneration.FIRST_GENERATION);
        final PlatformEvent eventE1 = createTestEvent(random, nodeE, NonDeterministicGeneration.FIRST_GENERATION);

        eventCreator.registerEvent(eventB1);
        eventCreator.registerEvent(eventC1);
        eventCreator.registerEvent(eventD1);
        // Attempt to register event from a node not in the address book.
        eventCreator.registerEvent(eventE1);

        // Create the next several events.
        // We should be able to create a total of 3 before we exhaust all possible parents in the address book.

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final PlatformEvent eventA2 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA2);

        // This will advance the snapshot, total advancement weight is 2 (2+1/4 > 2/3)
        final PlatformEvent eventA3 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA3);

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final PlatformEvent eventA4 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA4);

        // It should not be possible to create another event since we have exhausted all possible other parents in
        // the address book.
        assertNull(eventCreator.maybeCreateEvent());
    }

    /**
     * There was once a bug where it was possible to create a self event that was stale at the moment of its creation
     * time. This test verifies that this is no longer possible.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("No Stale Events At Creation Time Test")
    void noStaleEventsAtCreationTimeTest(final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 4;

        final Roster roster = RandomRosterBuilder.create(random)
                .withMinimumWeight(1)
                .withMaximumWeight(1)
                .withSize(networkSize)
                .build();

        final FakeTime time = new FakeTime();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId()); // self

        final EventCreator eventCreator = buildEventCreator(random, time, roster, nodeA, Collections::emptyList);

        eventCreator.setEventWindow(new EventWindow(
                1,
                100,
                1 /* ignored in this context */,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD));

        // Since there are no other parents available, the next event created would have a generation of 0
        // (if event creation were permitted). Since the current minimum generation non ancient is 100,
        // that event would be stale at the moment of its creation.
        assertNull(eventCreator.maybeCreateEvent());
    }

    /**
     * Checks that birth round on events is being set if the setting for using birth round is set.
     * <p>
     */
    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    @DisplayName("Check setting of birthRound on new events.")
    void checkSettingEventBirthRound(final boolean advancingClock, final boolean useBirthRoundForAncient) {
        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(
                random,
                time,
                roster,
                transactionSupplier::get,
                useBirthRoundForAncient ? AncientMode.BIRTH_ROUND_THRESHOLD : AncientMode.GENERATION_THRESHOLD);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator();

                final long pendingConsensusRound = eventIndex + 2;
                if (eventIndex > 0) {

                    final long ancientThreshold;
                    if (useBirthRoundForAncient) {
                        ancientThreshold = Math.max(EventConstants.MINIMUM_ROUND_CREATED, eventIndex - 26);
                    } else {
                        ancientThreshold = Math.max(EventConstants.FIRST_GENERATION, eventIndex - 26);
                    }

                    // Set non-ancientEventWindow after creating genesis event from each node.
                    eventCreator.setEventWindow(new EventWindow(
                            pendingConsensusRound - 1,
                            ancientThreshold,
                            1 /* ignored in this context */,
                            useBirthRoundForAncient
                                    ? AncientMode.BIRTH_ROUND_THRESHOLD
                                    : AncientMode.GENERATION_THRESHOLD));
                }

                final PlatformEvent event = eventCreator.maybeCreateEvent();

                // In this test, it should be impossible for a node to be unable to create an event.
                assertNotNull(event);

                assignNGenAndDistributeEvent(nodes, events, event);

                if (advancingClock) {
                    assertEquals(event.getTimeCreated(), time.now());
                }

                if (eventIndex == 0) {
                    final long birthRound = event.getEventCore().birthRound();
                    assertEquals(ROUND_FIRST, birthRound);
                } else {
                    final long birthRound = event.getEventCore().birthRound();
                    if (useBirthRoundForAncient) {
                        assertEquals(pendingConsensusRound, birthRound);
                    } else {
                        assertEquals(ROUND_FIRST, birthRound);
                    }
                }
            }
        }
    }

    /**
     * During PCES replay, the node will learn of self events it created in the past. This test creates a single node
     * network, sends the event creator self events (with nGen values assigned), then creates a new event. The new event
     * should have the proper self parent.
     */
    @Test
    @DisplayName("Self event with highest nGen is used as latest self event on startup")
    void lastSelfEventUpdatedDuringPCESReplay() {
        final Random random = getRandomPrintSeed();
        final int networkSize = 1;
        final int numEvents = 100;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, selfId, () -> Collections.EMPTY_LIST);

        final List<PlatformEvent> pcesEvents = new ArrayList<>();
        PlatformEvent eventWithHighestNGen = null;
        for (int i = 0; i < numEvents; i++) {
            final PlatformEvent event = createTestEvent(random, selfId, i++);
            if (eventWithHighestNGen == null || event.getNGen() > eventWithHighestNGen.getNGen()) {
                eventWithHighestNGen = event;
            }
            pcesEvents.add(event);
        }

        // Add the events to the creator in a random order
        Collections.shuffle(pcesEvents, random);
        pcesEvents.forEach(eventCreator::registerEvent);

        // Verify that the new event created uses a self parent that is the event with the highest nGen.
        // This new event should not have an nGen assigned.
        final PlatformEvent newEvent = eventCreator.maybeCreateEvent();
        assertNotNull(newEvent);
        assertEquals(eventWithHighestNGen.getDescriptor(), newEvent.getSelfParent());
        assertEquals(NonDeterministicGeneration.GENERATION_UNDEFINED, newEvent.getNGen());
    }

    /**
     * This test verifies that an event recently created by the event creator is not overwritten when it learns of a
     * self event for the first time from the intake pipeline.
     */
    @Test
    @DisplayName("Last Self Event just created is not overwritten")
    void lastSelfEventNotOverwritten() {
        final Random random = getRandomPrintSeed();
        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, selfId, () -> Collections.EMPTY_LIST);

        final PlatformEvent newEvent = eventCreator.maybeCreateEvent();
        assertNotNull(newEvent);
        assertEquals(NonDeterministicGeneration.GENERATION_UNDEFINED, newEvent.getNGen());

        // Create a self event with an nGen value set and register it with the event creator. This can happen
        // if we are forced to reconnect and learn of an event we created a long time ago after we started creating
        // the events. This is a branch, but not necessarily an intentional branch. This old event should be discarded
        // because we want to favor any self event last created by the event creator even though it does not have an
        // nGen set.
        final PlatformEvent oldSelfEvent = createTestEvent(random, selfId, NonDeterministicGeneration.FIRST_GENERATION);
        eventCreator.registerEvent(oldSelfEvent);

        // Now create another event and check that the self parent is the expected event.
        final PlatformEvent newEvent2 = eventCreator.maybeCreateEvent();
        assertNotNull(newEvent2);
        assertEquals(newEvent.getDescriptor(), newEvent2.getSelfParent());
        assertEquals(NonDeterministicGeneration.GENERATION_UNDEFINED, newEvent2.getNGen());
    }
}
