// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.rpc;

import com.hedera.hapi.platform.message.GossipEventWindow;
import com.hedera.hapi.platform.message.GossipSyncData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Wrapper class for representing combination of event window and tip hashes used in the rpc sync protocol
 * @param eventWindow event window we see
 * @param tipHashes tips of our hashgraph
 */
public record SyncData(EventWindow eventWindow, List<Hash> tipHashes) {

    /**
     * Convert protobuf communication version of class to internal one
     *
     * @param syncData    protobuf object
     * @return internal class
     */
    public static SyncData fromProtobuf(@NonNull final GossipSyncData syncData) {
        final var gossipWindow = syncData.window();

        final var eventWindow = new EventWindow(
                gossipWindow.latestConsensusRound(),
                gossipWindow.latestConsensusRound() + 1,
                gossipWindow.ancientThreshold(),
                gossipWindow.expiredThreshold());
        final var tips =
                syncData.tips().stream().map(it -> new Hash(it.toByteArray())).collect(Collectors.toList());
        return new SyncData(eventWindow, tips);
    }

    /**
     * Convert internal version of class to protobuf communication one
     * @return protobuf version of internal data
     */
    public GossipSyncData toProtobuf() {
        final GossipSyncData.Builder builder = GossipSyncData.newBuilder();
        builder.window(GossipEventWindow.newBuilder()
                .ancientThreshold(eventWindow.ancientThreshold())
                .expiredThreshold(eventWindow.expiredThreshold())
                .latestConsensusRound(eventWindow.latestConsensusRound())
                .build());
        builder.tips(tipHashes.stream().map(Hash::getBytes).collect(Collectors.toList()));
        return builder.build();
    }
}
