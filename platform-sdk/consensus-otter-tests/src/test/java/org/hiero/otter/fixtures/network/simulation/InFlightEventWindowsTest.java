// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.RecordingReceiver.ReceivedEventWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InFlightEventWindows Test")
class InFlightEventWindowsTest {

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
    private InFlightEventWindows inFlightEventWindows;

    /** Receives on behalf of {@link #NODE_0}, which is the node these tests deliver to. */
    private RecordingReceiver node0;

    @BeforeEach
    void setUp() {
        downConnections.clear();

        final BiPredicate<NodeId, NodeId> connected =
                (sender, receiver) -> !downConnections.contains(Map.entry(sender, receiver));
        inFlightEventWindows = new InFlightEventWindows(connected);

        inFlightEventWindows.addNode(NODE_0);
        inFlightEventWindows.addNode(NODE_1);
        inFlightEventWindows.addNode(NODE_2);

        node0 = new RecordingReceiver(NODE_0);
    }

    @Test
    @DisplayName("nothing is delivered when no event windows are in flight")
    void deliveringWithNothingInFlight() {
        inFlightEventWindows.deliverArrivedEventWindows(START, node0);

        assertThat(node0.receivedEventWindows).isEmpty();
    }

    @Test
    @DisplayName("a single event window is delivered once it arrives")
    void aSingleEventWindowIsDelivered() {
        final EventWindow window = addEventWindow(NODE_1, 10, 5);

        inFlightEventWindows.deliverArrivedEventWindows(at(5), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        inFlightEventWindows.deliverArrivedEventWindows(at(10), node0);
        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, window));
    }

    @Test
    @DisplayName("only the newest arrived window is delivered, and the ones it supersedes are discarded")
    void supersededWindowsAreDiscarded() {
        addEventWindow(NODE_1, 10, 5);
        addEventWindow(NODE_1, 20, 6);
        final EventWindow newest = addEventWindow(NODE_1, 30, 7);

        inFlightEventWindows.deliverArrivedEventWindows(at(40), node0);

        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, newest));
    }

    @Test
    @DisplayName("windows that have not arrived yet are not coalesced into the delivered one")
    void onlyArrivedWindowsAreCoalesced() {
        addEventWindow(NODE_1, 10, 5);
        final EventWindow arrived = addEventWindow(NODE_1, 20, 6);
        final EventWindow notYetArrived = addEventWindow(NODE_1, 40, 7);

        inFlightEventWindows.deliverArrivedEventWindows(at(30), node0);
        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, arrived));

        inFlightEventWindows.deliverArrivedEventWindows(at(50), node0);
        assertThat(node0.receivedEventWindows)
                .containsExactly(
                        new ReceivedEventWindow(NODE_1, arrived), new ReceivedEventWindow(NODE_1, notYetArrived));
    }

    @Test
    @DisplayName("each sender's newest window is delivered separately")
    void eachSenderIsDeliveredSeparately() {
        addEventWindow(NODE_1, 10, 5);
        final EventWindow newestFromOne = addEventWindow(NODE_1, 20, 6);
        final EventWindow newestFromTwo = addEventWindow(NODE_2, 15, 9);

        inFlightEventWindows.deliverArrivedEventWindows(at(30), node0);

        assertThat(node0.receivedEventWindows)
                .containsExactly(
                        new ReceivedEventWindow(NODE_1, newestFromOne), new ReceivedEventWindow(NODE_2, newestFromTwo));
    }

    @Test
    @DisplayName("a window already delivered is not delivered a second time")
    void deliveredWindowsAreNotRepeated() {
        final EventWindow window = addEventWindow(NODE_1, 10, 5);

        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);
        inFlightEventWindows.deliverArrivedEventWindows(at(30), node0);

        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, window));
    }

    @Test
    @DisplayName("a connection that is down holds up only its own windows")
    void aDownConnectionHoldsUpOnlyItsOwnWindows() {
        addEventWindow(NODE_1, 10, 5);
        final EventWindow fromOpenConnection = addEventWindow(NODE_2, 10, 9);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);

        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_2, fromOpenConnection));
    }

    @Test
    @DisplayName("a connection coming back up delivers the sender's newest window, not the stale one it was holding")
    void aRestoredConnectionDeliversTheNewestWindow() {
        addEventWindow(NODE_1, 10, 5);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        final EventWindow newest = addEventWindow(NODE_1, 30, 9);
        downConnections.clear();

        inFlightEventWindows.deliverArrivedEventWindows(at(40), node0);
        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, newest));
    }

    @Test
    @DisplayName("a window refused by the receiver is kept and delivered later")
    void refusedWindowsAreKept() {
        final EventWindow window = addEventWindow(NODE_1, 10, 5);
        node0.accepting = false;

        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        node0.accepting = true;
        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);
        assertThat(node0.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_1, window));
    }

    @Test
    @DisplayName("clearing a node's incoming windows leaves other nodes untouched")
    void clearingIncomingAffectsOneNodeOnly() {
        addEventWindow(NODE_0, NODE_1, 10, 5);
        final EventWindow towardsNodeOne = addEventWindow(NODE_1, NODE_0, 10, 5);

        inFlightEventWindows.clearIncoming(NODE_0);

        inFlightEventWindows.deliverArrivedEventWindows(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        final RecordingReceiver node1 = new RecordingReceiver(NODE_1);
        inFlightEventWindows.deliverArrivedEventWindows(at(20), node1);
        assertThat(node1.receivedEventWindows).containsExactly(new ReceivedEventWindow(NODE_0, towardsNodeOne));
    }

    @Test
    @DisplayName("adding a window that arrives before the previous one is rejected")
    void addingOutOfArrivalOrderIsRejected() {
        addEventWindow(NODE_1, 20, 5);

        assertThatThrownBy(() -> addEventWindow(NODE_1, 10, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Puts an event window in flight towards {@link #NODE_0}.
     *
     * @param sender        the node whose window it is
     * @param arrivalMillis how long after the start of the test the window arrives
     * @param round         the round the window is at, which distinguishes one window from another
     * @return the event window that was put in flight
     */
    private EventWindow addEventWindow(@NonNull final NodeId sender, final long arrivalMillis, final long round) {
        return addEventWindow(NODE_0, sender, arrivalMillis, round);
    }

    /**
     * Puts an event window in flight.
     *
     * @param receiver      the node the window is traveling to
     * @param sender        the node whose window it is
     * @param arrivalMillis how long after the start of the test the window arrives
     * @param round         the round the window is at, which distinguishes one window from another
     * @return the event window that was put in flight
     */
    private EventWindow addEventWindow(
            @NonNull final NodeId receiver, @NonNull final NodeId sender, final long arrivalMillis, final long round) {

        final EventWindow eventWindow = new EventWindow(round, round, round, round);
        inFlightEventWindows.add(receiver, new EventWindowInTransit(eventWindow, sender, at(arrivalMillis)));
        return eventWindow;
    }

    /**
     * @param millis how long after the start of the test
     * @return the instant that many milliseconds after the start of the test
     */
    private static Instant at(final long millis) {
        return START.plusMillis(millis);
    }
}
