// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.container.proto.LogEntry;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRound;
import org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * Converts between the platform's model classes and the messages that a container node exchanges with the test
 * framework.
 */
public class ProtobufConverter {
    private ProtobufConverter() {}

    /**
     * Converts a ProtoConsensusRound to ConsensusRound.
     *
     * @param sourceRound the ProtoConsensusRound to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static ConsensusRound toPlatform(@NonNull final ProtoConsensusRound sourceRound) {
        final List<PlatformEvent> consensusEvents = sourceRound.consensusEvents().stream()
                .map(ProtobufConverter::toPlatform)
                .toList();
        final Instant reachedConsTimestamp = Instant.ofEpochSecond(sourceRound.reachedConsTimestamp());

        return new ConsensusRound(
                RosterWrapper.of(sourceRound.consensusRosterOrThrow()),
                consensusEvents,
                toPlatform(sourceRound.eventWindowOrThrow()),
                sourceRound.snapshotOrThrow(),
                sourceRound.pcesRound(),
                reachedConsTimestamp);
    }

    /**
     * Converts a ConsensusRound to a ProtoConsensusRound.
     *
     * @param sourceRound the ConsensusRound to convert
     * @return the converted ProtoConsensusRound
     */
    @NonNull
    public static ProtoConsensusRound fromPlatform(@NonNull final ConsensusRound sourceRound) {
        final List<ProtoPlatformEvent> events = sourceRound.getConsensusEvents().stream()
                .map(ProtobufConverter::fromPlatform)
                .toList();

        return ProtoConsensusRound.newBuilder()
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
     * Converts a ProtoPlatformEvent to a PlatformEvent.
     *
     * @param sourcePlatformEvent the ProtoPlatformEvent to convert
     * @return the converted PlatformEvent
     */
    @NonNull
    public static PlatformEvent toPlatform(@NonNull final ProtoPlatformEvent sourcePlatformEvent) {
        final PlatformEvent platformEvent =
                new PlatformEvent(sourcePlatformEvent.gossipEventOrThrow(), EventOrigin.GOSSIP);
        new PbjStreamHasher().hashEvent(platformEvent);
        platformEvent.setConsensusData(sourcePlatformEvent.consensusDataOrThrow());
        return platformEvent;
    }

    /**
     * Converts a PlatformEvent to a ProtoPlatformEvent. Of the gossip event, only the creator, the birth round, the
     * creation time, and the parents are transferred.
     *
     * @param platformEvent the PlatformEvent to convert
     * @return the converted ProtoPlatformEvent
     */
    @NonNull
    private static ProtoPlatformEvent fromPlatform(@NonNull final PlatformEvent platformEvent) {
        final GossipEvent gossipEvent = platformEvent.getGossipEvent();
        final EventCore eventCore = requireNonNull(gossipEvent.eventCore());
        final GossipEvent transferredEvent = GossipEvent.newBuilder()
                .eventCore(EventCore.newBuilder()
                        .creatorNodeId(eventCore.creatorNodeId())
                        .birthRound(eventCore.birthRound())
                        .timeCreated(eventCore.timeCreated())
                        .build())
                .parents(gossipEvent.parents())
                .build();

        return ProtoPlatformEvent.newBuilder()
                .gossipEvent(transferredEvent)
                .consensusData(platformEvent.getConsensusData())
                .build();
    }

    /**
     * Converts an EventWindow message to a Platform EventWindow.
     *
     * @param sourceEventWindow the EventWindow message to convert
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
     * Converts a Platform EventWindow to an EventWindow message.
     *
     * @param sourceEventWindow the Platform EventWindow to convert
     * @return the converted EventWindow message
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
     * Converts a LogEntry to a StructuredLog.
     *
     * @param sourceLog the LogEntry to convert
     * @return the converted StructuredLog
     */
    @NonNull
    public static StructuredLog toPlatform(@NonNull final LogEntry sourceLog) {
        return new StructuredLog(
                sourceLog.timestamp(),
                Level.toLevel(sourceLog.level()),
                sourceLog.message(),
                sourceLog.loggerName(),
                sourceLog.thread(),
                MarkerManager.getMarker(sourceLog.marker()),
                sourceLog.nodeId() < 0 ? null : NodeId.of(sourceLog.nodeId()));
    }

    /**
     * Converts a StructuredLog to a LogEntry.
     *
     * @param sourceLog the StructuredLog to convert
     * @return the converted LogEntry
     */
    @NonNull
    public static LogEntry fromPlatform(@NonNull final StructuredLog sourceLog) {
        return LogEntry.newBuilder()
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
