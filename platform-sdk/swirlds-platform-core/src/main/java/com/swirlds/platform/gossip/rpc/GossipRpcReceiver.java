// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.rpc;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Handler for messages/RPC coming from remote endpoint during gossip exchange. Counterpart to {@link GossipRpcSender},
 * just on the receiving side.
 */
public interface GossipRpcReceiver {

    /**
     * Receive information about peer tips and event window. If they have indicated fallen behind status previously,
     * this call indicates they are ready to sync again. See {@link GossipRpcSender#sendSyncData(SyncData)}
     *
     * @param syncMessage container class for tips and event window
     */
    void receiveSyncData(@NonNull SyncData syncMessage);

    /**
     * Receive information which of the remote tips they have on their side. See {@link GossipRpcSender#sendTips(List)}
     *
     * @param tips list of true in case we know of tip, false otherwise
     */
    void receiveTips(@NonNull List<Boolean> tips);

    /**
     * Receive events, preserving orders in which they are presented. See {@link GossipRpcSender#sendEvents(List)}
     *
     * @param gossipEvents events to send
     */
    void receiveEvents(@NonNull List<GossipEvent> gossipEvents);

    /**
     * Receive marker indicating that all events were already provided to the channel from the other side. See
     * {@link GossipRpcSender#sendEndOfEvents()}
     */
    void receiveEventsFinished();
}
