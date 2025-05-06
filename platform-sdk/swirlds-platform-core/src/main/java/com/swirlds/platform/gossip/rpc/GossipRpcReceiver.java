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
     * {@link GossipRpcSender#sendFallenBehind()}
     */
    void receiveFallenBehind();

    /**
     * {@link GossipRpcSender#sendTips(List)}
     */
    void receiveTips(@NonNull List<Boolean> tips);

    void receiveEvents(@NonNull List<GossipEvent> gossipEvents);

    void receiveEventsFinished();
}
