// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.network.simulation.RecordingReceiver.PeerEventWindow;
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

        inFlightEventWindows = new InFlightEventWindows(
                (sender, receiver) -> !downConnections.contains(Map.entry(NodeId.of(sender), NodeId.of(receiver))),
                START);

        inFlightEventWindows.setUp(3);

        node0 = new RecordingReceiver(NODE_0);
    }

    @Test
    @DisplayName("nothing is delivered when no event windows are in flight")
    void deliveringWithNothingInFlight() {
        deliver(START, node0);

        assertThat(node0.receivedEventWindows).isEmpty();
    }

    @Test
    @DisplayName("a single event window is delivered once it arrives")
    void aSingleEventWindowIsDelivered() {
        final PeerEventWindow window = addEventWindow(NODE_1, 10, 105);

        deliver(at(5), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        deliver(at(10), node0);
        assertThat(node0.receivedEventWindows).containsExactly(window);
    }

    @Test
    @DisplayName("only the newest arrived window is delivered, and the ones it supersedes are discarded")
    void supersededWindowsAreDiscarded() {
        addEventWindow(NODE_1, 10, 105);
        addEventWindow(NODE_1, 20, 106);
        final PeerEventWindow newest = addEventWindow(NODE_1, 30, 107);

        deliver(at(40), node0);

        assertThat(node0.receivedEventWindows).containsExactly(newest);
    }

    @Test
    @DisplayName("windows that have not arrived yet are not coalesced into the delivered one")
    void onlyArrivedWindowsAreCoalesced() {
        addEventWindow(NODE_1, 10, 105);
        final PeerEventWindow arrived = addEventWindow(NODE_1, 20, 106);
        final PeerEventWindow notYetArrived = addEventWindow(NODE_1, 40, 107);

        deliver(at(30), node0);
        assertThat(node0.receivedEventWindows).containsExactly(arrived);

        deliver(at(50), node0);
        assertThat(node0.receivedEventWindows).containsExactly(arrived, notYetArrived);
    }

    @Test
    @DisplayName("each sender's newest window is delivered separately")
    void eachSenderIsDeliveredSeparately() {
        addEventWindow(NODE_1, 10, 105);
        final PeerEventWindow newestFromOne = addEventWindow(NODE_1, 20, 106);
        final PeerEventWindow newestFromTwo = addEventWindow(NODE_2, 15, 109);

        deliver(at(30), node0);

        assertThat(node0.receivedEventWindows).containsExactly(newestFromOne, newestFromTwo);
    }

    @Test
    @DisplayName("a window already delivered is not delivered a second time")
    void deliveredWindowsAreNotRepeated() {
        final PeerEventWindow window = addEventWindow(NODE_1, 10, 105);

        deliver(at(20), node0);
        deliver(at(30), node0);

        assertThat(node0.receivedEventWindows).containsExactly(window);
    }

    @Test
    @DisplayName("a connection that is down holds up only its own windows")
    void aDownConnectionHoldsUpOnlyItsOwnWindows() {
        addEventWindow(NODE_1, 10, 105);
        final PeerEventWindow fromOpenConnection = addEventWindow(NODE_2, 10, 109);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        deliver(at(20), node0);

        assertThat(node0.receivedEventWindows).containsExactly(fromOpenConnection);
    }

    @Test
    @DisplayName("a connection coming back up delivers the sender's newest window, not the stale one it was holding")
    void aRestoredConnectionDeliversTheNewestWindow() {
        addEventWindow(NODE_1, 10, 105);
        downConnections.add(Map.entry(NODE_1, NODE_0));

        deliver(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        final PeerEventWindow newest = addEventWindow(NODE_1, 30, 109);
        downConnections.clear();

        deliver(at(40), node0);
        assertThat(node0.receivedEventWindows).containsExactly(newest);
    }

    @Test
    @DisplayName("a window refused by the receiver is kept and delivered later")
    void refusedWindowsAreKept() {
        final PeerEventWindow window = addEventWindow(NODE_1, 10, 105);
        node0.accepting = false;

        deliver(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        node0.accepting = true;
        deliver(at(20), node0);
        assertThat(node0.receivedEventWindows).containsExactly(window);
    }

    @Test
    @DisplayName("clearing a node's incoming windows leaves other nodes untouched")
    void clearingIncomingAffectsOneNodeOnly() {
        addEventWindow(NODE_0, NODE_1, 10, 105);
        final PeerEventWindow towardsNodeOne = addEventWindow(NODE_1, NODE_0, 10, 106);

        inFlightEventWindows.clearIncoming(rank(NODE_0));

        deliver(at(20), node0);
        assertThat(node0.receivedEventWindows).isEmpty();

        final RecordingReceiver node1 = new RecordingReceiver(NODE_1);
        deliver(at(20), node1);
        assertThat(node1.receivedEventWindows).containsExactly(towardsNodeOne);
    }

    @Test
    @DisplayName("windows are delivered by arrival time regardless of the order they were added in")
    void windowsAreOrderedByArrivalTime() {
        final PeerEventWindow later = addEventWindow(NODE_1, 20, 105);
        addEventWindow(NODE_1, 10, 106);

        deliver(at(30), node0);

        assertThat(node0.receivedEventWindows).containsExactly(later);
    }

    /**
     * Puts an event window in flight towards {@link #NODE_0}.
     *
     * @param sender        the node whose window it is
     * @param arrivalMillis how long after the start of the test the window arrives
     * @param round         the round the window is at, which distinguishes one window from another
     * @return the window paired with its sender, which is what delivering it should produce
     */
    private PeerEventWindow addEventWindow(@NonNull final NodeId sender, final long arrivalMillis, final long round) {
        return addEventWindow(NODE_0, sender, arrivalMillis, round);
    }

    /**
     * Puts an event window in flight.
     *
     * @param receiver      the node the window is traveling to
     * @param sender        the node whose window it is
     * @param arrivalMillis how long after the start of the test the window arrives
     * @param round         the round the window is at, which distinguishes one window from another
     * @return the window paired with its sender, which is what delivering it should produce
     */
    private PeerEventWindow addEventWindow(
            @NonNull final NodeId receiver, @NonNull final NodeId sender, final long arrivalMillis, final long round) {

        final EventWindow eventWindow = new EventWindow(round, round, round, round);
        inFlightEventWindows.add(
                rank(sender), rank(receiver), new EventWindowInTransit(eventWindow, sender, at(arrivalMillis)));
        return new PeerEventWindow(sender, eventWindow);
    }

    /**
     * @param millis how long after the start of the test
     * @return the instant that many milliseconds after the start of the test
     */
    private static Instant at(final long millis) {
        return START.plusMillis(millis);
    }

    /**
     * Gets the rank of a node. The nodes of these tests have the ids {@code 0} to {@code n - 1}, so a node's rank,
     * which is its position in node id order, is its id.
     *
     * @param nodeId the node
     * @return the rank of the node
     */
    private static int rank(@NonNull final NodeId nodeId) {
        return (int) nodeId.id();
    }

    /**
     * Delivers what has arrived at a receiver.
     *
     * @param now      the current time
     * @param receiver the receiver to deliver to
     */
    private void deliver(@NonNull final Instant now, @NonNull final RecordingReceiver receiver) {
        inFlightEventWindows.deliverArrived(now, rank(receiver.getNodeId()), receiver);
    }
}
