// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

record CommunicationEvent(long selfId, int selfCounter, long otherId, int otherCounter, long timestamp) {

    boolean isFrom(int nodeA, int nodeB) {
        return (selfId == nodeA && otherId == nodeB);
    }

    boolean isBetween(int nodeA, int nodeB) {
        return isFrom(nodeA, nodeB) || isFrom(nodeB, nodeA);
    }
}
