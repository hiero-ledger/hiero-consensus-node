// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.rpc;

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
     * Send single event coming from broadcast algorithm. Similar to sendEvents(singleton(gossipEvent)), but allows
     * for different handling on remote side, as it is sometimes needed to know that given event is not coming
     * as part of sync process
     * @param gossipEvent event to sent
     */
    void sendBroadcastEvent(@NonNull GossipEvent gossipEvent);

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
