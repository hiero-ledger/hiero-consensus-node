// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a connection between two nodes in a network.
 */
@SuppressWarnings("unused")
public interface Connection {

    /**
     * Gets the first node of the connection.
     *
     * @return the first node in the connection
     */
    @NonNull
    Node node1();

    /**
     * Gets the second node of the connection.
     *
     * @return the second node in the connection
     */
    @NonNull
    Node node2();

    /**
     * Disconnects two nodes, preventing bidirectional communication. If the nodes are already disconnected, this method
     * has no effect.
     */
    void disconnect();

    /**
     * Connects two nodes, establishing bidirectional communication. If the nodes are already connected, this method has
     * no effect.
     */
    void connect();

    /**
     * Checks if two nodes are currently connected.
     *
     * @return true if the nodes are connected, false otherwise
     */
    boolean isConnected();

    /**
     * Sets the latency range for bidirectional communication between two nodes.
     *
     * @param latencyRange the latency range to apply
     */
    void setLatencyRange(@NonNull LatencyRange latencyRange);

    /**
     * Sets asymmetric latency ranges between two nodes.
     *
     * @param fromNode1 latency range from first to second node
     * @param fromNode2 latency range from second to first node
     */
    void setAsymmetricLatencyRange(@NonNull LatencyRange fromNode1, @NonNull LatencyRange fromNode2);

    /**
     * Restores the original latency for the connection.
     */
    void restoreLatencyRange();

    /**
     * Gets the current latency range between two nodes.
     *
     * @return the current latency range
     */
    @NonNull
    LatencyRange latencyRange();

    /**
     * Sets the bandwidth limit for bidirectional communication between two nodes.
     *
     * @param bandwidthLimit the bandwidth limit to apply
     */
    void setBandwidthLimit(@NonNull BandwidthLimit bandwidthLimit);

    /**
     * Sets asymmetric bandwidth limits between two nodes.
     *
     * @param fromNode1 bandwidth limit from first to second node
     * @param fromNode2 bandwidth limit from second to first node
     */
    void setAsymmetricBandwidthLimit(@NonNull BandwidthLimit fromNode1, @NonNull BandwidthLimit fromNode2);

    /**
     * Restores the original bandwidth for a connection.
     */
    void restoreBandwidthLimit();

    /**
     * Gets the current bandwidth limit between two nodes.
     *
     * @return the current bandwidth limit
     */
    @NonNull
    BandwidthLimit bandwidthLimit();
}
