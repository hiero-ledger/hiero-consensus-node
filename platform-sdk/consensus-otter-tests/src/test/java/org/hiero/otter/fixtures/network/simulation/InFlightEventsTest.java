// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InFlightEvents Test")
class InFlightEventsTest {

    private static final NodeId NODE_0 = NodeId.of(0);
    private static final NodeId NODE_1 = NodeId.of(1);
    private static final NodeId NODE_2 = NodeId.of(2);

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The connections that are currently down, as (sender, receiver) pairs. Tests mutate this to bring a connection up
     * or down; the predicate handed to the class under test reads it on every check.
     */
    private final Set<Map.Entry<NodeId, NodeId>> downConnections = new HashSet<>();

    /** The structure under test. */
    private InFlightEvents inFlightEvents;

    /** Receives on behalf of {@link #NODE_0}, which is the node most of these tests deliver to. */
    private RecordingReceiver node0;

    @BeforeEach
    void setUp() {
        downConnections.clear();

        final BiPredicate<NodeId, NodeId> connected =
                (sender, receiver) -> !downConnections.contains(Map.entry(sender, receiver));
        inFlightEvents = new InFlightEvents(connected);

        inFlightEvents.addNode(NODE_0);
        inFlightEvents.addNode(NODE_1);
        inFlightEvents.addNode(NODE_2);

        node0 = new RecordingReceiver(NODE_0);
    }

    @Test
    @DisplayName("nothing is delivered when no events are in flight")
    void deliveringWithNothingInFlight() {
        inFlightEvents.deliverArrivedEvents(START, node0);

        assertThat(node0.received).isEmpty();
    }

    @Test
    @DisplayName("events on one connection are delivered in the order they were added")
    void eventsOnOneConnectionKeepTheirOrder() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 20);

        inFlightEvents.deliverArrivedEvents(at(30), node0);

        assertThat(node0.received).containsExactly(first, second);
    }

    @Test
    @DisplayName("events from different senders are merged into arrival order")
    void eventsFromDifferentSendersAreMerged() {
        // Added grouped by sender, so insertion order alone would not produce the expected result.
        final PlatformEvent fromOneEarly = addEvent(NODE_1, 10);
        final PlatformEvent fromOneLate = addEvent(NODE_1, 30);
        final PlatformEvent fromTwoEarly = addEvent(NODE_2, 20);
        final PlatformEvent fromTwoLate = addEvent(NODE_2, 40);

        inFlightEvents.deliverArrivedEvents(at(50), node0);

        assertThat(node0.received).containsExactly(fromOneEarly, fromTwoEarly, fromOneLate, fromTwoLate);
    }

    @Test
    @DisplayName("events that have not arrived yet are held until they do")
    void eventsAreHeldUntilTheyArrive() {
        final PlatformEvent early = addEvent(NODE_1, 10);
        final PlatformEvent late = addEvent(NODE_1, 30);

        inFlightEvents.deliverArrivedEvents(at(20), node0);
        assertThat(node0.received).containsExactly(early);

        inFlightEvents.deliverArrivedEvents(at(40), node0);
        assertThat(node0.received).containsExactly(early, late);
    }

    @Test
    @DisplayName("an event arriving exactly now is delivered")
    void eventArrivingExactlyNowIsDelivered() {
        final PlatformEvent event = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrivedEvents(at(10), node0);

        assertThat(node0.received).containsExactly(event);
    }

    @Test
    @DisplayName("a connection that is down holds up only its own events")
    void aDownConnectionHoldsUpOnlyItsOwnEvents() {
        // The held event arrived first, so a single queue per receiver would have blocked the other sender behind it.
        addEvent(NODE_1, 10);
        final PlatformEvent fromOpenConnection = addEvent(NODE_2, 20);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEvents.deliverArrivedEvents(at(30), node0);

        assertThat(node0.received).containsExactly(fromOpenConnection);
    }

    @Test
    @DisplayName("events held by a down connection are delivered once it comes back up")
    void heldEventsAreDeliveredWhenTheConnectionComesBackUp() {
        final PlatformEvent held = addEvent(NODE_1, 10);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEvents.deliverArrivedEvents(at(20), node0);
        assertThat(node0.received).isEmpty();

        downConnections.clear();
        inFlightEvents.deliverArrivedEvents(at(20), node0);
        assertThat(node0.received).containsExactly(held);
    }

    @Test
    @DisplayName("a connection being down in the other direction does not hold up delivery")
    void connectionsAreDirected() {
        final PlatformEvent event = addEvent(NODE_1, 10);
        downConnections.add(Map.entry(NODE_0, NODE_1));

        inFlightEvents.deliverArrivedEvents(at(20), node0);

        assertThat(node0.received).containsExactly(event);
    }

    @Test
    @DisplayName("delivery stops when the receiver stops accepting, and resumes later")
    void deliveryStopsWhenTheReceiverRejects() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 20);
        node0.accepting = false;

        inFlightEvents.deliverArrivedEvents(at(30), node0);
        assertThat(node0.received).isEmpty();

        node0.accepting = true;
        inFlightEvents.deliverArrivedEvents(at(30), node0);
        assertThat(node0.received).containsExactly(first, second);
    }

    @Test
    @DisplayName("events arriving at the same instant are delivered in node id order")
    void tiesAreBrokenByNodeId() {
        // Added highest sender first, so insertion order and node id order disagree.
        final PlatformEvent fromTwo = addEvent(NODE_2, 10);
        final PlatformEvent fromOne = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrivedEvents(at(20), node0);

        assertThat(node0.received).containsExactly(fromOne, fromTwo);
    }

    @Test
    @DisplayName("clearing a node's incoming events leaves other nodes untouched")
    void clearingIncomingAffectsOneNodeOnly() {
        addEvent(NODE_0, NODE_1, 10);
        final PlatformEvent towardsNodeOne = addEvent(NODE_1, NODE_0, 10);

        inFlightEvents.clearIncoming(NODE_0);

        inFlightEvents.deliverArrivedEvents(at(20), node0);
        assertThat(node0.received).isEmpty();

        final RecordingReceiver node1 = new RecordingReceiver(NODE_1);
        inFlightEvents.deliverArrivedEvents(at(20), node1);
        assertThat(node1.received).containsExactly(towardsNodeOne);
    }

    @Test
    @DisplayName("a node added later can send to and receive from the nodes already present")
    void aNodeAddedLaterIsConnectedToTheExistingNodes() {
        final NodeId nodeThree = NodeId.of(3);
        inFlightEvents.addNode(nodeThree);

        final PlatformEvent towardsNodeZero = addEvent(NODE_0, nodeThree, 10);
        final PlatformEvent towardsNodeThree = addEvent(nodeThree, NODE_0, 10);

        inFlightEvents.deliverArrivedEvents(at(20), node0);
        assertThat(node0.received).containsExactly(towardsNodeZero);

        final RecordingReceiver nodeThreeReceiver = new RecordingReceiver(nodeThree);
        inFlightEvents.deliverArrivedEvents(at(20), nodeThreeReceiver);
        assertThat(nodeThreeReceiver.received).containsExactly(towardsNodeThree);
    }

    @Test
    @DisplayName("adding an event that arrives before the previous one is rejected")
    void addingOutOfArrivalOrderIsRejected() {
        addEvent(NODE_1, 20);

        assertThatThrownBy(() -> addEvent(NODE_1, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("two events on one connection may arrive at the same instant")
    void equalArrivalTimesOnOneConnectionAreAllowed() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrivedEvents(at(20), node0);

        assertThat(node0.received).containsExactly(first, second);
    }

    /**
     * Puts an event in flight towards {@link #NODE_0}.
     *
     * @param sender        the node sending the event
     * @param arrivalMillis how long after the start of the test the event arrives
     * @return the event that was put in flight
     */
    private PlatformEvent addEvent(@NonNull final NodeId sender, final long arrivalMillis) {
        return addEvent(NODE_0, sender, arrivalMillis);
    }

    /**
     * Puts an event in flight.
     *
     * @param receiver      the node the event is travelling to
     * @param sender        the node sending the event
     * @param arrivalMillis how long after the start of the test the event arrives
     * @return the event that was put in flight
     */
    private PlatformEvent addEvent(
            @NonNull final NodeId receiver, @NonNull final NodeId sender, final long arrivalMillis) {
        final PlatformEvent event = mock(PlatformEvent.class);
        inFlightEvents.add(receiver, new EventInTransit(event, sender, at(arrivalMillis)));
        return event;
    }

    /**
     * @param millis how long after the start of the test
     * @return the instant that many milliseconds after the start of the test
     */
    private static Instant at(final long millis) {
        return START.plusMillis(millis);
    }

    /**
     * An {@link EventReceiver} that records what it is given and can be told to stop accepting events.
     */
    private static final class RecordingReceiver implements EventReceiver {

        private final NodeId nodeId;
        private final List<PlatformEvent> received = new ArrayList<>();
        private boolean accepting = true;

        private RecordingReceiver(@NonNull final NodeId nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public NodeId getNodeId() {
            return nodeId;
        }

        @Override
        public boolean receiveEvent(@NonNull final PlatformEvent event) {
            if (!accepting) {
                return false;
            }
            received.add(event);
            return true;
        }
    }
}
