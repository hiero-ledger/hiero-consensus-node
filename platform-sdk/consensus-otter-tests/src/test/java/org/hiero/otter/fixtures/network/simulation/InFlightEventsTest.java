// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
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

    /** The receiver's current event window, which tests replace to make events ancient. */
    private EventWindow eventWindow = EventWindow.getGenesisEventWindow();

    /** The structure under test. */
    private InFlightEvents inFlightEvents;

    /** Receives on behalf of {@link #NODE_0}, which is the node most of these tests deliver to. */
    private RecordingReceiver node0;

    @BeforeEach
    void setUp() {
        downConnections.clear();
        eventWindow = EventWindow.getGenesisEventWindow();

        final BiPredicate<NodeId, NodeId> connected =
                (sender, receiver) -> !downConnections.contains(Map.entry(sender, receiver));
        inFlightEvents = new InFlightEvents(connected, nodeId -> eventWindow, START);

        inFlightEvents.addNode(NODE_0);
        inFlightEvents.addNode(NODE_1);
        inFlightEvents.addNode(NODE_2);

        node0 = new RecordingReceiver(NODE_0);
    }

    @Test
    @DisplayName("nothing is delivered when no events are in flight")
    void deliveringWithNothingInFlight() {
        inFlightEvents.deliverArrived(START, node0);

        assertThat(node0.receivedEvents).isEmpty();
    }

    @Test
    @DisplayName("events on one connection are delivered in the order they were added")
    void eventsOnOneConnectionKeepTheirOrder() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 20);

        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receivedEvents).containsExactly(first, second);
    }

    @Test
    @DisplayName("events from different senders are merged into arrival order")
    void eventsFromDifferentSendersAreMerged() {
        // Added grouped by sender, so insertion order alone would not produce the expected result.
        final PlatformEvent fromOneEarly = addEvent(NODE_1, 10);
        final PlatformEvent fromOneLate = addEvent(NODE_1, 30);
        final PlatformEvent fromTwoEarly = addEvent(NODE_2, 20);
        final PlatformEvent fromTwoLate = addEvent(NODE_2, 40);

        inFlightEvents.deliverArrived(at(50), node0);

        assertThat(node0.receivedEvents).containsExactly(fromOneEarly, fromTwoEarly, fromOneLate, fromTwoLate);
    }

    @Test
    @DisplayName("events that have not arrived yet are held until they do")
    void eventsAreHeldUntilTheyArrive() {
        final PlatformEvent early = addEvent(NODE_1, 10);
        final PlatformEvent late = addEvent(NODE_1, 30);

        inFlightEvents.deliverArrived(at(20), node0);
        assertThat(node0.receivedEvents).containsExactly(early);

        inFlightEvents.deliverArrived(at(40), node0);
        assertThat(node0.receivedEvents).containsExactly(early, late);
    }

    @Test
    @DisplayName("an event arriving exactly now is delivered")
    void eventArrivingExactlyNowIsDelivered() {
        final PlatformEvent event = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrived(at(10), node0);

        assertThat(node0.receivedEvents).containsExactly(event);
    }

    @Test
    @DisplayName("a connection that is down holds up only its own events")
    void aDownConnectionHoldsUpOnlyItsOwnEvents() {
        // The held event arrived first, so a single queue per receiver would have blocked the other sender behind it.
        addEvent(NODE_1, 10);
        final PlatformEvent fromOpenConnection = addEvent(NODE_2, 20);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receivedEvents).containsExactly(fromOpenConnection);
    }

    @Test
    @DisplayName("events held by a down connection are delivered once it comes back up")
    void heldEventsAreDeliveredWhenTheConnectionComesBackUp() {
        final PlatformEvent held = addEvent(NODE_1, 10);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEvents.deliverArrived(at(20), node0);
        assertThat(node0.receivedEvents).isEmpty();

        downConnections.clear();
        inFlightEvents.deliverArrived(at(20), node0);
        assertThat(node0.receivedEvents).containsExactly(held);
    }

    @Test
    @DisplayName("a connection being down in the other direction does not hold up delivery")
    void connectionsAreDirected() {
        final PlatformEvent event = addEvent(NODE_1, 10);
        downConnections.add(Map.entry(NODE_0, NODE_1));

        inFlightEvents.deliverArrived(at(20), node0);

        assertThat(node0.receivedEvents).containsExactly(event);
    }

    @Test
    @DisplayName("delivery stops when the receiver stops accepting, and resumes later")
    void deliveryStopsWhenTheReceiverRejects() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 20);
        node0.accepting = false;

        inFlightEvents.deliverArrived(at(30), node0);
        assertThat(node0.receivedEvents).isEmpty();

        node0.accepting = true;
        inFlightEvents.deliverArrived(at(30), node0);
        assertThat(node0.receivedEvents).containsExactly(first, second);
    }

    @Test
    @DisplayName("events arriving at the same instant are delivered in node id order")
    void tiesAreBrokenByNodeId() {
        // Added highest sender first, so insertion order and node id order disagree.
        final PlatformEvent fromTwo = addEvent(NODE_2, 10);
        final PlatformEvent fromOne = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrived(at(20), node0);

        assertThat(node0.receivedEvents).containsExactly(fromOne, fromTwo);
    }

    @Test
    @DisplayName("a tie with a connection's next event is still broken by node id")
    void tiesWithALaterEventOnAConnectionAreBrokenByNodeId() {
        // Node 2's second event only becomes its head after its first is delivered, and then ties with node 1's.
        final PlatformEvent fromTwoEarly = addEvent(NODE_2, 10);
        final PlatformEvent fromTwoLate = addEvent(NODE_2, 20);
        final PlatformEvent fromOne = addEvent(NODE_1, 20);

        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receivedEvents).containsExactly(fromTwoEarly, fromOne, fromTwoLate);
    }

    @Test
    @DisplayName("clearing a node's incoming events leaves other nodes untouched")
    void clearingIncomingAffectsOneNodeOnly() {
        addEvent(NODE_0, NODE_1, 10);
        final PlatformEvent towardsNodeOne = addEvent(NODE_1, NODE_0, 10);

        inFlightEvents.clearIncoming(NODE_0);

        inFlightEvents.deliverArrived(at(20), node0);
        assertThat(node0.receivedEvents).isEmpty();

        final RecordingReceiver node1 = new RecordingReceiver(NODE_1);
        inFlightEvents.deliverArrived(at(20), node1);
        assertThat(node1.receivedEvents).containsExactly(towardsNodeOne);
    }

    @Test
    @DisplayName("a node added later can send to and receive from the nodes already present")
    void aNodeAddedLaterIsConnectedToTheExistingNodes() {
        final NodeId nodeThree = NodeId.of(3);
        inFlightEvents.addNode(nodeThree);

        final PlatformEvent towardsNodeZero = addEvent(NODE_0, nodeThree, 10);
        final PlatformEvent towardsNodeThree = addEvent(nodeThree, NODE_0, 10);

        inFlightEvents.deliverArrived(at(20), node0);
        assertThat(node0.receivedEvents).containsExactly(towardsNodeZero);

        final RecordingReceiver nodeThreeReceiver = new RecordingReceiver(nodeThree);
        inFlightEvents.deliverArrived(at(20), nodeThreeReceiver);
        assertThat(nodeThreeReceiver.receivedEvents).containsExactly(towardsNodeThree);
    }

    @Test
    @DisplayName("events on one connection are delivered in arrival order regardless of scheduling order")
    void eventsOnOneConnectionAreDeliveredInArrivalOrder() {
        final PlatformEvent late = addEvent(NODE_1, 30);
        final PlatformEvent early = addEvent(NODE_1, 10);
        final PlatformEvent middle = addEvent(NODE_1, 20);

        inFlightEvents.deliverArrived(at(15), node0);
        assertThat(node0.receivedEvents).containsExactly(early);

        inFlightEvents.deliverArrived(at(40), node0);
        assertThat(node0.receivedEvents).containsExactly(early, middle, late);
    }

    @Test
    @DisplayName("events with equal arrival times on one connection keep their scheduling order")
    void equalArrivalTimesKeepSchedulingOrder() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 10);
        final PlatformEvent third = addEvent(NODE_1, 10);
        final PlatformEvent earlier = addEvent(NODE_1, 5);

        inFlightEvents.deliverArrived(at(20), node0);

        assertThat(node0.receivedEvents).containsExactly(earlier, first, second, third);
    }

    @Test
    @DisplayName("two events on one connection may arrive at the same instant")
    void equalArrivalTimesOnOneConnectionAreAllowed() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 10);

        inFlightEvents.deliverArrived(at(20), node0);

        assertThat(node0.receivedEvents).containsExactly(first, second);
    }

    @Test
    @DisplayName("refusing one sender does not stop delivery from the others in the same tick")
    void refusingOneSenderDoesNotStopTheOthers() {
        addEvent(NODE_1, 10);
        final PlatformEvent fromTwoEarly = addEvent(NODE_2, 20);
        final PlatformEvent fromTwoLate = addEvent(NODE_2, 30);
        node0.refusedSenders.add(NODE_1);

        inFlightEvents.deliverArrived(at(40), node0);

        assertThat(node0.receivedEvents).containsExactly(fromTwoEarly, fromTwoLate);
    }

    @Test
    @DisplayName("the senders that are not refused keep their arrival order, interleaved across connections")
    void refusalKeepsTheArrivalOrderOfTheOthers() {
        final NodeId nodeThree = NodeId.of(3);
        inFlightEvents.addNode(nodeThree);
        final PlatformEvent fromOneEarly = addEvent(NODE_1, 10);
        final PlatformEvent fromTwoEarly = addEvent(NODE_2, 20);
        final PlatformEvent fromOneLate = addEvent(NODE_1, 25);
        final PlatformEvent fromThree = addEvent(nodeThree, 30);
        final PlatformEvent fromTwoLate = addEvent(NODE_2, 40);
        node0.refusedSenders.add(NODE_1);

        inFlightEvents.deliverArrived(at(50), node0);
        assertThat(node0.receivedEvents).containsExactly(fromTwoEarly, fromThree, fromTwoLate);
        assertThat(node0.receiveEventCalls).isEqualTo(4);

        node0.refusedSenders.clear();
        inFlightEvents.deliverArrived(at(50), node0);
        assertThat(node0.receivedEvents)
                .containsExactly(fromTwoEarly, fromThree, fromTwoLate, fromOneEarly, fromOneLate);
    }

    @Test
    @DisplayName("a refused sender is offered only once per delivery")
    void aRefusedSenderIsOfferedOnce() {
        addEvent(NODE_1, 10);
        addEvent(NODE_1, 20);
        node0.refusedSenders.add(NODE_1);

        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receiveEventCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("refused events stay queued, in order, and are delivered once the refusal ends")
    void refusedEventsAreDeliveredWhenTheRefusalEnds() {
        final PlatformEvent first = addEvent(NODE_1, 10);
        final PlatformEvent second = addEvent(NODE_1, 20);
        node0.refusedSenders.add(NODE_1);

        inFlightEvents.deliverArrived(at(30), node0);
        assertThat(node0.receivedEvents).isEmpty();

        node0.refusedSenders.clear();
        inFlightEvents.deliverArrived(at(30), node0);
        assertThat(node0.receivedEvents).containsExactly(first, second);
    }

    @Test
    @DisplayName("events keep being added to a refused connection and are delivered after the refusal")
    void eventsAddedDuringARefusalAreKept() {
        final PlatformEvent before = addEvent(NODE_1, 10);
        node0.refusedSenders.add(NODE_1);
        inFlightEvents.deliverArrived(at(15), node0);

        final PlatformEvent during = addEvent(NODE_1, 20);
        inFlightEvents.deliverArrived(at(25), node0);
        assertThat(node0.receivedEvents).isEmpty();

        node0.refusedSenders.clear();
        inFlightEvents.deliverArrived(at(25), node0);
        assertThat(node0.receivedEvents).containsExactly(before, during);
    }

    @Test
    @DisplayName("an event that became ancient while it waited on a down connection is dropped at delivery")
    void anAncientEventIsDroppedAtDelivery() {
        final PlatformEvent stale = addEvent(NODE_1, 10, 5);
        final PlatformEvent fresh = addEvent(NODE_1, 20, 15);
        final PlatformEvent freshFromOther = addEvent(NODE_2, 30, 12);
        downConnections.add(Map.entry(NODE_1, NODE_0));
        inFlightEvents.deliverArrived(at(40), node0);
        assertThat(node0.receivedEvents).containsExactly(freshFromOther);

        eventWindow = new EventWindow(10, 11, 10, 1);
        downConnections.clear();
        inFlightEvents.deliverArrived(at(40), node0);

        assertThat(node0.receivedEvents).containsExactly(freshFromOther, fresh).doesNotContain(stale);
    }

    @Test
    @DisplayName("an ancient event is not offered to the receiver, so it does not count as a refusal")
    void anAncientEventIsNotOffered() {
        addEvent(NODE_1, 10, 5);
        final PlatformEvent fresh = addEvent(NODE_1, 20, 15);
        eventWindow = new EventWindow(10, 11, 10, 1);

        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receivedEvents).containsExactly(fresh);
        assertThat(node0.receiveEventCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("an event that became ancient during a refusal is dropped once the refusal ends")
    void anEventThatBecameAncientDuringARefusalIsDropped() {
        addEvent(NODE_1, 10, 5);
        final PlatformEvent fresh = addEvent(NODE_1, 20, 15);
        node0.refusedSenders.add(NODE_1);
        inFlightEvents.deliverArrived(at(30), node0);

        eventWindow = new EventWindow(10, 11, 10, 1);
        node0.refusedSenders.clear();
        inFlightEvents.deliverArrived(at(30), node0);

        assertThat(node0.receivedEvents).containsExactly(fresh);
    }

    /**
     * Puts an event with the given birth round in flight towards {@link #NODE_0}.
     *
     * @param sender        the node sending the event
     * @param arrivalMillis how long after the start of the test the event arrives
     * @param birthRound    the birth round of the event
     * @return the event that was put in flight
     */
    private PlatformEvent addEvent(@NonNull final NodeId sender, final long arrivalMillis, final long birthRound) {
        return addEvent(NODE_0, sender, arrivalMillis, birthRound);
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
        return addEvent(receiver, sender, arrivalMillis, 1);
    }

    /**
     * Puts an event in flight.
     *
     * @param receiver      the node the event is travelling to
     * @param sender        the node sending the event
     * @param arrivalMillis how long after the start of the test the event arrives
     * @param birthRound    the birth round of the event
     * @return the event that was put in flight
     */
    private PlatformEvent addEvent(
            @NonNull final NodeId receiver,
            @NonNull final NodeId sender,
            final long arrivalMillis,
            final long birthRound) {
        final PlatformEvent event = mock(PlatformEvent.class);
        when(event.getSenderId()).thenReturn(sender);
        when(event.getBirthRound()).thenReturn(birthRound);
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
}
