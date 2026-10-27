// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import org.hiero.otter.fixtures.network.simulation.RecordingReceiver.PeerEventWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SimulatedNetworkConnectivity Test")
class SimulatedNetworkConnectivityTest {

    private static final NodeId NODE_0 = NodeId.of(0);
    private static final NodeId NODE_1 = NodeId.of(1);
    private static final NodeId NODE_2 = NodeId.of(2);

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /** One way latency of every connection. A round trip is therefore 200 ms. */
    private static final Duration LATENCY = Duration.ofMillis(100);

    /** The reconnect delay is 5 round trips (1000 ms) varied by up to 20% either way. */
    private static final long MIN_RECONNECT_DELAY_MILLIS = 800;

    private static final long MAX_RECONNECT_DELAY_MILLIS = 1200;

    private static final EventWindow WINDOW = new EventWindow(5, 6, 1, 1);

    private SimulatedNetworkConnectivity network;
    private RecordingReceiver node0;
    private RecordingReceiver node1;
    private RecordingReceiver node2;

    /** The millisecond offset of the latest tick. */
    private long now;

    @BeforeEach
    void setUp() {
        network = new SimulatedNetworkConnectivity(new Random(0), START);
        addNodes();
        now = 0;
        network.tick(at(0));
    }

    private void addNodes() {
        node0 = new RecordingReceiver(NODE_0);
        node1 = new RecordingReceiver(NODE_1);
        node2 = new RecordingReceiver(NODE_2);
        network.addNode(NODE_0, node0);
        network.addNode(NODE_1, node1);
        network.addNode(NODE_2, node2);
    }

    @Test
    @DisplayName("the first connections are established and have no reconnect delay")
    void initialConnectionsAreImmediatelyUsable() {
        network.setConnections(connections(true));

        final PlatformEvent event = submit(NODE_0);
        tickTo(1);
        tickTo(101);

        assertThat(node1.receivedEvents).hasSize(1);
        assertThat(node1.receivedEvents.getFirst().getDescriptor()).isEqualTo(event.getDescriptor());
    }

    @Test
    @DisplayName("nothing is delivered on a link before the reconnect delay after it comes up")
    void nothingIsDeliveredBeforeTheReconnectDelay() {
        network.setConnections(connections(true));
        network.setConnections(connections(false));
        tickTo(10);

        network.setConnections(connections(true));
        final long cameUp = now;
        submit(NODE_0);
        tickTo(cameUp + 1);
        tickTo(cameUp + MIN_RECONNECT_DELAY_MILLIS - 1);

        assertThat(node1.receivedEvents).isEmpty();
        assertThat(node1.receivedEventWindows).isEmpty();

        tickTo(cameUp + MAX_RECONNECT_DELAY_MILLIS);

        assertThat(node1.receivedEvents).hasSize(1);
        assertThat(node1.receivedEventWindows).isNotEmpty();
    }

    @Test
    @DisplayName("the delay of one link does not hold up the others")
    void otherLinksAreNotDelayed() {
        network.setConnections(connections(true));
        final Map<ConnectionKey, ConnectionState> oneLinkCut = connections(true);
        cut(oneLinkCut, NODE_0, NODE_1);
        network.setConnections(oneLinkCut);
        tickTo(10);
        network.setConnections(connections(true));

        submit(NODE_0);
        tickTo(11);
        tickTo(120);

        assertThat(node1.receivedEvents).isEmpty();
        assertThat(node2.receivedEvents).hasSize(1);
    }

    @Test
    @DisplayName("nothing gets through if the link goes down again before the delay has passed")
    void linkGoingDownRestartsTheDelay() {
        network.setConnections(connections(true));
        network.setConnections(connections(false));
        tickTo(10);

        network.setConnections(connections(true));
        final long firstUp = now;
        submit(NODE_0);
        tickTo(firstUp + 300);
        network.setConnections(connections(false));
        tickTo(firstUp + 500);
        network.setConnections(connections(true));
        final long secondUp = now;

        // the first delay would have ended here, but the link was down in between
        tickTo(firstUp + MAX_RECONNECT_DELAY_MILLIS);
        tickTo(secondUp + MIN_RECONNECT_DELAY_MILLIS - 1);
        assertThat(node1.receivedEvents).isEmpty();
        assertThat(node1.receivedEventWindows).isEmpty();

        tickTo(secondUp + MAX_RECONNECT_DELAY_MILLIS);
        assertThat(node1.receivedEvents).hasSize(1);
    }

    @Test
    @DisplayName("a node restart counts as all of its links coming up")
    void restartDelaysAllLinksOfTheNode() {
        network.setConnections(connections(true));
        submit(NODE_0);
        tickTo(1);
        tickTo(200);
        assertThat(node1.receivedEvents).hasSize(1);

        network.resetCursor(NODE_1);
        node1.receivedEvents.clear();
        final long restarted = now;
        tickTo(restarted + 1);
        tickTo(restarted + MIN_RECONNECT_DELAY_MILLIS - 1);
        assertThat(node1.receivedEvents).isEmpty();

        tickTo(restarted + MAX_RECONNECT_DELAY_MILLIS);
        assertThat(node1.receivedEvents).hasSize(1);
    }

    @Test
    @DisplayName("a restart does not delay links between the other nodes")
    void restartLeavesOtherLinksAlone() {
        network.setConnections(connections(true));
        network.resetCursor(NODE_1);

        submit(NODE_0);
        tickTo(1);
        tickTo(101);

        assertThat(node2.receivedEvents).hasSize(1);
        assertThat(node1.receivedEvents).isEmpty();
    }

    @Test
    @DisplayName("peers' latest windows are sent again when a link comes up")
    void windowsAreResentWhenLinkComesUp() {
        network.setConnections(connections(true));
        network.updateEventWindow(NODE_0, WINDOW);
        tickTo(1);
        tickTo(101);
        assertThat(node1.receivedEventWindows).contains(new PeerEventWindow(NODE_0, WINDOW));
        node1.receivedEventWindows.clear();

        network.setConnections(connections(false));
        tickTo(200);
        network.setConnections(connections(true));
        final long cameUp = now;

        tickTo(cameUp + MIN_RECONNECT_DELAY_MILLIS - 1);
        assertThat(node1.receivedEventWindows).isEmpty();
        tickTo(cameUp + MAX_RECONNECT_DELAY_MILLIS);
        assertThat(node1.receivedEventWindows).contains(new PeerEventWindow(NODE_0, WINDOW));
    }

    @Test
    @DisplayName("peers' latest windows are sent again to a restarted node")
    void windowsAreResentOnRestart() {
        network.setConnections(connections(true));
        network.updateEventWindow(NODE_0, WINDOW);
        tickTo(1);
        tickTo(101);
        node1.receivedEventWindows.clear();

        network.resetCursor(NODE_1);
        final long restarted = now;

        tickTo(restarted + MIN_RECONNECT_DELAY_MILLIS - 1);
        assertThat(node1.receivedEventWindows).isEmpty();
        tickTo(restarted + MAX_RECONNECT_DELAY_MILLIS);
        assertThat(node1.receivedEventWindows).contains(new PeerEventWindow(NODE_0, WINDOW));
    }

    @Test
    @DisplayName("a one-way cut stops both directions")
    void oneWayCutStopsBothDirections() {
        final Map<ConnectionKey, ConnectionState> connections = connections(true);
        connections.put(new ConnectionKey(NODE_1, NODE_0), state(false));
        network.setConnections(connections);

        submit(NODE_0);
        submit(NODE_1);
        network.updateEventWindow(NODE_0, WINDOW);
        network.updateEventWindow(NODE_1, WINDOW);
        tickTo(1);
        tickTo(10_000);

        assertThat(node0.receivedEvents).isEmpty();
        assertThat(node1.receivedEvents).isEmpty();
        assertThat(node0.receivedEventWindows).isEmpty();
        assertThat(node1.receivedEventWindows).isEmpty();
        // the link to the third node is unaffected
        assertThat(node2.receivedEvents).hasSize(2);
    }

    @Test
    @DisplayName("the reconnect delay is 5 round trips give or take 20%")
    void reconnectDelayIsWithinJitterOfFiveRoundTrips() {
        for (long seed = 0; seed < 20; seed++) {
            network = new SimulatedNetworkConnectivity(new Random(seed), START);
            addNodes();
            now = 0;
            network.tick(at(0));
            network.setConnections(connections(true));
            network.setConnections(connections(false));
            tickTo(10);
            network.setConnections(connections(true));
            final long cameUp = now;
            submit(NODE_0);
            tickTo(cameUp + 1);

            tickTo(cameUp + MIN_RECONNECT_DELAY_MILLIS - 1);
            assertThat(node1.receivedEvents).as("seed %d", seed).isEmpty();
            tickTo(cameUp + MAX_RECONNECT_DELAY_MILLIS);
            assertThat(node1.receivedEvents).as("seed %d", seed).hasSize(1);
        }
    }

    /**
     * Creates and submits an event from a node.
     */
    @NonNull
    private PlatformEvent submit(@NonNull final NodeId creator) {
        final PlatformEvent event =
                new TestingEventBuilder(new Random()).setCreatorId(creator).build();
        new DefaultEventHasher().hashEvent(event);
        event.setSenderId(creator);
        network.submitEvent(event);
        return event;
    }

    private void tickTo(final long millis) {
        now = millis;
        network.tick(at(millis));
    }

    @NonNull
    private static Instant at(final long millis) {
        return START.plusMillis(millis);
    }

    @NonNull
    private static ConnectionState state(final boolean connected) {
        return new ConnectionState(connected, LATENCY, Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH);
    }

    /**
     * Connections between all three nodes, all of them up or all of them down.
     */
    @NonNull
    private static Map<ConnectionKey, ConnectionState> connections(final boolean connected) {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final NodeId sender : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
            for (final NodeId receiver : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
                if (!sender.equals(receiver)) {
                    connections.put(new ConnectionKey(sender, receiver), state(connected));
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
}
