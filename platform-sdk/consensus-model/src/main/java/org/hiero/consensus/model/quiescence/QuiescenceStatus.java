// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.quiescence;

/**
 * The quiescence status of the node.
 */
public enum QuiescenceStatus {
    /**
     * A node that is quiescing will not create new events. It will still gossip, and any events that it receives
     * through gossip might change its quiescence status.
     */
    QUIESCING,
    /**
     * This is an edge case in the quiescence mechanism. The whole network might be quiescent, and a single node might
     * receive a transaction that needs to become part of an event. A node might not have any viable parents to create a
     * new event. In this case, the node must create an event with any parents so that it will break the quiescence of
     * the network. Once this new event is gossiped, other nodes will start creating new events as well.
     */
    BREAKING_QUIESCENCE,
    /**
     * A node that is active will create new events and advance consensus time.
     */
    ACTIVE
}
