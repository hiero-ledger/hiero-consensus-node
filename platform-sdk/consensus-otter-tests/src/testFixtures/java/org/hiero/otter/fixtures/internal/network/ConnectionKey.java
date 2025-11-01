// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * Represents a key for a connection between two nodes in the topology.
 *
 * @param sender the starting node of the connection
 * @param receiver the ending node of the connection
 */
public record ConnectionKey(@NonNull NodeId sender, @NonNull NodeId receiver) {

    /**
     * Reverses the connection key by swapping the sender and receiver.
     *
     * @return a new ConnectionKey with sender and receiver swapped
     */
    @NonNull
    public ConnectionKey reversed() {
        return new ConnectionKey(receiver, sender);
    }
}
