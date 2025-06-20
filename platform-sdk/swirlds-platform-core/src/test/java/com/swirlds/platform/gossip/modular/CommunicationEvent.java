// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

/**
 * Debug class used by TestProtocol for purposes of unit testing the network communication
 *
 * @param selfId node id/index which received the message
 * @param selfCounter counter of received messages from given peer
 * @param otherId node id/index which has sent the message, as reported on the wire
 * @param otherCounter counter of sent messages from other peer, as reported on the wire
 * @param timestamp time at which message was received
 */
record CommunicationEvent(long selfId, int selfCounter, long otherId, int otherCounter, long timestamp) {

    boolean isFrom(int nodeA, int nodeB) {
        return (selfId == nodeA && otherId == nodeB);
    }

    boolean isBetween(int nodeA, int nodeB) {
        return isFrom(nodeA, nodeB) || isFrom(nodeB, nodeA);
    }
}
