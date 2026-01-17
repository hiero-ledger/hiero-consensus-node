// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildEventCreator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
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
        eventCreator = buildEventCreator(random, time, roster, NodeId.of(0), () -> {
            final List<TimestampedTransaction> copy = List.copyOf(transactionPool);
            transactionPool.clear();
            return copy;
        });
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
     * Verifies event creation time uses the max time received of all parents.
     */
    @Test
    void maxParents() {
        // genesis event
        final var firstEvent = eventCreator.maybeCreateEvent();
        assertNotNull(firstEvent);

        // time created is based on parent's time received
        final Instant parentTimeReceived = firstEvent.getTimeCreated().plusSeconds(1);
        firstEvent.setTimeReceived(parentTimeReceived);
        final var secondEvent = eventCreator.maybeCreateEvent();
        assertNotNull(secondEvent);
        assertEquals(
                parentTimeReceived,
                secondEvent.getTimeCreated(),
                "An event's creation time should be equal to the max time received of its parents");
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
