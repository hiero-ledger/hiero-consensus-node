// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

/**
 * Internal {@link RpcPeerProtocol} byte indicators for various message types
 */
class RpcMessageId {

    /**
     * Indicates {@link com.hedera.hapi.platform.message.GossipSyncData} from send/receiveSyncData
     */
    static final int SYNC_DATA = 1;

    /**
     * Indicates {@link com.hedera.hapi.platform.message.GossipKnownTips} from send/receiveTips
     */
    static final int KNOWN_TIPS = 2;

    /**
     * Indicates single {@link com.hedera.hapi.platform.event.GossipEvent} from send/receiveEvents
     */
    static final int EVENT = 3;

    /**
     * Indicates that stream of events is finished, from send/receivedEventsFinished
     */
    static final int EVENTS_FINISHED = 4;

    /**
     * Indicates {@link com.hedera.hapi.platform.message.GossipPing} ping request
     */
    static final int PING = 5;

    /**
     * Indicates {@link com.hedera.hapi.platform.message.GossipPing} ping reply
     */
    static final int PING_REPLY = 6;
}
