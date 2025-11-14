// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a single direction of a connection between two nodes in a network.
 */
@SuppressWarnings("unused")
public interface DirectionalConnection {

    /**
     * Gets the start node of the connection.
     *
     * @return the start node of the connection
     */
    @NonNull
    Node sender();

    /**
     * Gets the end node of the connection.
     *
     * @return the end node of the connection
     */
    @NonNull
    Node receiver();

    /**
     * Disconnects two nodes, preventing communication from the start node to the end node.
     */
    void disconnect();

    /**
     * Connects two nodes, establishing communication from the start node to the end node.
     */
    void connect();

    /**
     * Checks if there is currently a connection from the start node to the end node.
     *
     * @return {@code true} if the nodes are connected, {@code false} otherwise
     */
    boolean isConnected();

    /**
     * Restores the original connectivity, latency, jitter, and bandwidth for this connection.
     */
    void restoreConnectivity();

    /**
     * Gets the current latency from the start node to the end node.
     *
     * @return the current latency, {@code null} if the latency has not been altered
     */
    @NonNull
    Duration latency();

    /**
     * Sets the latency for the communication from the start node to the end node.
     *
     * @param latency the latency to apply
     */
    void latency(@NonNull Duration latency);

    /**
     * Gets the current jitter of this connection.
     *
     * @return the current jitter, {@code null} if the jitter has not been altered
     */
    @NonNull
    Percentage jitter();

    /**
     * Sets the jitter for this connection.
     *
     * @param jitter the percentage of jitter to apply to the latency
     */
    void jitter(@NonNull Percentage jitter);

    /**
     * Restores the original latency (and jitter) for this connection.
     */
    void restoreLatency();

    /**
     * Gets the current bandwidth limit from the start node to the end node.
     *
     * @return the current bandwidth limit, {@code null} if the bandwidth limit has not been altered
     */
    @NonNull
    BandwidthLimit bandwidthLimit();

    /**
     * Sets the bandwidth limit for this connection.
     *
     * @param bandwidthLimit the bandwidth limit to apply
     */
    void bandwidthLimit(@NonNull BandwidthLimit bandwidthLimit);

    /**
     * Restores the original bandwidth for a connection.
     */
    void restoreBandwidthLimit();
}
