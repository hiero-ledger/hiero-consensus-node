// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.junit.jupiter.api.Test;

class FallenBehindDetectorTests {

    /** The production default: half of the peer weight has to report a node before it has fallen behind. */
    private static final double FALLEN_BEHIND_THRESHOLD = 0.5;

    /**
     * The weight every node is given unless a test cares about weights. Chosen so that the weight threshold works out
     * to a whole number: with four nodes it takes two of a node's three peers to put it over.
     */
    private static final long EQUAL_WEIGHT = 100;

    /**
     * A node that is ahead of both {@link #BEHIND} and {@link #FAR_BEHIND}: it has already expired events that neither
     * of them has caught up to, so it reports both of them as behind.
     */
    private static final EventWindow AHEAD = new EventWindow(30, 31, 25, 20);

    /** Behind {@link #AHEAD}, but ahead of {@link #FAR_BEHIND}. */
    private static final EventWindow BEHIND = new EventWindow(10, 11, 5, 3);

    /** Behind both of the others. */
    private static final EventWindow FAR_BEHIND = new EventWindow(2, 3, 1, 1);

    /** Further along than {@link #AHEAD}, which it reports as behind in turn. */
    private static final EventWindow LEADING = new EventWindow(60, 61, 55, 50);

    /** Far enough along that {@link #AHEAD} no longer reports it, but still behind {@link #LEADING}. */
    private static final EventWindow ALONGSIDE_AHEAD = new EventWindow(25, 26, 21, 18);

    private static final ConnectionState CONNECTED =
            new ConnectionState(true, Duration.ofMillis(100), Percentage.withPercentage(10.0), UNLIMITED_BANDWIDTH);

    private static final ConnectionState DISCONNECTED = CONNECTED.withConnected(false);

    /** The state the detector reads, standing in for what {@link SimulatedNetworkConnectivity} would own. */
    private final List<NodeId> nodeIds = new ArrayList<>();

    private final Map<NodeId, EventWindow> eventWindows = new HashMap<>();
    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();

    private final FallenBehindDetector detector = new FallenBehindDetector(nodeIds, eventWindows, connections);

    /**
     * The weight and event window of one node, used to describe a network to {@link #givenNetwork(NodeState...)}.
     *
     * @param weight      the node's weight in the roster
     * @param eventWindow the event window the node has reported
     */
    private record NodeState(long weight, @NonNull EventWindow eventWindow) {}

    /**
     * Sets up a fully connected network of nodes with the given weights and event windows and starts the detector. Node
     * ids are assigned in order, so the first argument describes node 0.
     */
    private void givenNetwork(@NonNull final NodeState... nodeStates) {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (int index = 0; index < nodeStates.length; index++) {
            final NodeId nodeId = node(index);
            nodeIds.add(nodeId);
            eventWindows.put(nodeId, nodeStates[index].eventWindow());
            rosterEntries.add(RosterEntry.newBuilder()
                    .nodeId(nodeId.id())
                    .weight(nodeStates[index].weight())
                    .build());
        }

        for (final NodeId sender : nodeIds) {
            for (final NodeId receiver : nodeIds) {
                if (!sender.equals(receiver)) {
                    connections.put(new ConnectionKey(sender, receiver), CONNECTED);
                }
            }
        }

        detector.start(Roster.newBuilder().rosterEntries(rosterEntries).build(), FALLEN_BEHIND_THRESHOLD);
    }

    /**
     * Sets up a fully connected network in which every node carries the same weight, described only by the event window
     * each of them has reported.
     */
    private void givenBalancedNetwork(@NonNull final EventWindow... reportedEventWindows) {
        givenNetwork(Arrays.stream(reportedEventWindows)
                .map(eventWindow -> new NodeState(EQUAL_WEIGHT, eventWindow))
                .toArray(NodeState[]::new));
    }

    /**
     * Breaks the connection between two nodes in both directions.
     */
    private void disconnect(@NonNull final NodeId first, @NonNull final NodeId second) {
        connections.put(new ConnectionKey(first, second), DISCONNECTED);
        connections.put(new ConnectionKey(second, first), DISCONNECTED);
        detector.markStale();
    }

    /**
     * Restores the connection between two nodes in both directions.
     */
    private void connect(@NonNull final NodeId first, @NonNull final NodeId second) {
        connections.put(new ConnectionKey(first, second), CONNECTED);
        connections.put(new ConnectionKey(second, first), CONNECTED);
        detector.markStale();
    }

    /**
     * Breaks every connection a node has.
     */
    private void isolate(@NonNull final NodeId nodeId) {
        nodeIds.stream().filter(peerId -> !peerId.equals(nodeId)).forEach(peerId -> disconnect(nodeId, peerId));
    }

    @NonNull
    private static NodeId node(final int id) {
        return NodeId.of(id);
    }

    @Test
    void noNodeHasFallenBehindBeforeTheDetectorIsStarted() {
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);

        // The same state, seen by a detector that has never been given a roster
        final FallenBehindDetector unstarted = new FallenBehindDetector(nodeIds, eventWindows, connections);

        assertThat(unstarted.detectNewlyFallenBehind()).isEmpty();
        assertThat(unstarted.hasFallenBehind(node(0))).isFalse();
    }

    @Test
    void nodeBehindEnoughPeerWeightHasFallenBehind() {
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);

        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0));
        assertThat(detector.hasFallenBehind(node(0))).isTrue();

        // The nodes that are ahead are on the other side of the same comparison
        assertThat(detector.hasFallenBehind(node(1))).isFalse();
        assertThat(detector.hasFallenBehind(node(2))).isFalse();
        assertThat(detector.hasFallenBehind(node(3))).isFalse();
    }

    @Test
    void oneReportingPeerOutOfThreeIsNotEnoughWeight() {
        givenBalancedNetwork(BEHIND, AHEAD, BEHIND, BEHIND);

        assertThat(detector.detectNewlyFallenBehind()).isEmpty();
        assertThat(detector.hasFallenBehind(node(0))).isFalse();
    }

    @Test
    void twoReportingPeersOutOfThreeAreEnoughWeight() {
        givenBalancedNetwork(BEHIND, AHEAD, AHEAD, BEHIND);

        // Both of the nodes on the old window are found, in node id order
        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0), node(3));
    }

    @Test
    void nodeIsolatedBeforeAnyPeerReportsItHasNotFallenBehind() {
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);
        isolate(node(0));

        // A node hears that it is behind from the peers it syncs with. This one drifted out of reach before any of
        // them got to say so, and being behind is not something it can work out on its own.
        assertThat(detector.detectNewlyFallenBehind()).isEmpty();
        assertThat(detector.hasFallenBehind(node(0))).isFalse();
    }

    @Test
    void peerThatHasNeverBeenReachedDoesNotReport() {
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);
        disconnect(node(0), node(2));
        disconnect(node(0), node(3));

        // All three peers are ahead, but two of them have never had a sync in which to say so
        assertThat(detector.detectNewlyFallenBehind()).isEmpty();
        assertThat(detector.hasFallenBehind(node(0))).isFalse();
    }

    @Test
    void reportOutlivesTheConnectionItCameOver() {
        // Node 0 is behind all three of its peers but can only reach node 1, which leaves it below the threshold
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);
        disconnect(node(0), node(2));
        disconnect(node(0), node(3));

        assertThat(detector.detectNewlyFallenBehind()).isEmpty();

        // Node 1 reported node 0 as behind on that check, and only node 1 can withdraw that. Losing the connection
        // does not, so when node 2 reaches node 0 and says the same thing, the two reports add up and carry node 0
        // over the threshold - even though it was never able to reach both peers at once.
        disconnect(node(0), node(1));
        connect(node(0), node(2));

        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0));
    }

    @Test
    void peerWithdrawsItsOwnReportOnceTheNodeCatchesUpToIt() {
        // Five nodes, so node 0 needs three of its four peers to report it. It starts out able to reach two of them,
        // one of which - node 1 - is only moderately ahead.
        givenBalancedNetwork(FAR_BEHIND, AHEAD, LEADING, LEADING, AHEAD);
        disconnect(node(0), node(3));
        disconnect(node(0), node(4));

        assertThat(detector.detectNewlyFallenBehind()).isEmpty();

        // Node 0 makes progress: enough to draw level with node 1, not enough to catch the two leaders. Node 1 is
        // still reachable and so takes its own report back, which is the only way a report is ever withdrawn. Node 3
        // arriving in its place leaves the count where it was rather than completing it.
        eventWindows.put(node(0), ALONGSIDE_AHEAD);
        connect(node(0), node(3));

        assertThat(detector.detectNewlyFallenBehind()).isEmpty();
        assertThat(detector.hasFallenBehind(node(0))).isFalse();
    }

    @Test
    void peerThatHasFallenBehindDoesNotReport() {
        // Node 0 carries enough weight that node 3 would be over the threshold on its report alone
        givenNetwork(
                new NodeState(5, BEHIND),
                new NodeState(5, AHEAD),
                new NodeState(1, AHEAD),
                new NodeState(1, FAR_BEHIND));

        // Node 3 is behind node 0, but node 0 is found first and has stopped gossiping by the time node 3 is checked,
        // so it never gets to say so. What is left - the weight of nodes 1 and 2 - lands exactly on the threshold
        // rather than over it.
        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0));
        assertThat(detector.hasFallenBehind(node(3))).isFalse();
    }

    @Test
    void nodeIsOnlyReportedOnce() {
        givenBalancedNetwork(FAR_BEHIND, AHEAD, AHEAD, AHEAD);

        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0));

        detector.markStale();
        assertThat(detector.detectNewlyFallenBehind()).isEmpty();
        assertThat(detector.hasFallenBehind(node(0))).isTrue();
    }

    @Test
    void checkIsSkippedUntilMarkedStale() {
        givenBalancedNetwork(AHEAD, AHEAD, AHEAD, AHEAD);

        // Consumes the check that starting asked for
        assertThat(detector.detectNewlyFallenBehind()).isEmpty();

        eventWindows.put(node(0), FAR_BEHIND);
        assertThat(detector.detectNewlyFallenBehind()).isEmpty();

        detector.markStale();
        assertThat(detector.detectNewlyFallenBehind()).containsExactly(node(0));
    }
}
