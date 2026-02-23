// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;

/**
 * Super-interface for {@link UnidirectionalConnection} and {@link BidirectionalConnection} that
 * defines common behavior for network connections between nodes.
 */
public interface Connection {

    /**
     * Disconnects two nodes, preventing communication. If the nodes are already disconnected, this method
     * has no effect.
     */
    void disconnect();

    /**
     * Connects two nodes, establishing communication. If the nodes are already connected, this method has
     * no effect.
     */
    void connect();

    /**
     * Checks if two nodes are currently connected.
     *
     * @return {@code true} if the nodes are connected, {@code false} otherwise
     */
    boolean isConnected();

    /**
     * Restores the original connectivity, latency, jitter, and bandwidth for a connection.
     */
    void restoreConnectivity();

    /**
     * Gets the current latency of this connection.
     *
     * @return the current latency
     */
    @NonNull
    Duration latency();

    /**
     * Sets the latency for this connection.
     *
     * @param latency the latency to apply
     */
    void latency(@NonNull Duration latency);

    /**
     * Gets the current jitter percentage of this connection.
     *
     * @return the current jitter percentage
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
     * Restores the original latency and jitter for the connection.
     */
    void restoreLatency();

    /**
     * Sets the bandwidth limit for this connection.
     *
     * @param bandwidthLimit the bandwidth limit to apply
     */
    void bandwidthLimit(@NonNull BandwidthLimit bandwidthLimit);

    /**
     * Gets the current bandwidth limit of this connection.
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
