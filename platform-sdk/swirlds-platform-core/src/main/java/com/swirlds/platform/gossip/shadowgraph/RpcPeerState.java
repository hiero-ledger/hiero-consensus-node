// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.platform.gossip.rpc.SyncData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Internal state class for {@link RpcPeerHandler}, extracted to separate object for having clarity what is a
 * sync-specific mutable state.
 * Shouldn't be really used by any other parties, it is very specific to current RpcPeerHandler implementation.
 */
class RpcPeerState {
    /**
     * Events they for sure have based on the tips they have sent, to be merged with known tips they respond for our
     * tipset
     */
    final Set<ShadowEvent> eventsTheyHave = new HashSet<>();

    /**
     * Event window we have reserved during start of sync process with the peer
     */
    ReservedEventWindow shadowWindow;

    /**
     * Sync data we have sent to the peer
     */
    SyncData mySyncData;

    /**
     * Sync data we have received from the peer
     */
    SyncData remoteSyncData;

    /**
     * List of tips we have sent to the peer
     */
    List<ShadowEvent> myTips;

    /**
     * Has the peer node reported it is falling behind
     */
    boolean peerIsBehind;

    /**
     * The time at which the last sync finished
     */
    Instant lastSyncFinishedTime = Instant.MIN;

    /**
     * The peer is still sending us events, so we shouldn't bother it with another sync request
     */
    boolean peerStillSendingEvents = false;

    /**
     * Clear internal state in preparation for a new sync in the future. In particular, this method closes
     * {@link #shadowWindow} if one was allocated previously.
     *
     * @param lastSyncTime time when synchronization has finished, in most cases same as time when this method is
     *                     called
     */
    public void clear(@NonNull final Instant lastSyncTime) {
        if (shadowWindow != null) {
            shadowWindow.close();
            shadowWindow = null;
        }
        remoteSyncData = null;
        mySyncData = null;
        myTips = null;
        eventsTheyHave.clear();
        lastSyncFinishedTime = lastSyncTime;
    }

    /**
     * Peer told us that he is ready to sync
     * @param syncMessage synchronization data peer has passed to us
     */
    public void syncInitiated(final SyncData syncMessage) {
        // if they are sending us sync data, they are no longer behind compared to the self node
        peerIsBehind = false;
        peerStillSendingEvents = false;
        remoteSyncData = syncMessage;
    }
}
