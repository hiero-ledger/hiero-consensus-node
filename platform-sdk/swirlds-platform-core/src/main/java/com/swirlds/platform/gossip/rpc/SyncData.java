// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.rpc;

import com.hedera.hapi.platform.message.GossipAncientMode;
import com.hedera.hapi.platform.message.GossipEventWindow;
import com.hedera.hapi.platform.message.GossipSyncData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.EventWindow;

public record SyncData(EventWindow eventWindow, List<Hash> tipHashes) {

    public static SyncData fromProtobuf(@NonNull final GossipSyncData syncData) {
        final var gossipWindow = syncData.window();
        final var ancientMode =
                switch (gossipWindow.ancientMode()) {
                    case GENERATION -> AncientMode.GENERATION_THRESHOLD;
                    case BIRTH_ROUND -> AncientMode.BIRTH_ROUND_THRESHOLD;
                    default ->
                        throw new IllegalArgumentException("Unknown ancient mode: " + gossipWindow.ancientMode());
                };
        final var eventWindow = new EventWindow(
                gossipWindow.latestConsensusRound(),
                gossipWindow.ancientThreshold(),
                gossipWindow.expiredThreshold(),
                ancientMode);
        final var tips =
                syncData.tips().stream().map(it -> new Hash(it.toByteArray())).collect(Collectors.toList());
        return new SyncData(eventWindow, tips);
    }

    public GossipSyncData toProtobuf() {
        final GossipSyncData.Builder builder = GossipSyncData.newBuilder();
        builder.window(GossipEventWindow.newBuilder()
                .ancientThreshold(eventWindow.getAncientThreshold())
                .expiredThreshold(eventWindow.getExpiredThreshold())
                .latestConsensusRound(eventWindow.getLatestConsensusRound())
                .ancientMode(
                        switch (eventWindow.getAncientMode()) {
                            case GENERATION_THRESHOLD -> GossipAncientMode.GENERATION;
                            case BIRTH_ROUND_THRESHOLD -> GossipAncientMode.BIRTH_ROUND;
                            default ->
                                throw new IllegalArgumentException(
                                        "Unknown ancient mode: " + eventWindow.getAncientMode());
                        })
                .build());
        builder.tips(tipHashes.stream().map(Hash::getBytes).collect(Collectors.toList()));
        return builder.build();
    }
}
