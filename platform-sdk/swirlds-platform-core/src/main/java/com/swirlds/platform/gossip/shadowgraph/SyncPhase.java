// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.platform.gossip.sync.protocol.SyncStatusChecker;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Describes what phases rpc sync can be in
 */
public enum SyncPhase {
    /**
     * It is not doing anything, waiting for sync timeout period to finish
     */
    IDLE,
    /**
     * At least one side has sent sync window
     */
    EXCHANGING_WINDOWS,
    /**
     * At least one side has sent tip booleans
     */
    EXCHANGING_TIPS,
    /**
     * Both sides are still sending events after exchanging windows and tips
     */
    EXCHANGING_EVENTS,
    /**
     * Our side has finished sending events, but we are still receiving them from remote party
     */
    RECEIVING_EVENTS,
    /**
     * Remote side has finished sending events, but we are still sending them
     */
    SENDING_EVENTS,
    /**
     * Other side has fallen behind, so don't do anything
     */
    OTHER_FALLEN_BEHIND,
    /**
     * We have fallen behind, reconnect protocol will take over soon
     */
    SELF_FALLEN_BEHIND,
    /**
     * We are not allowed to run, most probably caused by reconnect protocol taking over control
     */
    GOSSIP_HALTED,
    /**
     * We are in the system state which does not allow synchronization - see
     * {@link SyncStatusChecker#doesStatusPermitSync(PlatformStatus)}
     */
    PLATFORM_STATUS_PREVENTING_SYNC,
    /**
     * There are no permits available for us to consume
     */
    NO_PERMIT,
    /**
     * Rpc exchange has finished for now, other protocols take care about the system
     */
    OUTSIDE_OF_RPC
}
