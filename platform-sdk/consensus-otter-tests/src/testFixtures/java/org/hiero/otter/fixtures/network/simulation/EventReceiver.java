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
     * <p>Returning {@code false} refuses the event and every other event from the same sender (see
     * {@link PlatformEvent#getSenderId()}) for the rest of the current tick. The refused events stay queued on that
     * sender's connection and are offered again on a later tick; events from other senders are still delivered.
     *
     * <p>Events are refused in statuses in which production does not gossip. This is currently not implemented in
     * Turtle (<a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/27528">#27528</a>).
     *
     * @param event the event to receive
     * @return true if the event was successfully received, false to refuse it and its sender's events for this tick
     */
    boolean receiveEvent(@NonNull PlatformEvent event);

    /**
     * Receive a peer's latest event window from the simulated network.
     *
     * <p>Event windows are refused in statuses in which production does not gossip. This is currently not implemented
     * in Turtle (<a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/27528">#27528</a>).
     *
     * <p>Only the newest window a peer has sent is delivered; the ones it supersedes are discarded by the network. A
     * window is therefore the peer's current position, not a step in a sequence. The same window may be delivered again
     * when a link comes up or a node restarts, since the network then sends each peer's latest window afresh.
     *
     * @param sender      the node whose event window this is
     * @param eventWindow the event window to receive
     * @return true if the event window was successfully received, false otherwise
     */
    boolean receiveEventWindow(@NonNull NodeId sender, @NonNull EventWindow eventWindow);
}
