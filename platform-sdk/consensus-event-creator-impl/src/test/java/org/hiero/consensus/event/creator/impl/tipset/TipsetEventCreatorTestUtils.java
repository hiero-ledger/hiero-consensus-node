// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.CompareTo.isGreaterThanOrEqualTo;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.gossip.IntakeEventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.TransactionSupplier;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator.HashSigner;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.transaction.TestingTransactions;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.Assertions;

public class TipsetEventCreatorTestUtils {

    /**
     * Build an event creator for a node.
     */
    @NonNull
    public static EventCreator buildEventCreator(
            @NonNull final Random random,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId nodeId,
            @NonNull final TransactionSupplier transactionSupplier) {

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final HashSigner signer = mock(HashSigner.class);
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
    public static Map<NodeId, SimulatedNode> buildSimulatedNodes(
            @NonNull final Random random,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final TransactionSupplier transactionSupplier) {

        final Map<NodeId, SimulatedNode> eventCreators = new HashMap<>();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();

        for (final RosterEntry address : roster.rosterEntries()) {

            final NodeId selfId = NodeId.of(address.nodeId());
            final EventCreator eventCreator = buildEventCreator(random, time, roster, selfId, transactionSupplier);

            // Set a wide event window so that no events get stuck in the Future Event Buffer
            eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());

            final TipsetTracker tipsetTracker = new TipsetTracker(time, selfId, roster);

            final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
            final TipsetWeightCalculator tipsetWeightCalculator = new TipsetWeightCalculator(
                    platformContext, roster, NodeId.of(address.nodeId()), tipsetTracker, childlessEventTracker);
            final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(platformContext, mock(IntakeEventCounter.class));

            eventCreators.put(
                    selfId,
                    new SimulatedNode(
                            NodeId.of(address.nodeId()),
                            orphanBuffer,
                            tipsetTracker,
                            eventCreator,
                            tipsetWeightCalculator));
        }

        return eventCreators;
    }

    /**
     * Validates that @code newEvent satisfies:
     * - if it has a null self-parent, is only during genesis scenario
     * - if it has a null other-parent, is only during genesis scenario
     * - the event is contained in allEvents
     * - NGen should be max of parents plus one
     * - Parent's birthround or generation is never higher than event's.
     * - There is a minimum gap of 1-nanosecond between Event's timeCreated and selfparent's ( timeCreated + transactionCount)
     * - Except for genesis scenario, new event must have a positive advancement score.
     * - Application Transactions of the event matches @code expectedTransactions
     *
     * This method produces a side effect of advancing the last AdvancementWeight of the simulatedNode
     *
     * @param allEvents the list of all events, where parents are going to be retrieved from and the event is going to be check against
     * @param newEvent the event to validate
     * @param expectedTransactions all the application transactions expected to be present in the event
     * @param simulatedNode the node that produced the event. This object is changed after invoking this method.
     * @param slowNode an specific mode of validation
     */
    public static void validateNewEventAndMaybeAdvanceCreatorScore(
            @NonNull final Map<EventDescriptorWrapper, PlatformEvent> allEvents,
            @NonNull final PlatformEvent newEvent,
            @NonNull final List<Bytes> expectedTransactions,
            @NonNull final SimulatedNode simulatedNode,
            final boolean slowNode) {

        final PlatformEvent selfParent = allEvents.get(newEvent.getSelfParent());
        final long selfParentGeneration =
                selfParent == null ? NonDeterministicGeneration.GENERATION_UNDEFINED : selfParent.getNGen();
        final PlatformEvent otherParent =
                allEvents.get(newEvent.getOtherParents().stream().findFirst().orElse(null));
        final long otherParentGeneration =
                otherParent == null ? NonDeterministicGeneration.GENERATION_UNDEFINED : otherParent.getNGen();

        if (selfParent == null) {
            // The only legal time to have a null self parent is genesis.
            for (final PlatformEvent event : allEvents.values()) {
                if (Objects.equals(event.getHash(), newEvent.getHash())) {
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
                assertTrue(allEvents.size() == 1 || allEvents.size() == 2);
            } else {
                // The only legal time to have no other-parent is at genesis before other events are received.
                assertEquals(1, allEvents.size());
            }
            assertTrue(allEvents.containsKey(newEvent.getDescriptor()));
        }

        // Timestamp must always increase by 1 nanosecond, and there must always be a unique timestamp
        // with nanosecond precision for transaction.
        if (selfParent != null) {
            final int minimumIncrement = Math.max(1, selfParent.getTransactionCount());
            final Instant minimumTimestamp = selfParent.getTimeCreated().plus(Duration.ofNanos(minimumIncrement));
            assertTrue(isGreaterThanOrEqualTo(newEvent.getTimeCreated(), minimumTimestamp));
        }

        // Validate tipset constraints.
        final EventDescriptorWrapper descriptor = newEvent.getDescriptor();
        if (selfParent != null) {
            // Except for a genesis event, all other new events must have a positive advancement score.
            assertTrue(simulatedNode
                    .tipsetWeightCalculator()
                    .addEventAndGetAdvancementWeight(descriptor)
                    .isNonZero());
        } else {
            // the next call will modify the tipsetWeightCalculator
            simulatedNode.tipsetWeightCalculator().addEventAndGetAdvancementWeight(descriptor);
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

        assertDoesNotThrow(simulatedNode.eventCreator()::toString);
    }

    /**
     * Calculate and assign the nGen value to the event and distribute to all nodes in the network.
     */
    public static void assignNGenAndDistributeEvent(
            @NonNull final Map<NodeId, SimulatedNode> nodeMap,
            @NonNull final Map<EventDescriptorWrapper, PlatformEvent> events,
            @NonNull final PlatformEvent event) {

        nodeMap.values().forEach(node -> registerEvent(node, events, event));
        distributeEvent(nodeMap, event);
    }

    /**
     * Register the event in the map of all events, and pass the event through the node's orphan buffer to ensure it
     * gets assigned an nGen value.
     */
    @NonNull
    public static PlatformEvent registerEvent(
            @NonNull final SimulatedNode node,
            @NonNull final Map<EventDescriptorWrapper, PlatformEvent> allEvents,
            @NonNull final PlatformEvent event) {
        node.orphanBuffer().handleEvent(event);
        assertThat(event.hasNGen())
                .withFailMessage("Event should have passed through the orphan buffer and been assigned an nGen value")
                .isTrue();
        allEvents.put(event.getDescriptor(), event);
        return event;
    }

    /**
     * Distribute an event to all nodes in the network.
     */
    public static void distributeEvent(
            @NonNull final Map<NodeId, SimulatedNode> nodeMap, @NonNull final PlatformEvent event) {

        for (final SimulatedNode node : nodeMap.values()) {
            node.eventCreator().registerEvent(event);
            if (!event.getCreatorId().equals(node.nodeId())) {
                node.tipsetTracker().addPeerEvent(event);
            } else {
                node.tipsetTracker().addSelfEvent(event.getDescriptor(), event.getAllParents());
            }
        }
    }

    /**
     * Generates {@code number} of random transactions
     * @param random the random instance to use
     * @param number the number of transactions to generate. Must be positive.
     * @return a list of bytes for each transaction
     */
    @NonNull
    static List<Bytes> generateTransactions(@NonNull final Random random, final int number) {
        if (number <= 0) {
            throw new IllegalArgumentException("number must be greater than 0");
        }
        return IntStream.range(0, number)
                .mapToObj(i -> TestingTransactions.generateRandomTransaction(random))
                .toList();
    }

    /**
     * Generate a small number of random transactions.
     */
    @NonNull
    public static List<Bytes> generateRandomTransactions(@NonNull final Random random) {
        return generateTransactions(random, random.nextInt(1, 10));
    }

    @NonNull
    public static PlatformEvent createTestEventWithParent(
            @NonNull final Random random, @Nullable final NodeId creator, final long nGen, final long birthRound) {

        final PlatformEvent selfParent =
                new TestingEventBuilder(random).setCreatorId(creator).build();

        return new TestingEventBuilder(random)
                .setCreatorId(creator)
                .setNGen(nGen)
                .setBirthRound(birthRound)
                .setSelfParent(selfParent)
                .build();
    }

    /**
     * @return a boolean array containing both false and true values
     */
    @NonNull
    public static List<Boolean> booleanValues() {
        return Arrays.asList(Boolean.FALSE, Boolean.TRUE);
    }
}
