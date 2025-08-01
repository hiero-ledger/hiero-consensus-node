// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a connection between two nodes in a network.
 */
@SuppressWarnings("unused")
public interface Connection {

    @NonNull
    Node node1();

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
     * Restores unlimited bandwidth for bidirectional communication between two nodes. Removes any previously set
     * bandwidth limits.
     */
    void removeBandwidthLimit();

    /**
     * Gets the current bandwidth limit between two nodes.
     *
     * @return the current bandwidth limit
     */
    @NonNull
    BandwidthLimit getBandwidthLimit();
}
