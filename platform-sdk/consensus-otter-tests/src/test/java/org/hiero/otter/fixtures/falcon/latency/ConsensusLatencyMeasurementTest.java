// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.latency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.otter.fixtures.network.BandwidthLimit.UNLIMITED_BANDWIDTH;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.hiero.otter.fixtures.network.simulation.TestRosters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConsensusLatencyMeasurement}, which follow a simulated network of three nodes whose connections all
 * have a latency of 100 ms, with node 2 as the only full node.
 */
class ConsensusLatencyMeasurementTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final NodeId NODE_0 = NodeId.of(0);
    private static final NodeId NODE_1 = NodeId.of(1);
    private static final NodeId FULL_NODE = NodeId.of(2);
    private static final List<NodeId> NODES = List.of(NODE_0, NODE_1, FULL_NODE);

    @Test
    @DisplayName(
            "Events are counted when their creators submit them, and their C2C when the full node reaches consensus")
    void measuresCreatedEventsAndC2C() {
        final ConsensusLatencyMeasurement measurement = ConsensusLatencyMeasurement.create();
        final SimulatedNetworkConnectivity network = startedNetwork(measurement);

        final PlatformEvent event = create(NODE_0);
        submit(network, event, NODE_0);
        // a forwarded copy is not an event created
        submit(network, event, NODE_1);
        network.tick(START.plusMillis(1));
        measurement.consensusRound(FULL_NODE, round(event.getTimeCreated().plusMillis(50), List.of(event)));

        final ConsensusLatencyResult result = measurement.snapshot(START.plusSeconds(1));
        assertThat(result.nodes()).isEqualTo(3);
        assertThat(result.eventsPerSec()).isEqualTo(1);
        assertThat(result.c2cSamples()).isEqualTo(1);
        assertThat(result.maxC2C()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("A measurement cannot be read or restarted before the network has started")
    void requiresAStartedNetwork() {
        final ConsensusLatencyMeasurement measurement = ConsensusLatencyMeasurement.create();

        assertThatThrownBy(() -> measurement.snapshot(START)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> measurement.restart(START)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Only a measurement with duplicates estimates them")
    void onlyEstimatesDuplicatesWhenAskedFor() {
        assertThat(duplicateBytesPerSec(ConsensusLatencyMeasurement.create())).isZero();
        assertThat(duplicateBytesPerSec(ConsensusLatencyMeasurement.createWithDuplicates()))
                .isPositive();
    }

    /**
     * Has node 0 broadcast an event to the full node, which never learns it, so that node 0 sends it again by sync once
     * it is older than {@code selfFilterThreshold}, as a duplicate.
     *
     * @param measurement the measurement
     * @return the duplicate bytes per second it reports
     */
    private static long duplicateBytesPerSec(@NonNull final ConsensusLatencyMeasurement measurement) {
        final SimulatedNetworkConnectivity network = startedNetwork(measurement);
        submit(network, create(NODE_0), NODE_0);
        for (long millis = 1; millis <= 2000; millis += 100) {
            network.tick(START.plusMillis(millis));
        }
        return measurement.snapshot(START.plusMillis(2000)).duplicateBytesPerSec();
    }

    /**
     * Creates a network followed by a measurement, and starts it as Falcon would.
     */
    @NonNull
    private static SimulatedNetworkConnectivity startedNetwork(@NonNull final ConsensusLatencyMeasurement measurement) {
        final List<EventReceiver> receivers =
                NODES.stream().<EventReceiver>map(IgnoringReceiver::new).toList();
        final SimulatedNetworkConnectivity network = new SimulatedNetworkConnectivity(
                new Random(0), START, TestRosters.rosterOf(receivers), receivers, false);
        network.tick(START);
        network.setConnections(connections());
        measurement.nodeAdded(NODE_0, false);
        measurement.nodeAdded(NODE_1, false);
        measurement.nodeAdded(FULL_NODE, true);
        measurement.networkStarting(network, START);
        return network;
    }

    private static void submit(
            @NonNull final SimulatedNetworkConnectivity network,
            @NonNull final PlatformEvent event,
            @NonNull final NodeId submitter) {
        final PlatformEvent copy = event.copyGossipedData();
        copy.setSenderId(submitter);
        network.submitEvent(copy);
    }

    @NonNull
    private static PlatformEvent create(@NonNull final NodeId creator) {
        final PlatformEvent event = new TestingEventBuilder(new Random())
                .setCreatorId(creator)
                .setTimeCreated(START)
                .build();
        new DefaultEventHasher().hashEvent(event);
        return event;
    }

    @NonNull
    private static ConsensusRound round(@NonNull final Instant reached, @NonNull final List<PlatformEvent> events) {
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getReachedConsTimestamp()).thenReturn(reached);
        when(round.getConsensusEvents()).thenReturn(events);
        return round;
    }

    /**
     * Connections between all nodes, all of them up.
     */
    @NonNull
    private static Map<ConnectionKey, ConnectionState> connections() {
        final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
        for (final NodeId sender : NODES) {
            for (final NodeId receiver : NODES) {
                if (!sender.equals(receiver)) {
                    connections.put(
                            new ConnectionKey(sender, receiver),
                            new ConnectionState(
                                    true, Duration.ofMillis(100), Percentage.withPercentage(0.0), UNLIMITED_BANDWIDTH));
                }
            }
        }
        return connections;
    }

    /**
     * A node that accepts everything and keeps nothing; it knows only the events it submits.
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
