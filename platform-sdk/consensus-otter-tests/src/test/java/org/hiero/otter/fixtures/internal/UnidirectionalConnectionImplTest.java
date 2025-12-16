// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.time.temporal.ChronoUnit.MILLIS;
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
 * Integration tests for {@code AbstractNetwork.UnidirectionalConnectionImpl}.
 * Tests the behavior through the Network's public API since DirectionalConnectionImpl is private.
 */
class UnidirectionalConnectionImplTest {

    private TestableNetwork network;
    private Node node1;
    private Node node2;

    @BeforeEach
    void setUp() {
        network = new TestableNetwork();
        network.addNodes(2);

        final List<Node> nodes = network.nodes();
        node1 = nodes.get(0);
        node2 = nodes.get(1);
    }

    @Test
    void returnsCorrectNodes() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);

        assertThat(connection.sender()).isEqualTo(node1);
        assertThat(connection.receiver()).isEqualTo(node2);
    }

    @Test
    void changesConnectionState() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection reverse = network.unidirectionalConnection(node2, node1);
        assertThat(connection.isConnected()).isTrue();
        assertThat(reverse.isConnected()).isTrue();

        connection.disconnect();

        assertThat(connection.isConnected()).isFalse();
        assertThat(reverse.isConnected()).isTrue();

        connection.connect();

        assertThat(connection.isConnected()).isTrue();
        assertThat(reverse.isConnected()).isTrue();

        connection.disconnect();

        assertThat(connection.isConnected()).isFalse();
        assertThat(reverse.isConnected()).isTrue();
    }

    @Test
    void setLatencyUpdatesLatency() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final Duration newLatency = connection.latency().plus(100, MILLIS);

        connection.latency(newLatency);

        assertThat(connection.latency()).isEqualTo(newLatency);
    }

    @Test
    void latencyChangesAreIndependentPerDirection() {
        final UnidirectionalConnection forward = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection backward = network.unidirectionalConnection(node2, node1);

        final Duration forwardLatency = Duration.ofMillis(100);
        final Duration backwardLatency = Duration.ofMillis(200);

        forward.latency(forwardLatency);
        backward.latency(backwardLatency);

        assertThat(forward.latency()).isEqualTo(forwardLatency);
        assertThat(backward.latency()).isEqualTo(backwardLatency);
    }

    @Test
    void setJitterUpdatesJitter() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final Percentage newJitter = Percentage.withPercentage(15.0);

        connection.jitter(newJitter);

        assertThat(connection.jitter()).isEqualTo(newJitter);
    }

    @Test
    void jitterChangesAreIndependentPerDirection() {
        final UnidirectionalConnection forward = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection backward = network.unidirectionalConnection(node2, node1);

        final Percentage forwardJitter = Percentage.withPercentage(10.0);
        final Percentage backwardJitter = Percentage.withPercentage(20.0);

        forward.jitter(forwardJitter);
        backward.jitter(backwardJitter);

        assertThat(forward.jitter()).isEqualTo(forwardJitter);
        assertThat(backward.jitter()).isEqualTo(backwardJitter);
    }

    @Test
    void restoreLatencyResetsToDefault() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final Duration originalLatency = connection.latency();
        final Percentage originalJitter = connection.jitter();

        connection.latency(Duration.ofMillis(999));
        connection.jitter(Percentage.withPercentage(50.0));

        connection.restoreLatency();

        assertThat(connection.latency()).isEqualTo(originalLatency);
        assertThat(connection.jitter()).isEqualTo(originalJitter);
    }

    @Test
    void setBandwidthLimitUpdatesBandwidth() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final BandwidthLimit newLimit = BandwidthLimit.ofMegabytesPerSecond(5);

        connection.bandwidthLimit(newLimit);

        assertThat(connection.bandwidthLimit()).isEqualTo(newLimit);
    }

    @Test
    void bandwidthLimitChangesAreIndependentPerDirection() {
        final UnidirectionalConnection forward = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection backward = network.unidirectionalConnection(node2, node1);

        final BandwidthLimit forwardLimit = BandwidthLimit.ofKilobytesPerSecond(1000);
        final BandwidthLimit backwardLimit = BandwidthLimit.ofKilobytesPerSecond(2000);

        forward.bandwidthLimit(forwardLimit);
        backward.bandwidthLimit(backwardLimit);

        assertThat(forward.bandwidthLimit()).isEqualTo(forwardLimit);
        assertThat(backward.bandwidthLimit()).isEqualTo(backwardLimit);
    }

    @Test
    void restoreBandwidthLimitResetsToDefault() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final BandwidthLimit originalLimit = connection.bandwidthLimit();

        connection.bandwidthLimit(BandwidthLimit.ofMegabytesPerSecond(10));

        connection.restoreBandwidthLimit();

        assertThat(connection.bandwidthLimit()).isEqualTo(originalLimit);
    }

    @Test
    void restoreConnectivityResetsAllProperties() {
        final UnidirectionalConnection connection = network.unidirectionalConnection(node1, node2);
        final Duration originalLatency = connection.latency();
        final Percentage originalJitter = connection.jitter();
        final BandwidthLimit originalBandwidth = connection.bandwidthLimit();

        connection.disconnect();
        connection.latency(Duration.ofMillis(999));
        connection.jitter(Percentage.withPercentage(50.0));
        connection.bandwidthLimit(BandwidthLimit.ofMegabytesPerSecond(1));

        connection.restoreConnectivity();

        assertThat(connection.isConnected()).isTrue();
        assertThat(connection.latency()).isEqualTo(originalLatency);
        assertThat(connection.jitter()).isEqualTo(originalJitter);
        assertThat(connection.bandwidthLimit()).isEqualTo(originalBandwidth);
    }

    @Test
    void multipleConnectionObjectsReferSameUnderlyingConnection() {
        final UnidirectionalConnection connection1 = network.unidirectionalConnection(node1, node2);
        final UnidirectionalConnection connection2 = network.unidirectionalConnection(node1, node2);

        connection1.disconnect();
        connection1.latency(Duration.ofMillis(999));
        connection1.jitter(Percentage.withPercentage(50.0));
        connection1.bandwidthLimit(BandwidthLimit.ofMegabytesPerSecond(1));

        assertThat(connection2.isConnected()).isFalse();
        assertThat(connection2.latency()).isEqualTo(Duration.ofMillis(999));
        assertThat(connection2.jitter()).isEqualTo(Percentage.withPercentage(50.0));
        assertThat(connection2.bandwidthLimit()).isEqualTo(BandwidthLimit.ofMegabytesPerSecond(1));
    }

    /**
     * Minimal testable implementation of AbstractNetwork for testing DirectionalConnectionImpl.
     */
    private static class TestableNetwork extends AbstractNetwork {

        TestableNetwork() {
            super(new java.util.Random(42), false);
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
}
