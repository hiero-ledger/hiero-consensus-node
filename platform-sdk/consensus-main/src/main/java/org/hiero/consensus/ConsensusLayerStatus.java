// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import org.hiero.base.UniqueId;

/**
 * The status of the consensus layer. Various statuses permit different actions, such as gossip and event creation.
 */
public enum ConsensusLayerStatus implements UniqueId {
    /**
     * The consensus layer is starting up.
     */
    STARTING_UP(1),
    /**
     * The consensus layer is actively trying to produce the next consensus round. Gossip and event creation are
     * permitted.
     */
    ACTIVE(2),
    /**
     * The consensus layer is far enough behind its peers to require a reconnect.
     */
    BEHIND(4),
    /**
     * A freeze timestamp has been crossed, and the consensus layer is in the process of freezing.
     */
    FREEZING(5),
    /**
     * The consensus node has completed the freeze.
     */
    FREEZE_COMPLETE(6),
    /**
     * The consensus layer is replaying events from the pre-consensus event stream. Gossip is not permitted.
     */
    REPLAYING_EVENTS(7),
    /**
     * The consensus layer has just started, and is observing the network. The consensus layer may gossip, but not
     * create events.
     */
    OBSERVING(8),
    /**
     * A self event has not reached consensus in a configurable amount of wall clock time. The node is not healthy, and
     * transactions submitted to this node are not reaching consensus. This status is only possible when a round has
     * been requested but not yet provided to the execution layer. If no round was requested, the node will transition
     * to {@link #IDLE} instead.
     * <p>
     * Events created while in this status do not contain any transactions.
     */
    CHECKING(9),
    /**
     * The consensus layer has no work to do. All requested rounds have been provided to the execution layer and the
     * consensus layer is waiting for the next request.
     */
    IDLE(10),
    /**
     * The consensus layer has encountered a failure, and is unable to continue. The consensus layer is idle.
     */
    CATASTROPHIC_FAILURE(11);

    /**
     * Unique ID of the enum value
     */
    private final int id;

    /**
     * Constructs an enum instance
     *
     * @param id unique ID of the instance
     */
    ConsensusLayerStatus(final int id) {
        this.id = id;
    }

    @Override
    public int getId() {
        return id;
    }
}
