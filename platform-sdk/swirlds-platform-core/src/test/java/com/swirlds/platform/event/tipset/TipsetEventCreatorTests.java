// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomSignature;
import static com.swirlds.common.utility.CompareTo.isGreaterThanOrEqualTo;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.components.transaction.TransactionSupplier;
import com.swirlds.platform.event.creation.EventCreator;
import com.swirlds.platform.event.creation.tipset.ChildlessEventTracker;
import com.swirlds.platform.event.creation.tipset.TipsetEventCreator;
import com.swirlds.platform.event.creation.tipset.TipsetTracker;
import com.swirlds.platform.event.creation.tipset.TipsetWeightCalculator;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TipsetEventCreatorImpl Tests")
class TipsetEventCreatorTests {

    /**
     * @param nodeId                 the node ID of the simulated node
     * @param tipsetTracker          tracks tipsets of events
     * @param eventCreator           the event creator for the simulated node
     * @param tipsetWeightCalculator used to sanity check event creation logic
     */
    private record SimulatedNode(
            @NonNull NodeId nodeId,
            @NonNull TipsetTracker tipsetTracker,
            @NonNull EventCreator eventCreator,
            @NonNull TipsetWeightCalculator tipsetWeightCalculator) {}

    /**
     * Build an event creator for a node.
     */
    @NonNull
    private EventCreator buildEventCreator(
            @NonNull final Random random,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId nodeId,
            @NonNull final TransactionSupplier transactionSupplier) {

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final Signer signer = mock(Signer.class);
        when(signer.sign(any())).thenAnswer(invocation -> randomSignature(random));

        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(1).build();

        return new TipsetEventCreator(
                platformContext, random, signer, roster, nodeId, softwareVersion, transactionSupplier);
    }

    /**
     * Build an event creator for each node in the address book.
     */
    @NonNull
    private Map<NodeId, SimulatedNode> buildSimulatedNodes(
            @NonNull final Random random,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final TransactionSupplier transactionSupplier,
            @NonNull final AncientMode ancientMode) {

        final Map<NodeId, SimulatedNode> eventCreators = new HashMap<>();
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        for (final RosterEntry address : roster.rosterEntries()) {

            final EventCreator eventCreator =
                    buildEventCreator(random, time, roster, NodeId.of(address.nodeId()), transactionSupplier);

            final TipsetTracker tipsetTracker = new TipsetTracker(time, roster, ancientMode);

            final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
            final TipsetWeightCalculator tipsetWeightCalculator = new TipsetWeightCalculator(
                    platformContext, roster, NodeId.of(address.nodeId()), tipsetTracker, childlessEventTracker);

            eventCreators.put(
                    NodeId.of(address.nodeId()),
                    new SimulatedNode(
                            NodeId.of(address.nodeId()), tipsetTracker, eventCreator, tipsetWeightCalculator));
        }

        return eventCreators;
    }

    private void validateNewEvent(
            @NonNull final Map<Hash, EventImpl> events,
            @NonNull final UnsignedEvent newEvent,
            @NonNull final List<Bytes> expectedTransactions,
            @NonNull final SimulatedNode simulatedNode,
            final boolean slowNode) {

        final EventImpl selfParent = events.get(newEvent.getMetadata().getSelfParentHash());
        final long selfParentGeneration =
                selfParent == null ? EventConstants.GENERATION_UNDEFINED : selfParent.getGeneration();
        final EventImpl otherParent = events.get(newEvent.getMetadata().getOtherParents().stream()
                .findFirst()
                .map(EventDescriptorWrapper::hash)
                .orElse(null));
        final long otherParentGeneration =
                otherParent == null ? EventConstants.GENERATION_UNDEFINED : otherParent.getGeneration();

        if (selfParent == null) {
            // The only legal time to have a null self parent is genesis.
            for (final EventImpl event : events.values()) {
                if (event.getBaseEvent().getHash().equals(newEvent.getHash())) {
                    // comparing to self
                    continue;
                }
                Assertions.assertNotEquals(
                        event.getCreatorId().id(), newEvent.getEventCore().creatorNodeId());
            }
        }

        if (otherParent == null) {
            if (slowNode) {
                // During the slow node test, we intentionally don't distribute an event that ends up in the
                // events map. So it's possible for this map to contain two events at this point in time.
                assertTrue(events.size() == 1 || events.size() == 2);
            } else {
                // The only legal time to have no other-parent is at genesis before other events are received.
                assertEquals(1, events.size());
            }
            assertTrue(events.containsKey(newEvent.getHash()));
        }

        // Generation should be max of parents plus one
        final long expectedGeneration = Math.max(selfParentGeneration, otherParentGeneration) + 1;
        assertEquals(expectedGeneration, newEvent.getMetadata().getGeneration());

        // Timestamp must always increase by 1 nanosecond, and there must always be a unique timestamp
        // with nanosecond precision for transaction.
        if (selfParent != null) {
            final int minimumIncrement = Math.max(1, selfParent.getBaseEvent().getTransactionCount());
            final Instant minimumTimestamp = selfParent.getTimeCreated().plus(Duration.ofNanos(minimumIncrement));
            assertTrue(isGreaterThanOrEqualTo(newEvent.getTimeCreated(), minimumTimestamp));
        }

        // Validate tipset constraints.
        final EventDescriptorWrapper descriptor = newEvent.getDescriptor();
        if (selfParent != null) {
            // Except for a genesis event, all other new events must have a positive advancement score.
            assertTrue(simulatedNode
                    .tipsetWeightCalculator
                    .addEventAndGetAdvancementWeight(descriptor)
                    .isNonZero());
        } else {
            simulatedNode.tipsetWeightCalculator.addEventAndGetAdvancementWeight(descriptor);
        }

        final List<Bytes> convertedTransactions = newEvent.getTransactions().stream()
                .map(TransactionWrapper::getApplicationTransaction)
                .toList();
        // We should see the expected transactions
        IntStream.range(0, expectedTransactions.size()).forEach(i -> {
            final Bytes expected = expectedTransactions.get(i);
            final Bytes actual = convertedTransactions.get(i);
            assertEquals(expected, actual, "Transaction " + i + " mismatch");
        });

        assertDoesNotThrow(simulatedNode.eventCreator::toString);
    }

    /**
     * Link the event into its parents and distribute to all nodes in the network.
     */
    private void linkAndDistributeEvent(
            @NonNull final Map<NodeId, SimulatedNode> eventCreators,
            @NonNull final Map<Hash, EventImpl> events,
            @NonNull final UnsignedEvent event) {

        distributeEvent(eventCreators, linkEvent(eventCreators, events, event));
    }

    /**
     * Link an event to its parents.
     */
    @NonNull
    private EventImpl linkEvent(
            @NonNull final Map<NodeId, SimulatedNode> eventCreators,
            @NonNull final Map<Hash, EventImpl> events,
            @NonNull final UnsignedEvent event) {

        eventCreators
                .get(NodeId.of(event.getEventCore().creatorNodeId()))
                .tipsetTracker
                .addEvent(event.getDescriptor(), event.getMetadata().getAllParents());

        final EventImpl selfParent = events.get(event.getMetadata().getSelfParentHash());
        final EventImpl otherParent = events.get(event.getMetadata().getOtherParents().stream()
                .findFirst()
                .map(EventDescriptorWrapper::hash)
                .orElse(null));

        final EventImpl eventImpl = new EventImpl(new PlatformEvent(event, new byte[0]), selfParent, otherParent);
        events.put(event.getHash(), eventImpl);

        return eventImpl;
    }

    /**
     * Distribute an event to all nodes in the network.
     */
    private void distributeEvent(
            @NonNull final Map<NodeId, SimulatedNode> eventCreators, @NonNull final EventImpl eventImpl) {

        for (final SimulatedNode eventCreator : eventCreators.values()) {
            eventCreator.eventCreator.registerEvent(eventImpl.getBaseEvent());
            eventCreator.tipsetTracker.addEvent(
                    eventImpl.getBaseEvent().getDescriptor(),
                    eventImpl.getBaseEvent().getAllParents());
        }
    }

    /**
     * Generate a small number of random transactions.
     */
    @NonNull
    private List<Bytes> generateRandomTransactions(@NonNull final Random random) {
        final int transactionCount = random.nextInt(0, 10);
        final List<Bytes> transactions = new ArrayList<>();

        for (int i = 0; i < transactionCount; i++) {
            final byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            transactions.add(Bytes.wrap(bytes));
        }

        return transactions;
    }

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

        final Map<Hash, EventImpl> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                final UnsignedEvent event = eventCreator.maybeCreateEvent();

                // In this test, it should be impossible for a node to be unable to create an event.
                assertNotNull(event);

                linkAndDistributeEvent(nodes, events, event);

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

        final Map<Hash, EventImpl> events = new HashMap<>();

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
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                final UnsignedEvent event = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (event == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                linkAndDistributeEvent(nodes, events, event);

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
            final Map<Hash, EventImpl> events = new HashMap<>();

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
                    final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                    final UnsignedEvent event = eventCreator.maybeCreateEvent();

                    // It's possible a node may not be able to create an event. But we are guaranteed
                    // to be able to create at least one event per cycle.
                    if (event == null) {
                        continue;
                    }
                    atLeastOneEventCreated = true;

                    linkAndDistributeEvent(nodes, events, event);

                    if (advancingClock) {
                        assertEquals(event.getTimeCreated(), time.now());
                    }
                    validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);
                }

                assertTrue(atLeastOneEventCreated);
            }
            // Reset the test by calling clear. This test fails in the second iteration if we don't clear things out.
            for (final SimulatedNode node : nodes.values()) {
                node.eventCreator.clear();

                // There are copies of these data structures inside the event creator. We maintain these ones
                // to sanity check the behavior of the event creator.
                node.tipsetTracker.clear();
                node.tipsetWeightCalculator.clear();
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

        final Map<Hash, EventImpl> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {

                int count = 0;
                while (true) {
                    if (advancingClock) {
                        time.tick(Duration.ofMillis(10));
                    }

                    transactionSupplier.set(generateRandomTransactions(random));

                    final NodeId nodeId = NodeId.of(address.nodeId());
                    final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                    final UnsignedEvent event = eventCreator.maybeCreateEvent();

                    if (count == 0) {
                        // The first time we attempt to create an event we should be able to do so.
                        assertNotNull(event);
                    } else if (event == null) {
                        // we can't create any more events
                        break;
                    }

                    linkAndDistributeEvent(nodes, events, event);

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

        final Map<Hash, EventImpl> events = new HashMap<>();

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
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                final UnsignedEvent event = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (event == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                final NodeId otherId;
                if (event.getMetadata().hasOtherParent()) {
                    otherId = event.getMetadata().getOtherParents().getFirst().creator();
                } else {
                    otherId = null;
                }

                if (otherId != null && otherId.equals(zeroWeightNode)) {
                    zeroWeightNodeOtherParentCount++;
                }

                linkAndDistributeEvent(nodes, events, event);

                if (advancingClock) {
                    assertEquals(event.getTimeCreated(), time.now());
                }
                validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), false);
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

        final Map<Hash, EventImpl> events = new HashMap<>();
        final List<EventImpl> slowNodeEvents = new ArrayList<>();
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
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

                final UnsignedEvent event = eventCreator.maybeCreateEvent();

                // It's possible a node may not be able to create an event. But we are guaranteed
                // to be able to create at least one event per cycle.
                if (event == null) {
                    continue;
                }
                atLeastOneEventCreated = true;

                final NodeId otherId;
                if (event.getMetadata().hasOtherParent()) {
                    otherId = event.getMetadata().getOtherParents().getFirst().creator();
                } else {
                    otherId = null;
                }

                if (otherId != null && otherId.equals(zeroWeightNode)) {
                    zeroWeightNodeOtherParentCount++;
                }

                if (nodeId.equals(zeroWeightNode)) {
                    if (random.nextDouble() < 0.1 || slowNodeEvents.size() > 10) {
                        // Once in a while, take all the slow events and distribute them.
                        for (final EventImpl slowEvent : slowNodeEvents) {
                            distributeEvent(nodes, slowEvent);
                        }
                        slowNodeEvents.clear();
                        linkAndDistributeEvent(nodes, events, event);
                    } else {
                        // Most of the time, we don't immediately distribute the slow events.
                        final EventImpl eventImpl = linkEvent(nodes, events, event);
                        slowNodeEvents.add(eventImpl);
                    }
                } else {
                    // immediately distribute all events not created by the zero stake node
                    linkAndDistributeEvent(nodes, events, event);
                }

                if (advancingClock) {
                    assertEquals(event.getTimeCreated(), time.now());
                }
                validateNewEvent(events, event, transactionSupplier.get(), nodes.get(nodeId), true);
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

        final Map<Hash, EventImpl> events = new HashMap<>();

        final RosterEntry address = roster.rosterEntries().get(0);

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            if (advancingClock) {
                time.tick(Duration.ofMillis(10));
            }

            transactionSupplier.set(generateRandomTransactions(random));

            final NodeId nodeId = NodeId.of(address.nodeId());
            final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

            final UnsignedEvent event = eventCreator.maybeCreateEvent();

            // In this test, it should be impossible for a node to be unable to create an event.
            assertNotNull(event);

            linkAndDistributeEvent(nodes, events, event);

            if (advancingClock) {
                assertEquals(event.getTimeCreated(), time.now());
            }
        }
    }

    @NonNull
    private PlatformEvent createTestEvent(
            @NonNull final Random random,
            @NonNull final NodeId creator,
            long selfParentGeneration,
            @Nullable final NodeId otherParentId,
            final long otherParentGeneration) {

        final PlatformEvent selfParent =
                new TestingEventBuilder(random).setCreatorId(creator).build();

        final TestingEventBuilder eventBuilder = new TestingEventBuilder(random)
                .setCreatorId(creator)
                .setSelfParent(selfParent)
                .overrideSelfParentGeneration(selfParentGeneration);

        if (otherParentId != null) {
            final PlatformEvent otherParent =
                    new TestingEventBuilder(random).setCreatorId(otherParentId).build();

            eventBuilder.setOtherParent(otherParent).overrideOtherParentGeneration(otherParentGeneration);
        }

        return eventBuilder.build();
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

        // All nodes except for node 0 are fully mocked. This test is testing how node 0 behaves.
        final EventCreator eventCreator = buildEventCreator(random, time, roster, nodeA, Collections::emptyList);

        // Create some genesis events
        final UnsignedEvent eventA1 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA1);

        final PlatformEvent eventB1 = createTestEvent(
                random, nodeB, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);
        final PlatformEvent eventC1 = createTestEvent(
                random, nodeC, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);
        final PlatformEvent eventD1 = createTestEvent(
                random, nodeD, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);

        eventCreator.registerEvent(eventB1);
        eventCreator.registerEvent(eventC1);
        eventCreator.registerEvent(eventD1);

        // Create the next several events.
        // We should be able to create a total of 3 before we exhaust all possible parents.

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final UnsignedEvent eventA2 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA2);

        // This will advance the snapshot, total advancement weight is 2 (2+1/4 > 2/3)
        final UnsignedEvent eventA3 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA3);

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final UnsignedEvent eventA4 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA4);

        // It should not be possible to create another event since we have exhausted all possible other parents.
        assertNull(eventCreator.maybeCreateEvent());

        // Create an event from one of the other nodes that was updated in the previous snapshot,
        // but has not been updated in the current snapshot.

        final NodeId otherParentId;
        if (eventA2.getMetadata().hasOtherParent()) {
            otherParentId = eventA2.getMetadata().getOtherParents().getFirst().creator();
        } else {
            otherParentId = null;
        }

        final PlatformEvent legalOtherParent = createTestEvent(random, otherParentId, 0, nodeA, 0);

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
        final UnsignedEvent eventA1 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA1);

        final PlatformEvent eventB1 = createTestEvent(
                random, nodeB, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);
        final PlatformEvent eventC1 = createTestEvent(
                random, nodeC, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);
        final PlatformEvent eventD1 = createTestEvent(
                random, nodeD, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);
        final PlatformEvent eventE1 = createTestEvent(
                random, nodeE, EventConstants.GENERATION_UNDEFINED, null, EventConstants.GENERATION_UNDEFINED);

        eventCreator.registerEvent(eventB1);
        eventCreator.registerEvent(eventC1);
        eventCreator.registerEvent(eventD1);
        // Attempt to register event from a node not in the address book.
        eventCreator.registerEvent(eventE1);

        // Create the next several events.
        // We should be able to create a total of 3 before we exhaust all possible parents in the address book.

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final UnsignedEvent eventA2 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA2);

        // This will advance the snapshot, total advancement weight is 2 (2+1/4 > 2/3)
        final UnsignedEvent eventA3 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA3);

        // This will not advance the snapshot, total advancement weight is 1 (1+1/4 !> 2/3)
        final UnsignedEvent eventA4 = eventCreator.maybeCreateEvent();
        assertNotNull(eventA4);

        // It should not be possible to create another event since we have exhausted all possible other parents in the
        // address book.
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
        final Random random = getRandomPrintSeed(0);

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

        final Map<Hash, EventImpl> events = new HashMap<>();

        for (int eventIndex = 0; eventIndex < 100; eventIndex++) {
            for (final RosterEntry address : roster.rosterEntries()) {
                if (advancingClock) {
                    time.tick(Duration.ofMillis(10));
                }

                transactionSupplier.set(generateRandomTransactions(random));

                final NodeId nodeId = NodeId.of(address.nodeId());
                final EventCreator eventCreator = nodes.get(nodeId).eventCreator;

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

                final UnsignedEvent event = eventCreator.maybeCreateEvent();

                // In this test, it should be impossible for a node to be unable to create an event.
                assertNotNull(event);

                linkAndDistributeEvent(nodes, events, event);

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
}
