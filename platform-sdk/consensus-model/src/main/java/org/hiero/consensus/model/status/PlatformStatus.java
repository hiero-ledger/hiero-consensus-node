// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.status;

/**
 * The status of the Platform
 */
public enum PlatformStatus implements UniqueId {
    /**
     * The platform is starting up.
     */
    STARTING_UP(1),
    /**
     * The platform is gossiping, creating events, and accepting app transactions.
     */
    ACTIVE(2),
    /**
     * The Platform does not have the latest state, and needs to reconnect. The platform is not gossiping.
     */
    BEHIND(4),
    /**
     * A freeze timestamp has been crossed, and the platform is in the process of freezing. The platform is gossiping.
     * It is permitted to create events, but will not produce any additional events after creating one with its self
     * signature for the freeze state.
     */
    FREEZING(5),
    /**
     * The platform has completed the freeze. It is still gossipping, so that signatures on the freeze state can be
     * distributed to laggards.
     */
    FREEZE_COMPLETE(6),
    /**
     * The platform is replaying events from the preconsensus event stream.
     */
    REPLAYING_EVENTS(7),
    /**
     * The platform has just started, and is observing the network. The platform is gossiping, but will not create
     * events.
     */
    OBSERVING(8),
    /**
     * The platform has started up or has finished reconnecting, and is now ready to rejoin the network. The platform is
     * gossiping and creating events, but not yet accepting app transactions.
     */
    CHECKING(9),
    /**
     * The platform has just finished reconnecting. The platform is gossiping, but is waiting to write a state to disk
     * before creating events or accepting app transactions.
     */
    RECONNECT_COMPLETE(10),
    /**
     * The platform has encountered a failure, and is unable to continue. The platform is idle.
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
    PlatformStatus(final int id) {
        this.id = id;
    }

    @Override
    public int getId() {
        return id;
    }
}
