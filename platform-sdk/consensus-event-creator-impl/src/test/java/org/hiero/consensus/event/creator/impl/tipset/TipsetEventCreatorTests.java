// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.assignNGenAndDistributeEvent;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildEventCreator;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildSimulatedNodes;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.createTestEventWithParent;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.distributeEvent;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.generateRandomTransactions;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.generateTransactions;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.registerEvent;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.validateNewEventAndMaybeAdvanceCreatorScore;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.junit.extensions.ParamName;
import org.hiero.junit.extensions.ParamSource;
import org.hiero.junit.extensions.ParameterCombinationExtension;
import org.hiero.junit.extensions.UseParameterSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@DisplayName("TipsetEventCreatorImpl Tests")
class TipsetEventCreatorTests {

    /**
     * This test simulates the creation and propagation of events in a small network of simulated nodes (networkSize = 10).
     * It iterates 100 times, and within each iteration, it cycles through all nodes in the roster in order.
     * For each node, it potentially advances a simulated clock (if advancingClock is true), generates random transactions, triggers the node to create a new event, distributes this event to other nodes, and then validates the newly created event.
     * The test asserts that every node is always able to create an event and, if the clock is advancing, that the event's creation time matches the simulated current time.
     * The ancientMode parameter is used to assign a birthround or a generation at the time the event is being created.
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues")
    })
    @DisplayName("Round Robin Test")
    public void roundRobinTest(@ParamName("advancingClock") final boolean advancingClock) {

        final Random random = getRandomPrintSeed();

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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

                validateNewEventAndMaybeAdvanceCreatorScore(
                        events, event, transactionSupplier.get(), nodes.get(nodeId), false);
            }
        }
    }

    /**
     * This test simulates the creation and propagation of events in a small network of simulated nodes (networkSize = 10).
     * It iterates 100 times, and within each iteration, it cycles through all nodes in randomized order.
     * For each node, it potentially advances a simulated clock (if advancingClock is true), generates random transactions, triggers the node to create a new event, distributes this event to other nodes, and then validates the newly created event.
     * The test asserts that every node is always able to create an event and, if the clock is advancing, that the event's creation time matches the simulated current time.
     * The ancientMode parameter is used to assign a birthround or a generation at the time the event is being created.
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Random Order Test")
    void randomOrderTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                validateNewEventAndMaybeAdvanceCreatorScore(
                        events, event, transactionSupplier.get(), nodes.get(nodeId), false);
            }

            assertTrue(atLeastOneEventCreated);
        }
    }

    /**
     * This test is very similar to the {@link #randomOrderTest(boolean, Random)}, except that we repeat the test
     * several times using the same event creator. This fails when we do not clear the event creator in between runs,
     * but should not fail if we have cleared the vent creator.
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Clear Test")
    void clearTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                    validateNewEventAndMaybeAdvanceCreatorScore(
                            events, event, transactionSupplier.get(), nodes.get(nodeId), false);
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
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Create Many Events In A Row Test")
    void createManyEventsInARowTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                    validateNewEventAndMaybeAdvanceCreatorScore(
                            events, event, transactionSupplier.get(), nodes.get(nodeId), false);

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
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Zero Weight Node Test")
    void zeroWeightNodeTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {
        final int networkSize = 10;

        Roster roster = RandomRosterBuilder.create(random).withSize(networkSize).build();

        final NodeId zeroWeightNode =
                NodeId.of(roster.rosterEntries().getFirst().nodeId());

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

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                validateNewEventAndMaybeAdvanceCreatorScore(
                        allEvents, newEvent, transactionSupplier.get(), nodes.get(nodeId), false);
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
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Zero Weight Slow Node Test")
    void zeroWeightSlowNodeTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {
        final int networkSize = 10;

        Roster roster = RandomRosterBuilder.create(random).withSize(networkSize).build();

        final NodeId zeroWeightNode =
                NodeId.of(roster.rosterEntries().getFirst().nodeId());

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

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                validateNewEventAndMaybeAdvanceCreatorScore(
                        allEvents, newEvent, transactionSupplier.get(), nodes.get(nodeId), true);
            }

            assertTrue(atLeastOneEventCreated);
        }

        // This is just a heuristic. When running this, I typically see numbers around 10.
        // Essentially, we need to make sure that we are choosing the zero weight node's events
        // as other parents. Precisely how often is less important to this test, as long as we are
        // doing it at least some of the time.
        assertTrue(zeroWeightNodeOtherParentCount > 1);
    }

    /**
     *  This test evaluates the corner case of 1 node network.
     *  it should be impossible for a node to be unable to create an event.
     *
     * @param advancingClock {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Size One Network Test")
    void sizeOneNetworkTest(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {

        final int networkSize = 1;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        final RosterEntry address = roster.rosterEntries().getFirst();

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
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Frozen Event Creation Bug")
    void frozenEventCreationBug(@ParamName("random") final Random random) {

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

        final PlatformEvent eventB1 =
                createTestEventWithParent(random, nodeB, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        final PlatformEvent eventC1 =
                createTestEventWithParent(random, nodeC, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        final PlatformEvent eventD1 =
                createTestEventWithParent(random, nodeD, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);

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

        final PlatformEvent legalOtherParent = createTestEventWithParent(random, otherParentId, 2, ROUND_FIRST);

        eventCreator.registerEvent(legalOtherParent);

        // We should be able to create an event on the new parent.
        assertNotNull(eventCreator.maybeCreateEvent());
    }

    /**
     * Event from nodes not in the address book should not be used as parents for creating new events.
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Not Registering Events From NodeIds Not In AddressBook")
    void notRegisteringEventsFromNodesNotInAddressBook(@ParamName("random") final Random random) {

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
        // Set the event window to the genesis value so that no events get stuck in the Future Event Buffer
        eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());

        // Create some genesis events
        final PlatformEvent eventA1 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA1);

        final PlatformEvent eventB1 =
                createTestEventWithParent(random, nodeB, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        final PlatformEvent eventC1 =
                createTestEventWithParent(random, nodeC, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        final PlatformEvent eventD1 =
                createTestEventWithParent(random, nodeD, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        final PlatformEvent eventE1 =
                createTestEventWithParent(random, nodeE, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);

        eventCreator.registerEvent(eventB1);
        eventCreator.registerEvent(eventC1);
        eventCreator.registerEvent(eventD1);
        // Attempt to add an event from a node not in the address book.
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
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("No Stale Events At Creation Time Test")
    void noStaleEventsAtCreationTimeTest(@ParamName("random") final Random random) {
        final int networkSize = 4;

        final Roster roster = RandomRosterBuilder.create(random)
                .withMinimumWeight(1)
                .withMaximumWeight(1)
                .withSize(networkSize)
                .build();

        final FakeTime time = new FakeTime();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().getFirst().nodeId()); // self

        final EventCreator eventCreator = buildEventCreator(random, time, roster, nodeA, Collections::emptyList);
        eventCreator.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        // Since there are no other parents available, the next event created would have a generation of 0
        // (if event creation were permitted). Since the current minimum generation non ancient is 100,
        // that event would be stale at the moment of its creation.
        assertNull(eventCreator.maybeCreateEvent());
    }

    /**
     * Checks that birth round on events is being set if the setting for using birth round is set.
     * <p>
     * @param advancingClock  {@link TipsetEventCreatorTestUtils#booleanValues()}
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "advancingClock",
                fullyQualifiedClass = "org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils",
                method = "booleanValues"),
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Check setting of birthRound on new events.")
    void checkSettingEventBirthRound(
            @ParamName("advancingClock") final boolean advancingClock, @ParamName("random") final Random random) {

        final int networkSize = 10;

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final FakeTime time = new FakeTime();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>();

        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, transactionSupplier::get);

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
                    // Set non-ancientEventWindow after creating genesis event from each node.
                    eventCreator.setEventWindow(EventWindowBuilder.builder()
                            .setLatestConsensusRound(pendingConsensusRound - 1)
                            .setNewEventBirthRound(pendingConsensusRound)
                            .setAncientThresholdOrGenesis(eventIndex - 26)
                            .build());
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
                    assertEquals(pendingConsensusRound, birthRound);
                }
            }
        }
    }

    /**
     * During PCES replay, the node will learn of self events it created in the past. This test creates a single node
     * network, sends the event creator self events (with nGen values assigned), then creates a new event. The new event
     * should have the proper self parent.
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Self event with highest nGen is used as latest self event on startup")
    void lastSelfEventUpdatedDuringPCESReplay(@ParamName("random") final Random random) {
        final int networkSize = 1;
        final int numEvents = 100;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, selfId, Collections::emptyList);

        // Set the event window to the genesis value so that no events get stuck in the Future Event Buffer
        eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());

        final List<PlatformEvent> pcesEvents = new ArrayList<>();
        PlatformEvent eventWithHighestNGen = null;
        for (int i = 0; i < numEvents; i++) {
            final PlatformEvent event = createTestEventWithParent(random, selfId, i + 1, ROUND_FIRST);
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
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Last Self Event just created is not overwritten")
    void lastSelfEventNotOverwritten(@ParamName("random") final Random random) {

        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, selfId, Collections::emptyList);

        // Set the event window to the genesis value so that no events get stuck in the Future Event Buffer
        eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());

        final PlatformEvent newEvent = eventCreator.maybeCreateEvent();
        assertNotNull(newEvent);
        assertEquals(NonDeterministicGeneration.GENERATION_UNDEFINED, newEvent.getNGen());

        // Create a self event with an nGen value set and register it with the event creator. This can happen
        // if we are forced to reconnect and learn of an event we created a long time ago after we started creating
        // the events. This is a branch, but not necessarily an intentional branch. This old event should be discarded
        // because we want to favor any self event last created by the event creator even though it does not have an
        // nGen set.
        final PlatformEvent oldSelfEvent =
                createTestEventWithParent(random, selfId, NonDeterministicGeneration.FIRST_GENERATION, ROUND_FIRST);
        eventCreator.registerEvent(oldSelfEvent);

        // Now create another event and check that the self parent is the expected event.
        final PlatformEvent newEvent2 = eventCreator.maybeCreateEvent();
        assertNotNull(newEvent2);
        assertEquals(newEvent.getDescriptor(), newEvent2.getSelfParent());
        assertEquals(NonDeterministicGeneration.GENERATION_UNDEFINED, newEvent2.getNGen());
    }

    /**
     * This test verifies that the event creator assigns the propper creationTime in the following scenario:
     *   - the parent has no transactions
     *  - current time (wall clock) is after the parent's creation time
     * We expect the new event creation time to be the current time.
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("calculateNewEventCreationTime Test()")
    void eventCreationTime_testCurrentTimeAfterParentCreationTimeParentHasNoTransactions(
            @ParamName("random") final Random random) {

        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>(List.of());
        final FakeTime time = new FakeTime();

        final EventCreator eventCreator =
                buildEventCreator(random, time, roster, NodeId.of(0), transactionSupplier::get);

        var lastEvent = eventCreator.maybeCreateEvent();
        // Move the time forward
        time.tick(100);
        lastEvent = eventCreator.maybeCreateEvent();
        assertNotNull(lastEvent);
        Instant present = time.now();
        assertEquals(present, lastEvent.getTimeCreated());
    }

    /**
     * This test verifies that the event creator assigns the propper creationTime for an event in the following scenario:
     *  - the parent has transactions
     *  - current time (wall clock) is after the parent's last transaction time
     * We expect the new event creation time to be the current time.
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("calculateNewEventCreationTime Test()")
    void eventCreationTimeTest_currentTimeIsAfterParentLatestTransaction(@ParamName("random") final Random random) {

        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>(List.of());
        final FakeTime time = new FakeTime();
        final EventCreator eventCreator =
                buildEventCreator(random, time, roster, NodeId.of(0), transactionSupplier::get);

        transactionSupplier.set(generateTransactions(random, 10));
        var lastEvent = eventCreator.maybeCreateEvent();
        time.tick(100); // Move the time forward
        lastEvent = eventCreator.maybeCreateEvent();
        assertNotNull(lastEvent);
        Instant present = time.now();
        assertEquals(present, lastEvent.getTimeCreated());
    }

    /**
     * This test verifies that the event creator assigns the propper creationTime for an event in the following scenario:
     * - the parent has transactions
     * - current time (wall clock) is before the parent's last transaction time
     * We expect the new event creation time to be set to the parent's creation time + number of transactions.
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("calculateNewEventCreationTime Test()")
    void eventCreationTimeTestCurrenTimeIsBeforeParentLatestTransaction(@ParamName("random") final Random random) {

        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();

        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>(List.of());
        final FakeTime time = new FakeTime();
        final EventCreator eventCreator =
                buildEventCreator(random, time, roster, NodeId.of(0), transactionSupplier::get);

        transactionSupplier.set(generateTransactions(random, 100));
        // the self-parent
        var parentEvent = eventCreator.maybeCreateEvent();
        assertNotNull(parentEvent);
        // Move the time forward but just not enough to reach the parent's last transaction time
        time.tick(59);
        var lastEvent = eventCreator.maybeCreateEvent();
        assertNotNull(lastEvent);
        assertEquals(parentEvent.getTimeCreated().plusNanos(100), lastEvent.getTimeCreated());
    }

    /**
     * This test verifies that the event creator assigns the propper creationTime for an event in the following scenarioi:
     *  - the parent has no transactions
     *  - current time (wall clock) is the same as the parent's creation time
     * We expect the new event creation time to be set to the parent's creation time + a fixed delta.
     *
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("calculateNewEventCreationTime Test()")
    void eventCreationTimeTest(@ParamName("random") final Random random) {

        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = 1;
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final AtomicReference<List<Bytes>> transactionSupplier = new AtomicReference<>(List.of());
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, NodeId.of(0), transactionSupplier::get);

        var parentEvent = eventCreator.maybeCreateEvent(); // the self-parent
        assertNotNull(parentEvent);
        var lastEvent = eventCreator.maybeCreateEvent();
        assertNotNull(lastEvent);
        assertEquals(parentEvent.getTimeCreated().plusNanos(1), lastEvent.getTimeCreated());
    }

    /**
     * This test verifies that the event creator assigns the coin value correctly for a new event.
     * @param random  {@link RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("coinValue Test()")
    void coinValueTest(@ParamName("random") final Random random) {

        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = random.nextInt(1, 100);
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        final EventCreator eventCreator =
                buildEventCreator(random, new FakeTime(), roster, NodeId.of(0), Collections::emptyList);

        var event = eventCreator.maybeCreateEvent(); // the self-parent
        assertNotNull(event, "An event should have been created");
        assertTrue(event.getEventCore().coin() >= 0, "Coin value should be non-negative");
        assertTrue(
                event.getEventCore().coin() <= networkSize,
                "The maximum coin value should be equal to the network size");
    }
}
