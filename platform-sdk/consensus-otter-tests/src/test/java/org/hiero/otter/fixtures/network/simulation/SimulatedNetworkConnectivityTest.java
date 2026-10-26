// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;

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
        setUpNodes();
        now = 0;
        network.tick(at(0));
    }

    private void setUpNodes() {
        node0 = new RecordingReceiver(NODE_0);
        node1 = new RecordingReceiver(NODE_1);
        node2 = new RecordingReceiver(NODE_2);
        network.setUp(List.of(node0, node1, node2));
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
    @DisplayName("connections set before the network is set up are applied in order when it is")
    void connectionsSetBeforeSetUpAreAppliedInOrder() {
        network = new SimulatedNetworkConnectivity(new Random(0), START);
        network.setConnections(connections(true));
        network.setConnections(connections(false));
        network.setConnections(connections(true));
        setUpNodes();
        network.tick(at(0));

        // the links came up again after the first connections, so they wait out a reconnect delay
        submit(NODE_0);
        tickTo(1);
        tickTo(MIN_RECONNECT_DELAY_MILLIS - 1);
        assertThat(node1.receivedEvents).isEmpty();
        tickTo(MAX_RECONNECT_DELAY_MILLIS);
        assertThat(node1.receivedEvents).hasSize(1);
    }

    @Test
    @DisplayName("setting up again with the same nodes keeps the network, and with other nodes is rejected")
    void settingUpAgainKeepsTheNetwork() {
        network.setConnections(connections(true));

        // the same nodes in a different order are still the same nodes
        network.setUp(
                List.of(new RecordingReceiver(NODE_2), new RecordingReceiver(NODE_0), new RecordingReceiver(NODE_1)));
        submit(NODE_0);
        tickTo(1);
        tickTo(101);
        assertThat(node1.receivedEvents).hasSize(1);

        assertThatThrownBy(() -> network.setUp(List.of(new RecordingReceiver(NODE_0), new RecordingReceiver(NODE_1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a node cannot have more than one event receiver")
    void duplicateEventReceiversAreRejected() {
        final SimulatedNetworkConnectivity fresh = new SimulatedNetworkConnectivity(new Random(0), START);

        assertThatThrownBy(() -> fresh.setUp(List.of(new RecordingReceiver(NODE_0), new RecordingReceiver(NODE_0))))
                .isInstanceOf(IllegalArgumentException.class);
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
            setUpNodes();
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

    @Test
    @DisplayName("an own event on a link in broadcast mode arrives after the link latency")
    void ownEventArrivesAfterLatency() {
        network.setConnections(connections(true));

        final PlatformEvent event = submit(NODE_0);
        tickTo(1);
        tickTo(100);
        assertThat(node1.receivedEvents).isEmpty();
        tickTo(101);

        assertThat(node1.receivedEvents).hasSize(1);
        assertThat(event.getTimeReceived()).isNotNull();
        assertThat(node1.receivedEvents.getFirst().getTimeReceived()).isEqualTo(at(101));
    }

    @Test
    @DisplayName("a forwarded copy arrives after hold-back, round trip, half the pause and the latency")
    void forwardedCopyArrivesAfterSyncDelay() {
        network.setConnections(connections(true));

        // only the forwarded copy is submitted: the creator itself is not part of this test
        forward(submit(NODE_0), NODE_1);
        tickTo(1);
        // 250 hold-back + 200 round trip + 50 half the pause + 100 latency
        tickTo(600);
        assertThat(forwardedBy(node2, NODE_1)).isEmpty();
        tickTo(601);

        assertThat(forwardedBy(node2, NODE_1)).hasSize(1);
        assertThat(forwardedBy(node2, NODE_1).getFirst().getTimeReceived()).isEqualTo(at(601));
    }

    @Test
    @DisplayName("a link leaves broadcast mode above a 900 ms round trip and returns 30 s after falling below it")
    void broadcastModeFollowsRoundTrip() {
        network.setConnections(uniform(Duration.ofMillis(500), true));

        // no broadcast: no hold-back, 1000 round trip + 2.5 half the pause + 500 latency
        submit(NODE_0);
        tickTo(1);
        tickTo(2000);
        assertThat(node1.receivedEvents.getFirst().getTimeReceived()).isEqualTo(at(1).plusNanos(1_502_500_000L));

        // the round trip falls to 200 ms
        tickTo(10_000);
        network.setConnections(uniform(Duration.ofMillis(100), true));

        // still out of broadcast mode just before the 30 s have passed: 200 round trip + 2.5 + 100 latency
        tickTo(39_990);
        submit(NODE_0);
        tickTo(39_991);
        tickTo(41_000);
        assertThat(node1.receivedEvents.get(1).getTimeReceived())
                .isEqualTo(at(39_991).plusNanos(302_500_000L));

        // back in broadcast mode: only the latency
        submit(NODE_0);
        tickTo(41_001);
        tickTo(42_000);
        assertThat(node1.receivedEvents.get(2).getTimeReceived()).isEqualTo(at(41_101));
    }

    @Test
    @DisplayName("the receiver gets the creator's copy before a copy forwarded along a faster path")
    void creatorsCopyArrivesFirst() {
        final Map<ConnectionKey, ConnectionState> uneven = new HashMap<>();
        setLatency(uneven, NODE_0, NODE_2, 250);
        setLatency(uneven, NODE_0, NODE_1, 20);
        setLatency(uneven, NODE_1, NODE_2, 120);
        network.setConnections(uneven);

        final PlatformEvent event = submit(NODE_0);
        tickTo(1);
        tickTo(21);
        assertThat(node1.receivedEvents).hasSize(1);
        forward(event, NODE_1);
        tickTo(22);
        tickTo(1000);

        // creator's copy: 1 + 250. forwarded copy: 22 + 250 + 240 + 50 + 120
        assertThat(node2.receivedEvents).hasSize(2);
        assertThat(node2.receivedEvents.get(0).getSenderId()).isEqualTo(NODE_0);
        assertThat(node2.receivedEvents.get(0).getTimeReceived()).isEqualTo(at(251));
        assertThat(node2.receivedEvents.get(1).getSenderId()).isEqualTo(NODE_1);
        assertThat(node2.receivedEvents.get(1).getTimeReceived()).isEqualTo(at(682));
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

    /**
     * Submits a copy of an event as forwarded by a node.
     */
    private void forward(@NonNull final PlatformEvent event, @NonNull final NodeId forwarder) {
        final PlatformEvent copy = event.copyGossipedData();
        copy.setSenderId(forwarder);
        network.submitEvent(copy);
    }

    @NonNull
    private static List<PlatformEvent> forwardedBy(
            @NonNull final RecordingReceiver receiver, @NonNull final NodeId sender) {
        return receiver.receivedEvents.stream()
                .filter(e -> sender.equals(e.getSenderId()))
                .toList();
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

    /**
     * Connections between all three nodes with the same latency.
     */
    @NonNull
    private static Map<ConnectionKey, ConnectionState> uniform(final Duration latency, final boolean connected) {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final NodeId sender : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
            for (final NodeId receiver : new NodeId[] {NODE_0, NODE_1, NODE_2}) {
                if (!sender.equals(receiver)) {
                    connections.put(
                            new ConnectionKey(sender, receiver),
                            new ConnectionState(
                                    connected, latency, Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH));
                }
            }
        }
        return connections;
    }

    private static void setLatency(
            @NonNull final Map<ConnectionKey, ConnectionState> connections,
            @NonNull final NodeId a,
            @NonNull final NodeId b,
            final long millis) {
        final ConnectionState state = new ConnectionState(
                true, Duration.ofMillis(millis), Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH);
        connections.put(new ConnectionKey(a, b), state);
        connections.put(new ConnectionKey(b, a), state);
    }

    private static void cut(
            @NonNull final Map<ConnectionKey, ConnectionState> connections,
            @NonNull final NodeId a,
            @NonNull final NodeId b) {
        connections.put(new ConnectionKey(a, b), state(false));
        connections.put(new ConnectionKey(b, a), state(false));
    }
}
