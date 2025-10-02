// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.rpc;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Stub interface for sending sync and broadcast messages to remote peer
 */
public interface GossipRpcSender {

    /**
     * Send information about our tips and event window. If we indicated fallen behind status previously, this call
     * indicates we are ready to sync again
     * @param syncMessage container class for tips and event window
     */
    void sendSyncData(@NonNull SyncData syncMessage);

    /**
     * Send information which of the remote tips we have on our side
     * @param tips list of true in case we know of tip, false otherwise
     */
    void sendTips(@NonNull List<Boolean> tips);

    /**
     * Send all provided events, preserving orders in which they are presented
     * @param gossipEvents events to send
     */
    void sendEvents(@NonNull List<GossipEvent> gossipEvents);

    /**
     * Send marker indicating that all events were already provided to the channel
     *
     */
    void sendEndOfEvents();

    /**
     * Mark the end of conversation. Used because we are still in the compatibility mode with old protocols, so we need
     * to stop hogging network connection sometimes and let reconnect protocol to run its course.
     */
    void breakConversation();
}
