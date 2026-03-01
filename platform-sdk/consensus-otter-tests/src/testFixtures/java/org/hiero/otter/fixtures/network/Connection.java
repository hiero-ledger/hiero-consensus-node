// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
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
     * Checks if two nodes are currently connected in both directions.
     *
     * @return true if the nodes are connected, false otherwise
     */
    boolean isConnected();

    /**
     * Restores the original connectivity, latency, jitter, and bandwidth for a connection.
     */
    void restoreConnectivity();

    /**
     * Gets the current latency between two nodes.
     *
     * <p>If the latency range between both directions differs, the larger latency is returned.
     *
     * @return the current latency range
     */
    @NonNull
    Duration latency();

    /**
     * Sets the latency for bidirectional communication between two nodes.
     *
     * @param latency the latency to apply
     */
    void latency(@NonNull Duration latency);

    /**
     * Gets the current jitter percentage between two nodes.
     *
     * <p>If the jitter percentage between both directions differs, the larger jitter is returned.
     *
     * @return the current jitter percentage
     */
    @NonNull
    Percentage jitter();

    /**
     * Sets the jitter for bidirectional communication between two nodes.
     *
     * @param jitter the percentage of jitter to apply to the latency
     */
    void jitter(@NonNull Percentage jitter);

    /**
     * Restores the original latency and jitter for the connection.
     */
    void restoreLatency();

    /**
     * Sets the bandwidth limit for bidirectional communication between two nodes.
     *
     * @param bandwidthLimit the bandwidth limit to apply
     */
    void bandwidthLimit(@NonNull BandwidthLimit bandwidthLimit);

    /**
     * Gets the current bandwidth limit between two nodes.
     *
     * <p>If the bandwidth limit between both directions differs, the smaller bandwidth is returned.
     *
     * @return the current bandwidth limit
     */
    @NonNull
    BandwidthLimit bandwidthLimit();

    /**
     * Restores the original bandwidth for a connection.
     */
    void restoreBandwidthLimit();
}
