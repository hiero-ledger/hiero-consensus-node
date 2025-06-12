// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.rpc;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Counterpart to {@link GossipRpcSender}, just on the receiving side
 */
public interface GossipRpcReceiver {

    /**
     * {@link GossipRpcSender#sendSyncData(SyncData)}
     */
    void receiveSyncData(@NonNull SyncData syncMessage);

    /**
     * {@link GossipRpcSender#sendTips(List)}
     */
    void receiveTips(@NonNull List<Boolean> tips);

    /**
     * {@link GossipRpcSender#sendEvents(List)}
     */
    void receiveEvents(@NonNull List<GossipEvent> gossipEvents);

    /**
     * {@link GossipRpcSender#sendEndOfEvents()}
     */
    void receiveEventsFinished();

    /**
     * {@link GossipRpcSender#sendBroadcastEvent(GossipEvent)}
     */
    void receiveBroadcastEvent(GossipEvent gossipEvent);
}
