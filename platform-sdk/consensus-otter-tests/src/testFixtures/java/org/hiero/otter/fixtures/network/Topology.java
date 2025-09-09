// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a network topology.
 *
 * <p>This interface defines methods for adding nodes to a topology. The specific way nodes are connected and their
 * properties such as latency, jitter, and bandwidth depend on the implementation of the topology.
 */
@SuppressWarnings("unused")
public interface Topology {

    /** Default connection data for a disconnected state. */
    ConnectionData DISCONNECTED =
            new ConnectionData(false, Duration.ZERO, Percentage.withPercentage(0), BandwidthLimit.UNLIMITED);

    /**
     * Adds a single node to the topology.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the specific topology implementation.
     *
     * @return the created node
     */
    @NonNull
    default Node addNode() {
        return addNodes(1).getFirst();
    }

    /**
     * Adds multiple nodes to the topology.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the specific topology implementation.
     *
     * @param count the number of nodes to add
     * @return list of created nodes
     */
    @NonNull
    List<Node> addNodes(int count);

    /**
     * Add an instrumented node to the topology.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @return the added instrumented node
     */
    @NonNull
    InstrumentedNode addInstrumentedNode();

    /**
     * Get the list of nodes in the topology.
     *
     * <p>The {@link List} cannot be modified directly. However, if a node is added or removed from the topology, the
     * list is automatically updated. That means, if it is necessary to have a constant list, it is recommended to
     * create a copy.
     *
     * @return a list of nodes in the topology
     */
    @NonNull
    List<Node> nodes();

    /**
     * Get the default connection data for a connection between two nodes in the topology.
     *
     * @param sender the source node
     * @param receiver the destination node
     * @return the {@link ConnectionData}
     */
    @NonNull
    ConnectionData getConnectionData(@NonNull Node sender, @NonNull Node receiver);

    /**
     * Represents the data associated with a connection between two nodes in the topology.
     *
     * @param connected indicates whether the nodes are connected
     */
    record ConnectionData(
            boolean connected,
            @NonNull Duration latency,
            @NonNull Percentage jitter,
            @NonNull BandwidthLimit bandwidthLimit) {

        /**
         * Creates a new instance of {@link ConnectionData} with the specified {@code connected} parameters.
         *
         * @param connected indicates whether the nodes are connected
         * @return a new instance of {@link ConnectionData} with the specified connected state
         */
        public ConnectionData withConnected(final boolean connected) {
            return new ConnectionData(connected, latency, jitter, bandwidthLimit);
        }

        /**
         * Creates a new instance of {@link ConnectionData} with the specified {@code latency} and {@code jitter}.
         *
         * @param latency the latency of the connection
         * @param jitter the jitter of the connection
         * @return a new instance of {@link ConnectionData} with the specified latency and jitter
         */
        public ConnectionData withLatencyAndJitter(@NonNull final Duration latency, @NonNull final Percentage jitter) {
            return new ConnectionData(connected, latency, jitter, bandwidthLimit);
        }

        /**
         * Creates a new instance of {@link ConnectionData} with the specified {@code bandwidthLimit}.
         *
         * @param bandwidthLimit the bandwidth limit of the connection
         * @return a new instance of {@link ConnectionData} with the specified bandwidth limit
         */
        public ConnectionData withBandwidthLimit(@NonNull final BandwidthLimit bandwidthLimit) {
            return new ConnectionData(connected, latency, jitter, bandwidthLimit);
        }
    }
}
