// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The state of a simulated network that a {@link NetworkObserver} reads, without changing it. Implemented by
 * {@link SimulatedNetworkConnectivity}.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation.
 */
public interface NetworkView {

    /**
     * @return the number of nodes in the network
     */
    int nodeCount();

    /**
     * @param rank the rank of a node
     * @return the id of the node
     */
    @NonNull
    NodeId nodeId(int rank);

    /**
     * @param nodeId the id of a node
     * @return the rank of the node, or {@code -1} if it is not part of the network
     */
    int rankOf(@NonNull NodeId nodeId);

    /**
     * @param sender   the rank of the node at the sending end of a connection
     * @param receiver the rank of the node at the receiving end
     * @return the latency of the connection
     */
    long latency(int sender, int receiver);

    /**
     * @param a the rank of one node of a link
     * @param b the rank of the other node
     * @return {@code true} if the link is up, whether or not it is still waiting out a reconnect delay
     */
    boolean isLinkUp(int a, int b);

    /**
     * @param a the rank of one node of a link that is up
     * @param b the rank of the other node
     * @return the time from which things can be delivered over the link
     */
    long usableFrom(int a, int b);

    /**
     * @param a   the rank of one node of a link
     * @param b   the rank of the other node
     * @param now the current time
     * @return {@code true} if the link is in broadcast mode
     */
    boolean isBroadcastMode(int a, int b, long now);

    /**
     * @param rank the rank of a node
     * @return the latest event window the node has reported
     */
    @NonNull
    EventWindow eventWindow(int rank);

    /**
     * Gets the time the first copy of an event that is on its way to a node arrives, which is when the node has
     * received it if that time has passed.
     *
     * @param event an instance of the event
     * @param node  the rank of the node
     * @return the time, or {@link Long#MAX_VALUE} if no copy is on its way or the event is not tracked
     */
    long firstArrival(@NonNull PlatformEvent event, int node);

    /**
     * @return the production broadcast settings the network simulates
     */
    @NonNull
    BroadcastConfig broadcastConfig();

    /**
     * @return the production sync settings the network simulates
     */
    @NonNull
    SyncConfig syncConfig();
}
