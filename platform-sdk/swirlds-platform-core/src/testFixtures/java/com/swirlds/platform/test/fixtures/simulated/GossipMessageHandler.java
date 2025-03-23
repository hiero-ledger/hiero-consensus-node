// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.node.NodeId;

/**
 * An interface for test classes that handle gossiped messages
 */
public interface GossipMessageHandler {

    /**
     * Handle a message received from a peer over the network. This is the entry point to the gossip code.
     *
     * @param msg      the message received
     * @param fromPeer the peer who sent the message
     */
    void handleMessageFromWire(@NonNull final SelfSerializable msg, @NonNull final NodeId fromPeer);

    /**
     * Get the node id of this handler
     *
     * @return the node id
     */
    @NonNull
    NodeId getNodeId();
}
