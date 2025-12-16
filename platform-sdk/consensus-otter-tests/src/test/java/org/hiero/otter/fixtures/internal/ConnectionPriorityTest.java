// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.Topology;
import org.hiero.otter.fixtures.network.UnidirectionalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for connection priority system in {@link AbstractNetwork}.
 *
 * <p>Priority order (highest to lowest):
 * <ol>
 *   <li>Explicit connection settings via {@link UnidirectionalConnection#connect()}/{@link UnidirectionalConnection#disconnect()}</li>
 *   <li>Network partitions</li>
 *   <li>Topology configuration</li>
 * </ol>
 */
class ConnectionPriorityTest {

    private TestableNetwork network;
    private Node node1;
    private Node node2;
    private Node node3;

    @BeforeEach
    void setUp() {
        network = new TestableNetwork();
        network.addNodes(3);

        final List<Node> nodes = network.nodes();
        node1 = nodes.get(0);
        node2 = nodes.get(1);
        node3 = nodes.get(2);
    }

    @Test
    void topologyConnectedByDefault() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void topologyDisconnectedWhenConfigured() {
        network.setTopologyConnected(false);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void partitionOverridesConnectedTopology() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Initially connected by topology
        assertThat(connection.isConnected()).isTrue();

        // Create partition separating node1 and node2
        network.createNetworkPartition(node1, node3);

        // Partition overrides topology
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void partitionDoesNotOverrideDisconnectedTopology() {
        network.setTopologyConnected(false);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Initially disconnected by topology
        assertThat(connection.isConnected()).isFalse();

        // Create partition separating node1 and node2
        network.createNetworkPartition(node1, node3);

        // Should remain disconnected (partition doesn't change disconnected state)
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void explicitDisconnectOverridesConnectedTopology() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Initially connected by topology
        assertThat(connection.isConnected()).isTrue();

        // Explicit disconnect
        connection.disconnect();

        // Explicit setting overrides topology
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void explicitConnectOverridesDisconnectedTopology() {
        network.setTopologyConnected(false);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Initially disconnected by topology
        assertThat(connection.isConnected()).isFalse();

        // Explicit connect
        connection.connect();

        // Explicit setting overrides topology
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void explicitConnectOverridesPartition() {
        // Create partition separating node1 and node2
        network.createNetworkPartition(node1, node3);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Partition causes disconnection
        assertThat(connection.isConnected()).isFalse();

        // Explicit connect
        connection.connect();

        // Explicit setting overrides partition
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void explicitDisconnectOverridesPartition() {
        final UnidirectionalConnection connection12 = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection connection13 = network.unidirectionalConnection(node1, node3);

        // Create partition with all nodes together
        // (no partition actually, all nodes in same implicit partition)
        assertThat(connection12.isConnected()).isTrue();
        assertThat(connection13.isConnected()).isTrue();

        // Explicitly disconnect connection13
        connection13.disconnect();

        // Explicit setting overrides topology even within same partition
        assertThat(connection13.isConnected()).isFalse();
    }

    @Test
    void restoreConnectivityRemovesExplicitSettingRevealsPartition() {
        // Create partition
        final var partition = network.createNetworkPartition(node1, node3);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Partition causes disconnection
        assertThat(connection.isConnected()).isFalse();

        // Explicit connect overrides partition
        connection.connect();
        assertThat(connection.isConnected()).isTrue();

        // Restore removes explicit setting
        connection.restoreConnectivity();

        // Partition state is now revealed
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void restoreConnectivityRemovesExplicitSettingRevealsTopology() {
        network.setTopologyConnected(false);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Topology causes disconnection
        assertThat(connection.isConnected()).isFalse();

        // Explicit connect overrides topology
        connection.connect();
        assertThat(connection.isConnected()).isTrue();

        // Restore removes explicit setting
        connection.restoreConnectivity();

        // Topology state is now revealed
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void complexScenarioAllThreeLayers() {
        network.setTopologyConnected(false);

        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Base: topology is disconnected
        assertThat(connection.isConnected()).isFalse();

        // Layer 2: partition (but nodes in different partitions doesn't change disconnected state)
        final var partition = network.createNetworkPartition(node1, node3);
        assertThat(connection.isConnected()).isFalse();

        // Layer 3: explicit connect overrides everything
        connection.connect();
        assertThat(connection.isConnected()).isTrue();

        // Remove explicit setting
        connection.restoreConnectivity();

        // Back to partition layer (still disconnected)
        assertThat(connection.isConnected()).isFalse();

        // Remove partition
        network.removeNetworkPartition(partition);

        // Back to topology layer
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void complexScenarioConnectedTopologyWithLayers() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        // Base: topology is connected
        assertThat(connection.isConnected()).isTrue();

        // Layer 2: partition disconnects
        final var partition = network.createNetworkPartition(node1, node3);
        assertThat(connection.isConnected()).isFalse();

        // Layer 3: explicit connect overrides partition
        connection.connect();
        assertThat(connection.isConnected()).isTrue();

        // Layer 3: explicit disconnect
        connection.disconnect();
        assertThat(connection.isConnected()).isFalse();

        // Remove explicit setting
        connection.restoreConnectivity();

        // Back to partition layer (disconnected)
        assertThat(connection.isConnected()).isFalse();

        // Remove partition
        network.removeNetworkPartition(partition);

        // Back to topology layer (connected)
        assertThat(connection.isConnected()).isTrue();
    }

    /**
     * Testable network that allows configuring topology connection state.
     */
    private static class TestableNetwork extends AbstractNetwork {
        private final ControllableTopology controllableTopology;

        TestableNetwork() {
            super(new java.util.Random(42), false);
            this.controllableTopology = new ControllableTopology(super.topology());
        }

        private void setTopologyConnected(final boolean topologyConnected) {
            controllableTopology.setTopologyConnected(topologyConnected);
        }

        @Override
        @NonNull
        public Topology topology() {
            return controllableTopology;
        }

        @Override
        @NonNull
        protected Node doCreateNode(@NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
            final Node node = mock(Node.class);
            when(node.selfId()).thenReturn(nodeId);
            return node;
        }

        @Override
        @NonNull
        protected InstrumentedNode doCreateInstrumentedNode(
                @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
            throw new UnsupportedOperationException("Not needed for this test");
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
        protected void onConnectionsChanged(@NonNull final Map<ConnectionKey, Topology.ConnectionState> connections) {
            // No action needed for this test
        }

        @Override
        protected void doSendQuiescenceCommand(
                @NonNull final QuiescenceCommand command, @NonNull final Duration timeout) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        protected void preStartHook(@NonNull final Roster roster) {
            // No action needed for this test
        }
    }

    /**
     * Custom topology that allows controlling connection state.
     */
    private static class ControllableTopology implements Topology {

        private final Topology delegate;
        private boolean topologyConnected = true;

        private ControllableTopology(@NonNull final Topology delegate) {
            this.delegate = delegate;
        }

        private void setTopologyConnected(final boolean topologyConnected) {
            this.topologyConnected = topologyConnected;
        }

        @Override
        @NonNull
        public List<Node> nodes() {
            return delegate.nodes();
        }

        @Override
        @NonNull
        public Node addNode() {
            return delegate.addNode();
        }

        @Override
        @NonNull
        public List<Node> addNodes(final int count) {
            return delegate.addNodes(count);
        }

        @Override
        @NonNull
        public InstrumentedNode addInstrumentedNode() {
            return delegate.addInstrumentedNode();
        }

        @Override
        @NonNull
        public ConnectionState getConnectionData(@NonNull final Node sender, @NonNull final Node receiver) {
            // Return configurable connection state
            return new ConnectionState(
                    topologyConnected,
                    Duration.ofMillis(50),
                    Percentage.withPercentage(5.0),
                    BandwidthLimit.ofMegabytesPerSecond(100));
        }
    }
}
