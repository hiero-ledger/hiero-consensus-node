// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * A functional interface for receiving events from the simulated network.
 */
public interface EventReceiver {

    /**
     * Get the node ID of the receiver.
     *
     * @return the node ID of the receiver
     */
    NodeId getNodeId();

    /**
     * Receive an event from the simulated network.
     *
     * @param event the event to receive
     * @return true if the event was successfully received, false otherwise
     */
    boolean receiveEvent(@NonNull PlatformEvent event);

    /**
     * Receive a peer's latest event window from the simulated network.
     *
     * <p>Only the newest window a peer has sent is delivered; the ones it supersedes are discarded by the network. A
     * window is therefore the peer's current position, not a step in a sequence, and no window is delivered twice.
     *
     * @param sender      the node whose event window this is
     * @param eventWindow the event window to receive
     * @return true if the event window was successfully received, false otherwise
     */
    boolean receiveEventWindow(@NonNull NodeId sender, @NonNull EventWindow eventWindow);
}
