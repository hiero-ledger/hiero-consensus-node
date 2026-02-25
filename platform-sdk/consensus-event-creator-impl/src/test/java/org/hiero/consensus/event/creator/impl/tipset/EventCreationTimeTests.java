// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.assignNGenAndDistributeEvent;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildEventCreator;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildSimulatedNodes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for event creation time logic.
 */
public class EventCreationTimeTests {
    private EventCreator eventCreator;
    private FakeTime time;
    private List<TimestampedTransaction> transactionPool;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        // Common test set up. We initialize a network to make it easier to create events.
        final int networkSize = 1;
        final Random random = Randotron.create();
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(networkSize).build();
        transactionPool = new ArrayList<>();
        eventCreator = buildEventCreator(
                random,
                time,
                roster,
                NodeId.of(0),
                () -> {
                    final List<TimestampedTransaction> copy = List.copyOf(transactionPool);
                    transactionPool.clear();
                    return copy;
                },
                1);
    }

    /**
     * Add a transaction with the given timestamp to the transaction pool.
     * @param timestamp the timestamp of the transaction to add
     */
    private void addTransaction(@NonNull final Instant timestamp) {
        transactionPool.add(new TimestampedTransaction(Bytes.EMPTY, timestamp));
    }

    /**
     * Verifies that the creation time of a genesis event with no inputs is the wall-clock time.
     */
    @Test
    void genesisWallClock() {
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);
        assertEquals(
                time.now(),
                firstEvent.getTimeCreated(),
                "The genesis event should use the wall-clock time if it has no other inputs");
    }

    /**
     * Verifies that the creation time of a genesis event with transactions is the max transaction time.
     */
    @Test
    void genesisWithTransactions() {
        addTransaction(time.now().minusSeconds(1));
        addTransaction(time.now().plusSeconds(1));
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);
        assertEquals(
                time.now().plusSeconds(1),
                firstEvent.getTimeCreated(),
                "A genesis event with transactions should use the max transaction time");
    }

    /**
     * Verifies that the creation time of a genesis event with an event window is the event window time.
     */
    @Test
    void genesisWithEventWindow() {
        eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());
        final Instant genesisWindowTime = time.now();
        time.tick(Duration.ofSeconds(1));
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);
        assertEquals(
                genesisWindowTime,
                firstEvent.getTimeCreated(),
                "The genesis event should use the event window time if it has no other inputs");
    }

    /**
     * Verifies that the child's creation time advances from the self-parent's timeCreated.
     */
    @Test
    void selfParentTimeCreatedDrivesChildCreationTime() {
        // genesis event
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);

        // no other inputs that would advance the time beyond the self-parent's timeCreated,
        // so the child should be exactly 1 nanosecond after.
        final var secondEvent = eventCreator.maybeCreateEvent();
        assertNotNull(secondEvent);
        assertEquals(
                firstEvent.getTimeCreated().plusNanos(1),
                secondEvent.getTimeCreated(),
                "The child's creation time should advance from the self-parent's timeCreated");
    }

    /**
     * Verifies that changing timeReceived on the self-parent does not affect the child's creation time.
     * For the self-parent, timeReceived is a wall-clock value used only for metrics, not for the
     * logical timestamp calculation.
     */
    @Test
    void selfParentTimeReceivedDoesNotAffectChild() {
        // genesis event
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);

        // set timeReceived to something far in the future — should have no effect
        firstEvent.setTimeReceived(firstEvent.getTimeCreated().plusSeconds(10));
        final var secondEvent = eventCreator.maybeCreateEvent();
        assertNotNull(secondEvent);
        assertEquals(
                firstEvent.getTimeCreated().plusNanos(1),
                secondEvent.getTimeCreated(),
                "The self-parent's timeReceived should not influence the child's creation time");
    }

    /**
     * Verifies that the child's creation time uses timeReceived from the other parent (not timeCreated).
     * This tests the asymmetry: self-parent contributes timeCreated, other parents contribute timeReceived.
     */
    @Test
    void otherParentTimeReceivedDrivesChildCreationTime() {
        final Random random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random).withSize(2).build();
        final Map<NodeId, SimulatedNode> nodes = buildSimulatedNodes(random, time, roster, List::of);
        final Map<EventDescriptorWrapper, PlatformEvent> events = new HashMap<>();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().getFirst().nodeId());
        final NodeId nodeB = NodeId.of(roster.rosterEntries().getLast().nodeId());

        // Both nodes create their genesis events
        final PlatformEvent genesis0 = nodes.get(nodeA).eventCreator().maybeCreateEvent();
        assertNotNull(genesis0);
        assignNGenAndDistributeEvent(nodes, events, genesis0);

        time.tick(Duration.ofSeconds(1));

        final PlatformEvent genesis1 = nodes.get(nodeB).eventCreator().maybeCreateEvent();
        assertNotNull(genesis1);
        assignNGenAndDistributeEvent(nodes, events, genesis1);

        // Override the other parent's timeReceived to be far in the future,
        // while its timeCreated remains in the past.
        final Instant otherTimeReceived = genesis0.getTimeCreated().plusSeconds(100);
        genesis1.setTimeReceived(otherTimeReceived);

        // Node 0 creates a second event — should pick genesis1 as other parent
        // and use its timeReceived (not timeCreated) for the creation time calculation.
        final PlatformEvent childEvent = nodes.get(nodeA).eventCreator().maybeCreateEvent();
        assertNotNull(childEvent);
        assertEquals(
                otherTimeReceived,
                childEvent.getTimeCreated(),
                "The child's creation time should be based on the other parent's timeReceived, not timeCreated");
    }

    @Test
    void maxTransaction() {
        // genesis event
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);

        addTransaction(firstEvent.getTimeReceived().minusSeconds(1));
        addTransaction(firstEvent.getTimeReceived().plusSeconds(1));

        final var secondEvent = eventCreator.maybeCreateEvent();
        assertNotNull(secondEvent);
        assertEquals(
                firstEvent.getTimeReceived().plusSeconds(1),
                secondEvent.getTimeCreated(),
                "An event's creation time should equal the highest input, which in this case is a transaction");
    }

    /**
     * Verifies that the creation time is always later than the self-parent's creation time.
     */
    @Test
    void alwaysLaterThanSelfParent() {
        // the event window will be an old input
        eventCreator.setEventWindow(EventWindow.getGenesisEventWindow());
        time.tick(Duration.ofSeconds(1));

        // genesis event
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);

        // add inputs with an earlier time than the self-parent
        addTransaction(firstEvent.getTimeCreated().minusSeconds(2));

        final var secondEvent = eventCreator.maybeCreateEvent();
        assertNotNull(secondEvent);
        assertEquals(
                firstEvent.getTimeCreated().plusNanos(1),
                secondEvent.getTimeCreated(),
                "If the maximum time received of all parents is not higher than the time created of the self "
                        + "parent, the event creator should add a nanosecond to make it higher");
    }
}
