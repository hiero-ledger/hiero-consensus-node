// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The simulated network as seen by the gossip of a node: what a node hands to the network. The counterpart of
 * {@link EventReceiver}, which is what the network hands to a node.
 *
 * <p>A node is given its gossip network when it is created, before the network starts. It is only used while the
 * network is running.
 */
public interface GossipNetwork {

    /**
     * Submit an event to be gossiped around the network. Safe to be called by multiple nodes in parallel.
     *
     * @param event the event to gossip, with its sender set to the node submitting it
     */
    void submitEvent(@NonNull PlatformEvent event);

    /**
     * Report the latest {@link EventWindow} of a node. Safe to be called by multiple nodes in parallel.
     *
     * @param nodeId      the id of the node the event window belongs to
     * @param eventWindow the node's latest event window
     */
    void updateEventWindow(@NonNull NodeId nodeId, @NonNull EventWindow eventWindow);

    /**
     * Report that a node has restarted, so that it is sent everything it needs from the beginning again.
     *
     * @param nodeId the id of the node that has restarted
     */
    void resetCursor(@NonNull NodeId nodeId);
}
