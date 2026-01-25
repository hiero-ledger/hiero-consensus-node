// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.crypto.PbjStreamHasher;

public class ProtobufConverter {
    private ProtobufConverter() {}

    /**
     * Converts a consensus model NodeId to a PBJ NodeId.
     *
     * @param sourceNodeId the consensus model NodeId to convert
     * @return the converted PBJ NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.NodeId toPbjNodeId(
            @NonNull final org.hiero.consensus.model.node.NodeId sourceNodeId) {
        return com.hedera.hapi.platform.state.NodeId.newBuilder()
                .id(sourceNodeId.id())
                .build();
    }

    /**
     * Converts an Otter PlatformEvent to the consensus model PlatformEvent.
     *
     * @param sourcePlatformEvent the PBJ GossipEvent to convert
     * @return the converted Legacy GossipEvent
     */
    @NonNull
    public static org.hiero.consensus.model.event.PlatformEvent toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent sourcePlatformEvent) {
        final org.hiero.consensus.model.event.PlatformEvent platformEvent =
                new org.hiero.consensus.model.event.PlatformEvent(sourcePlatformEvent.gossipEvent());
        new PbjStreamHasher().hashEvent(platformEvent);
        platformEvent.setConsensusData(sourcePlatformEvent.consensusData());
        return platformEvent;
    }

    /**
     * Converts a ConsensusRound to a ProtoConsensusRound.
     *
     * @param sourceRound the ConsensusRound to convert
     * @return the converted ProtoConsensusRound
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.ProtoConsensusRound fromPlatform(
            @NonNull final org.hiero.consensus.model.hashgraph.ConsensusRound sourceRound) {
        final List<org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent> events =
                sourceRound.getConsensusEvents().stream()
                        .map(ProtobufConverter::fromPlatform)
                        .toList();

        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRound.newBuilder()
                .consensusEvents(events)
                .eventWindow(fromPlatform(sourceRound.getEventWindow()))
                .numAppTransactions(sourceRound.getNumAppTransactions())
                .snapshot(sourceRound.getSnapshot())
                .consensusRoster(sourceRound.getConsensusRoster())
                .pcesRound(sourceRound.isPcesRound())
                .reachedConsTimestamp(sourceRound.getReachedConsTimestamp().getEpochSecond())
                .build();
    }

    /**
     * Converts a ProtoConsensusRound to a platform ConsensusRound.
     *
     * @param sourceRound the ProtoConsensusRound to convert
     * @return the converted platform ConsensusRound
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.ConsensusRound toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRound sourceRound) {
        final List<org.hiero.consensus.model.event.PlatformEvent> events = sourceRound.consensusEvents().stream()
                .map(ProtobufConverter::toPlatform)
                .toList();

        return new org.hiero.consensus.model.hashgraph.ConsensusRound(
                sourceRound.consensusRoster(),
                events,
                toPlatform(sourceRound.eventWindow()),
                sourceRound.snapshot(),
                sourceRound.pcesRound(),
                java.time.Instant.ofEpochSecond(sourceRound.reachedConsTimestamp()));
    }

    /**
     * Converts ProtoConsensusRounds to a List of platform ConsensusRounds.
     *
     * @param sourceRounds the ProtoConsensusRounds to convert
     * @return the converted list of platform ConsensusRounds
     */
    @NonNull
    public static List<org.hiero.consensus.model.hashgraph.ConsensusRound> toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds sourceRounds) {
        return sourceRounds.rounds().stream().map(ProtobufConverter::toPlatform).toList();
    }

    private static org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent fromPlatform(
            @NonNull final org.hiero.consensus.model.event.PlatformEvent platformEvent) {
        final com.hedera.hapi.platform.event.GossipEvent gossipEvent = platformEvent.getGossipEvent();
        final com.hedera.hapi.platform.event.EventConsensusData consensusData = platformEvent.getConsensusData();
        return org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent.newBuilder()
                .gossipEvent(gossipEvent)
                .consensusData(consensusData)
                .build();
    }
    /**
     * Converts a Legacy EventWindow to a Platform EventWindow.
     *
     * @param sourceEventWindow the Legacy EventWindow to convert
     * @return the converted Platform EventWindow
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.EventWindow toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.EventWindow sourceEventWindow) {
        return new org.hiero.consensus.model.hashgraph.EventWindow(
                sourceEventWindow.latestConsensusRound(),
                sourceEventWindow.newEventBirthRound(),
                sourceEventWindow.ancientThreshold(),
                sourceEventWindow.expiredThreshold());
    }

    /**
     * Converts a Platform EventWindow to a Legacy EventWindow.
     *
     * @param sourceEventWindow the Platform EventWindow to convert
     * @return the converted Legacy EventWindow
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.EventWindow fromPlatform(
            @NonNull final org.hiero.consensus.model.hashgraph.EventWindow sourceEventWindow) {
        return org.hiero.otter.fixtures.container.proto.EventWindow.newBuilder()
                .latestConsensusRound(sourceEventWindow.latestConsensusRound())
                .newEventBirthRound(sourceEventWindow.newEventBirthRound())
                .ancientThreshold(sourceEventWindow.ancientThreshold())
                .expiredThreshold(sourceEventWindow.expiredThreshold())
                .build();
    }

    /**
     * Converts a Legacy LogEntry to a StructuredLog.
     *
     * @param sourceLog the Legacy LogEntry to convert
     * @return the converted StructuredLog
     */
    @NonNull
    public static org.hiero.otter.fixtures.logging.StructuredLog toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.LogEntry sourceLog) {
        return new org.hiero.otter.fixtures.logging.StructuredLog(
                sourceLog.timestamp(),
                Level.toLevel(sourceLog.level()),
                sourceLog.message(),
                sourceLog.loggerName(),
                sourceLog.thread(),
                MarkerManager.getMarker(sourceLog.marker()),
                sourceLog.nodeId() < 0 ? null : org.hiero.consensus.model.node.NodeId.of(sourceLog.nodeId()));
    }

    /**
     * Converts a StructuredLog to a Legacy LogEntry.
     *
     * @param sourceLog the StructuredLog to convert
     * @return the converted Legacy LogEntry
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.LogEntry fromPlatform(
            @NonNull final org.hiero.otter.fixtures.logging.StructuredLog sourceLog) {
        return org.hiero.otter.fixtures.container.proto.LogEntry.newBuilder()
                .timestamp(sourceLog.timestamp())
                .level(sourceLog.level().toString())
                .loggerName(sourceLog.loggerName())
                .thread(sourceLog.threadName())
                .message(sourceLog.message())
                .marker(sourceLog.marker() != null ? sourceLog.marker().toString() : "")
                .nodeId(sourceLog.nodeId() != null ? sourceLog.nodeId().id() : -1L)
                .build();
    }
}
