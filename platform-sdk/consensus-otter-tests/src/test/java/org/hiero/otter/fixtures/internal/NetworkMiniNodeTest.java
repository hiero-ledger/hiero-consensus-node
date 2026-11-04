// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.LatencyRange;
import org.hiero.otter.fixtures.network.MeshTopologyConfiguration;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that network operations which must cover all nodes use {@code allNodes()} rather than {@code nodes()}, i.e.,
 * that they also reach mini-nodes.
 */
@DisplayName("Network mini-node Test")
class NetworkMiniNodeTest {

    private TestableNetwork network;
    private Node node1;
    private Node node2;
    private MiniNode miniNode;

    @BeforeEach
    void setUp() {
        network = new TestableNetwork();
        final List<Node> nodes = network.addNodes(2);
        node1 = nodes.get(0);
        node2 = nodes.get(1);
        miniNode = network.addMiniNode();
    }

    @Test
    @DisplayName("Topology cannot be changed after a mini-node was added")
    void topologyIsLockedByMiniNodes() {
        final TestableNetwork miniOnlyNetwork = new TestableNetwork();
        miniOnlyNetwork.addMiniNode();

        assertThatThrownBy(() -> miniOnlyNetwork.topology(MeshTopologyConfiguration.DEFAULT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Node weight and configuration values are applied to mini-nodes")
    void weightAndConfigurationReachMiniNodes() {
        network.nodeWeight(5L);
        network.withConfigValue("some.key", "value");

        verify(miniNode).weight(5L);
        verify(miniNode.configuration()).withConfigValue("some.key", "value");
    }

    @Test
    @DisplayName("Total weight includes mini-nodes")
    void totalWeightIncludesMiniNodes() {
        when(node1.weight()).thenReturn(1L);
        when(node2.weight()).thenReturn(2L);
        when(miniNode.weight()).thenReturn(4L);

        assertThat(network.totalWeight()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Connections to and from mini-nodes are computed and modified")
    void connectionsIncludeMiniNodes() {
        final Duration latency = Duration.ofMillis(123);
        final BandwidthLimit bandwidth = BandwidthLimit.ofMegabytesPerSecond(3);

        network.setLatencyForAllConnections(node1, LatencyRange.of(latency));
        network.setBandwidthForAllConnections(node1, bandwidth);

        final Map<ConnectionKey, ConnectionState> connections = network.lastConnections;
        assertThat(connections).hasSize(6);
        final ConnectionState toMiniNode = connections.get(new ConnectionKey(node1.selfId(), miniNode.selfId()));
        final ConnectionState fromMiniNode = connections.get(new ConnectionKey(miniNode.selfId(), node1.selfId()));
        assertThat(toMiniNode.latency()).isEqualTo(latency);
        assertThat(toMiniNode.bandwidthLimit()).isEqualTo(bandwidth);
        assertThat(fromMiniNode.latency()).isEqualTo(latency);
        assertThat(fromMiniNode.bandwidthLimit()).isEqualTo(bandwidth);
    }

    @Test
    @DisplayName("Mini-nodes are part of network partitions")
    void partitionsIncludeMiniNodes() {
        network.createNetworkPartition(node1, node2);

        assertThat(network.getNetworkPartitionContaining(miniNode)).isNotNull();
        assertThat(network.connectionState(node1, miniNode).connected()).isFalse();
        assertThatThrownBy(() -> network.createNetworkPartition(node1, node2, miniNode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Mini-nodes count as peers when checking whether nodes are behind")
    void miniNodesArePeersInBehindCheck() {
        final EventWindow behind = new EventWindow(1, 2, 1, 1);
        final EventWindow ahead = new EventWindow(100, 101, 90, 80);
        when(node1.latestEventWindow()).thenReturn(behind);
        when(node2.latestEventWindow()).thenReturn(behind);
        when(miniNode.latestEventWindow()).thenReturn(ahead);

        assertThat(network.nodesAreBehindByNodeCount(node1)).isTrue();
    }

    @Test
    @DisplayName("A node is behind by weight if a strong minority of the weight, including mini-nodes, is ahead")
    void nodeIsBehindByNodeWeight() {
        final EventWindow behind = new EventWindow(1, 2, 1, 1);
        final EventWindow ahead = new EventWindow(100, 101, 90, 80);
        when(node1.weight()).thenReturn(1L);
        when(node2.weight()).thenReturn(1L);
        when(miniNode.weight()).thenReturn(4L);
        when(node1.latestEventWindow()).thenReturn(behind);
        when(node2.latestEventWindow()).thenReturn(behind);
        when(miniNode.latestEventWindow()).thenReturn(behind);

        assertThat(network.nodeIsBehindByNodeWeight(node1)).isFalse();

        when(miniNode.latestEventWindow()).thenReturn(ahead);

        assertThat(network.nodeIsBehindByNodeWeight(node1)).isTrue();
    }

    /**
     * Minimal implementation of {@link AbstractNetwork} with mocked nodes that records the latest connections.
     */
    private static class TestableNetwork extends AbstractNetwork {

        private Map<ConnectionKey, ConnectionState> lastConnections = Map.of();

        TestableNetwork() {
            super(new Random(42), false);
        }

        private static <T extends MiniNode> T mockNode(@NonNull final Class<T> type, @NonNull final NodeId nodeId) {
            final T node = mock(type);
            when(node.selfId()).thenReturn(nodeId);
            when(node.configuration()).thenReturn(mock(NodeConfiguration.class));
            return node;
        }

        @Override
        @NonNull
        protected Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
            return mockNode(Node.class, nodeId);
        }

        @Override
        @NonNull
        protected InstrumentedNode doCreateInstrumentedNode(
                @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        @NonNull
        protected MiniNode doCreateMiniNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
            return mockNode(MiniNode.class, nodeId);
        }

        @Override
        @NonNull
        protected TimeManager timeManager() {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        @NonNull
        protected TransactionGenerator transactionGenerator() {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
            lastConnections = connections;
        }

        @Override
        protected void recreateConnections(@NonNull final Map<ConnectionKey, ConnectionState> connections) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        protected void doSendQuiescenceCommand(
                @NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {}

        @Override
        protected void preStartHook(@NonNull final Roster roster) {
            // No action needed for this test
        }
    }
}
