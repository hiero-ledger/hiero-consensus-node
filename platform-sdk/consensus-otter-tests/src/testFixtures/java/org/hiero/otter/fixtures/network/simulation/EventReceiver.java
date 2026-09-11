// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
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
}
