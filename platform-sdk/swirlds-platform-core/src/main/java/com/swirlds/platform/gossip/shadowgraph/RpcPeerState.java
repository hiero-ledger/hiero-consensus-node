// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.platform.gossip.rpc.SyncData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class RpcPeerState {
    /**
     * Events they for sure have based on the tips they have sent, to be merged with known tips they respond for out
     * tipset
     */
    final Set<ShadowEvent> eventsTheyHave = new HashSet<>();

    /**
     * Event window we have reserved during start of sync process with remote node
     */
    ReservedEventWindow shadowWindow;

    /**
     * Sync data we have sent to remote party
     */
    SyncData mySyncData;

    /**
     * Sync data we have received from remote party
     */
    SyncData remoteSyncData;

    /**
     * List of tips we have sent to remote party
     */
    List<ShadowEvent> myTips;

    /**
     * Has remote node reported it has fallen behind
     */
    boolean remoteFallenBehind;

    /**
     * What was the time we have finished sync last
     */
    Instant lastSyncTime = Instant.MIN;

    /**
     * Remote party is still sending us events, so we shouldn't bother it with another sync request
     */
    boolean remoteStillSendingEvents = false;

    public void clear(@NonNull final Instant lastSyncTime) {
        if (shadowWindow != null) {
            shadowWindow.close();
            shadowWindow = null;
        }
        remoteSyncData = null;
        mySyncData = null;
        myTips = null;
        eventsTheyHave.clear();
        this.lastSyncTime = lastSyncTime;
    }
}
