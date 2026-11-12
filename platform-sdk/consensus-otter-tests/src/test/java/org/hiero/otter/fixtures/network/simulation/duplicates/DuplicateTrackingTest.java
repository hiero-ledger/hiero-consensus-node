// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;
import static org.hiero.otter.fixtures.network.simulation.TestRosters.rosterOf;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the duplicate estimate, see {@link DuplicateTracking}.
 *
 * <p>Every connection has a latency of 100 ms and is in broadcast mode, and the duplicates are estimated for node 2. The
 * nodes only know the events they submit: a node that does not submit an event it receives does not know it, as if it
 * waited for a parent forever.
 */
class DuplicateTrackingTest {

    private static final NodeId NODE_0 = NodeId.of(0);
    private static final NodeId NODE_1 = NodeId.of(1);
    private static final NodeId NODE_2 = NodeId.of(2);

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private static final Duration LATENCY = Duration.ofMillis(100);

    private SimulatedNetworkConnectivity network;

    /** The duplicates sent by sync to node 2. */
    private int syncDuplicates;

    /** The broadcast duplicates sent to node 2. */
    private int broadcastDuplicates;

    @BeforeEach
    void setUp() {
        final List<EventReceiver> receivers =
                List.of(new IgnoringReceiver(NODE_0), new IgnoringReceiver(NODE_1), new IgnoringReceiver(NODE_2));
        network = new SimulatedNetworkConnectivity(new Random(0), START, rosterOf(receivers), receivers, false);
        network.tick(START);
    }

    @Test
    @DisplayName("a self event is sent by sync once it is older than selfFilterThreshold")
    void selfEventIsSentAfterSelfThreshold() {
        final Map<ConnectionKey, ConnectionState> connections = connections();
        // only node 0 syncs with node 2
        cut(connections, NODE_1, NODE_2);
        network.setConnections(connections);
        estimateDuplicatesOfNode2();

        // node 0 broadcasts its event to node 2, arriving at 100, which never learns it. Node 0 makes the send lists
        // of its syncs with node 2 at 200, 700, 1000 and 1500
        submit(create(NODE_0), NODE_0);
        tickTo(1);
        tickTo(1400);
        tickTo(1401);
        assertThat(syncDuplicates).isZero();

        // at 1500 the event is older than 1000
        tickTo(1600);
        tickTo(1601);
        assertThat(syncDuplicates).isEqualTo(1);
        assertThat(broadcastDuplicates).isZero();
    }

    @Test
    @DisplayName(
            "a forwarded event that is a parent of a self event of the sender is sent by sync once it is older than "
                    + "ancestorFilterThreshold")
    void parentOfSelfEventIsSentAfterAncestorThreshold() {
        assertThat(duplicatesOfForwardedEvent(true, false, false)).isEqualTo(1);
    }

    @Test
    @DisplayName("a forwarded event that is no parent of an event to send is held back for nonAncestorFilterThreshold")
    void otherEventIsHeldBackForNonAncestorThreshold() {
        assertThat(duplicatesOfForwardedEvent(false, false, false)).isZero();
    }

    @Test
    @DisplayName("an event that is a tip of the receiver known to the sender is not sent")
    void tipOfReceiverKnownToSenderIsNotSent() {
        assertThat(duplicatesOfForwardedEvent(true, true, false)).isZero();
    }

    @Test
    @DisplayName("an event the receiver has built on is sent if the sender knows neither the receiver's child nor the "
            + "receiver its child")
    void eventBuiltOnByBothNodesIsSent() {
        assertThat(duplicatesOfForwardedEvent(true, true, true)).isEqualTo(1);
    }

    /**
     * Has node 0 broadcast an event to nodes 1 and 2, arriving at 100, which node 1 forwards at 100. Node 1 makes the
     * send lists of its syncs with node 2 at 200 and 700, after which the duplicates are counted. In the sync whose list
     * is made at 700, which node 2 starts at 400, node 2 samples its tips at 400, node 1 samples its own and reads those
     * of node 2 at 500, and node 2 answers at 600.
     *
     * @param senderBuildsOnEvent    whether node 1 creates a child of the event at 200, which node 2 never learns, and
     *                               which marks the event as a parent of a self event, so that it is held back for 250
     *                               instead of 3000
     * @param receiverKnowsEvent     whether node 2 learns the event at 100
     * @param receiverBuildsOnEvent  whether node 2 creates a child of the event at 150, which node 1 never learns
     * @return the duplicates sent by sync
     */
    private int duplicatesOfForwardedEvent(
            final boolean senderBuildsOnEvent, final boolean receiverKnowsEvent, final boolean receiverBuildsOnEvent) {
        network.setConnections(connections());
        estimateDuplicatesOfNode2();

        final PlatformEvent event = create(NODE_0);
        submit(event, NODE_0);
        tickTo(1);
        tickTo(100);
        submit(event, NODE_1);
        if (receiverKnowsEvent) {
            submit(event, NODE_2);
        }
        tickTo(101);
        tickTo(150);
        if (receiverBuildsOnEvent) {
            submit(createChild(NODE_2, event), NODE_2);
        }
        tickTo(151);
        tickTo(200);
        if (senderBuildsOnEvent) {
            submit(createChild(NODE_1, event), NODE_1);
        }
        tickTo(201);
        // node 0 sends its own event only after 1000, and node 1 its child too
        tickTo(900);
        tickTo(901);

        assertThat(broadcastDuplicates).isZero();
        return syncDuplicates;
    }

    @Test
    @DisplayName("duplicates cannot be estimated for a node that is not part of the network")
    void unknownReceiverIsRejected() {
        assertThatThrownBy(() -> DuplicateTracking.estimator(network, List.of(NodeId.of(99)), (r, e, s, b) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Adds the duplicate estimate for node 2 as an observer of the network, counting its duplicates.
     */
    private void estimateDuplicatesOfNode2() {
        network.addObserver(DuplicateTracking.estimator(
                network, List.of(NODE_2), (receiver, event, syncCopies, broadcastCopies) -> {
                    assertThat(receiver).isEqualTo(NODE_2);
                    syncDuplicates += syncCopies;
                    broadcastDuplicates += broadcastCopies;
                }));
    }

    private void tickTo(final long millis) {
        network.tick(START.plusMillis(millis));
    }

    /**
     * Has a node submit an event, which it knows from the time of the tick it is submitted at.
     */
    private void submit(@NonNull final PlatformEvent event, @NonNull final NodeId submitter) {
        final PlatformEvent copy = event.copyGossipedData();
        copy.setSenderId(submitter);
        network.submitEvent(copy);
    }

    @NonNull
    private static PlatformEvent create(@NonNull final NodeId creator) {
        final PlatformEvent event =
                new TestingEventBuilder(new Random()).setCreatorId(creator).build();
        new DefaultEventHasher().hashEvent(event);
        return event;
    }

    @NonNull
    private static PlatformEvent createChild(@NonNull final NodeId creator, @NonNull final PlatformEvent parent) {
        final PlatformEvent event = new TestingEventBuilder(new Random())
                .setCreatorId(creator)
                .setOtherParent(parent)
                .build();
        new DefaultEventHasher().hashEvent(event);
        return event;
    }

    /**
     * Connections between all three nodes, all of them up.
     */
    @NonNull
    private static Map<ConnectionKey, ConnectionState> connections() {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final NodeId sender : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
            for (final NodeId receiver : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
                if (!sender.equals(receiver)) {
                    connections.put(new ConnectionKey(sender, receiver), state(true));
                }
            }
        }
        return connections;
    }

    private static void cut(
            @NonNull final Map<ConnectionKey, ConnectionState> connections,
            @NonNull final NodeId a,
            @NonNull final NodeId b) {
        connections.put(new ConnectionKey(a, b), state(false));
        connections.put(new ConnectionKey(b, a), state(false));
    }

    @NonNull
    private static ConnectionState state(final boolean connected) {
        return new ConnectionState(connected, LATENCY, Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH);
    }

    /**
     * A node that accepts everything and keeps nothing; what it knows is decided by what the test submits for it.
     */
    private record IgnoringReceiver(@NonNull NodeId getNodeId) implements EventReceiver {

        @Override
        public boolean receiveEvent(@NonNull final PlatformEvent event) {
            return true;
        }

        @Override
        public boolean receiveEventWindow(@NonNull final NodeId sender, @NonNull final EventWindow eventWindow) {
            return true;
        }
    }
}
