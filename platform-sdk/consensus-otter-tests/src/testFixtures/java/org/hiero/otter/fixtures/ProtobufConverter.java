// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.legacy.EventConsensusData;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRound;

public class ProtobufConverter {
    private ProtobufConverter() {}

    /**
     * Converts a consensus model NodeId to a Legacy NodeId.
     *
     * @param sourceNodeId the consensus model NodeId to convert
     * @return the converted Legacy NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.NodeId toLegacy(
            @NonNull final org.hiero.consensus.model.node.NodeId sourceNodeId) {
        return com.hedera.hapi.platform.state.legacy.NodeId.newBuilder()
                .setId(sourceNodeId.id())
                .build();
    }

    /**
     * Converts a Legacy SemanticVersion to a PBJ SemanticVersion.
     *
     * @param sourceVersion the Legacy SemanticVersion to convert
     * @return the converted PBJ SemanticVersion
     */
    @NonNull
    public static com.hedera.hapi.node.base.SemanticVersion toPbj(
            @NonNull final com.hederahashgraph.api.proto.java.SemanticVersion sourceVersion) {
        return com.hedera.hapi.node.base.SemanticVersion.newBuilder()
                .major(sourceVersion.getMajor())
                .minor(sourceVersion.getMinor())
                .pre(sourceVersion.getPre())
                .patch(sourceVersion.getPatch())
                .build(sourceVersion.getBuild())
                .build();
    }

    /**
     * Converts a PBJ SemanticVersion to a Legacy SemanticVersion.
     *
     * @param sourceVersion the PBJ SemanticVersion to convert
     * @return the converted Legacy SemanticVersion
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.SemanticVersion fromPbj(
            @NonNull final com.hedera.hapi.node.base.SemanticVersion sourceVersion) {
        return com.hederahashgraph.api.proto.java.SemanticVersion.newBuilder()
                .setMajor(sourceVersion.major())
                .setMinor(sourceVersion.minor())
                .setPre(sourceVersion.pre())
                .setPatch(sourceVersion.patch())
                .setBuild(sourceVersion.build())
                .build();
    }

    /**
     * Converts a Legacy Roster to a PBJ Roster.
     *
     * @param sourceRoster the Legacy Roster to convert
     * @return the converted PBJ Roster
     */
    @NonNull
    public static com.hedera.hapi.node.state.roster.Roster toPbj(
            @NonNull final com.hederahashgraph.api.proto.java.Roster sourceRoster) {
        return com.hedera.hapi.node.state.roster.Roster.newBuilder()
                .rosterEntries(sourceRoster.getRosterEntriesList().stream()
                        .map(ProtobufConverter::toPbj)
                        .sorted(comparingLong(com.hedera.hapi.node.state.roster.RosterEntry::nodeId))
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ Roster to a Legacy Roster.
     *
     * @param sourceRoster the PBJ Roster to convert
     * @return the converted Legacy Roster
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.Roster fromPbj(
            @NonNull final com.hedera.hapi.node.state.roster.Roster sourceRoster) {
        return com.hederahashgraph.api.proto.java.Roster.newBuilder()
                .addAllRosterEntries(sourceRoster.rosterEntries().stream()
                        .sorted(comparingLong(com.hedera.hapi.node.state.roster.RosterEntry::nodeId))
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a Legacy RosterEntry to a PBJ RosterEntry.
     *
     * @param sourceEntry the Legacy RosterEntry to convert
     * @return the converted PBJ RosterEntry
     */
    @NonNull
    public static com.hedera.hapi.node.state.roster.RosterEntry toPbj(
            @NonNull final com.hederahashgraph.api.proto.java.RosterEntry sourceEntry) {
        return com.hedera.hapi.node.state.roster.RosterEntry.newBuilder()
                .nodeId(sourceEntry.getNodeId())
                .weight(sourceEntry.getWeight())
                .gossipCaCertificate(toPbj(sourceEntry.getGossipCaCertificate()))
                .gossipEndpoint(sourceEntry.getGossipEndpointList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ RosterEntry to a Legacy RosterEntry.
     *
     * @param sourceEntry the PBJ RosterEntry to convert
     * @return the converted Legacy RosterEntry
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.RosterEntry fromPbj(
            @NonNull final com.hedera.hapi.node.state.roster.RosterEntry sourceEntry) {
        return com.hederahashgraph.api.proto.java.RosterEntry.newBuilder()
                .setNodeId(sourceEntry.nodeId())
                .setWeight(sourceEntry.weight())
                .setGossipCaCertificate(fromPbj(sourceEntry.gossipCaCertificate()))
                .addAllGossipEndpoint(sourceEntry.gossipEndpoint().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a Legacy ServiceEndpoint to a PBJ ServiceEndpoint.
     *
     * @param sourceEntry the Legacy ServiceEndpoint to convert
     * @return the converted PBJ ServiceEndpoint
     */
    @NonNull
    public static com.hedera.hapi.node.base.ServiceEndpoint toPbj(
            @NonNull final com.hederahashgraph.api.proto.java.ServiceEndpoint sourceEntry) {
        return com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                .ipAddressV4(toPbj(sourceEntry.getIpAddressV4()))
                .port(sourceEntry.getPort())
                .domainName(sourceEntry.getDomainName())
                .build();
    }

    /**
     * Converts a PBJ ServiceEndpoint to a Legacy ServiceEndpoint.
     *
     * @param sourceEntry the PBJ ServiceEndpoint to convert
     * @return the converted Legacy ServiceEndpoint
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.ServiceEndpoint fromPbj(
            @NonNull final com.hedera.hapi.node.base.ServiceEndpoint sourceEntry) {
        return com.hederahashgraph.api.proto.java.ServiceEndpoint.newBuilder()
                .setIpAddressV4(fromPbj(sourceEntry.ipAddressV4()))
                .setPort(sourceEntry.port())
                .setDomainName(sourceEntry.domainName())
                .build();
    }

    /**
     * Converts a Legacy EventDescriptor to a PBJ EventDescriptor.
     *
     * @param sourceEventDescriptor the Legacy EventDescriptor to convert
     * @return the converted PBJ EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventDescriptor toPbj(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventDescriptor sourceEventDescriptor) {
        return com.hedera.hapi.platform.event.EventDescriptor.newBuilder()
                .hash(toPbj(sourceEventDescriptor.getHash()))
                .creatorNodeId(sourceEventDescriptor.getCreatorNodeId())
                .birthRound(sourceEventDescriptor.getBirthRound())
                .build();
    }

    /**
     * Converts a PBJ EventDescriptor to a Legacy EventDescriptor.
     *
     * @param sourceEventDescriptor the PBJ EventDescriptor to convert
     * @return the converted Legacy EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventDescriptor fromPbj(
            @NonNull final com.hedera.hapi.platform.event.EventDescriptor sourceEventDescriptor) {
        return com.hedera.hapi.platform.event.legacy.EventDescriptor.newBuilder()
                .setHash(fromPbj(sourceEventDescriptor.hash()))
                .setCreatorNodeId(sourceEventDescriptor.creatorNodeId())
                .setBirthRound(sourceEventDescriptor.birthRound())
                .build();
    }

    /**
     * Converts a Legacy GossipEvent to a PBJ GossipEvent.
     *
     * @param sourceGossipEvent the Legacy GossipEvent to convert
     * @return the converted PBJ GossipEvent
     */
    @NonNull
    public static com.hedera.hapi.platform.event.GossipEvent toPbj(
            @NonNull final com.hedera.hapi.platform.event.legacy.GossipEvent sourceGossipEvent) {
        return com.hedera.hapi.platform.event.GossipEvent.newBuilder()
                .eventCore(toPbj(sourceGossipEvent.getEventCore()))
                .signature(toPbj(sourceGossipEvent.getSignature()))
                .transactions(sourceGossipEvent.getTransactionsList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
                .parents(sourceGossipEvent.getParentsList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
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
                new org.hiero.consensus.model.event.PlatformEvent(toPbj(sourcePlatformEvent.getGossipEvent()));
        new PbjStreamHasher().hashEvent(platformEvent);
        platformEvent.setConsensusData(toPbj(sourcePlatformEvent.getConsensusData()));
        return platformEvent;
    }

    /**
     * Converts a Legacy EventCore to a PBJ EventCore.
     *
     * @param sourceEventCore the Legacy EventCore to convert
     * @return the converted PBJ EventCore
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventCore toPbj(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventCore sourceEventCore) {
        return com.hedera.hapi.platform.event.EventCore.newBuilder()
                .creatorNodeId(sourceEventCore.getCreatorNodeId())
                .birthRound(sourceEventCore.getBirthRound())
                .timeCreated(toPbj(sourceEventCore.getTimeCreated()))
                .build();
    }

    /**
     * Converts a PBJ EventCore to a Legacy EventCore.
     *
     * @param sourceEventCore the PBJ EventCore to convert
     * @return the converted Legacy EventCore
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventCore fromPbj(
            @NonNull final com.hedera.hapi.platform.event.EventCore sourceEventCore) {
        return com.hedera.hapi.platform.event.legacy.EventCore.newBuilder()
                .setCreatorNodeId(sourceEventCore.creatorNodeId())
                .setBirthRound(sourceEventCore.birthRound())
                .setTimeCreated(sourceEventCore.timeCreated() != null ? fromPbj(sourceEventCore.timeCreated()) : null)
                .build();
    }

    /**
     * Converts a Legacy Timestamp to a PBJ Timestamp.
     *
     * @param sourceTimestamp the Legacy Timestamp to convert
     * @return the converted PBJ Timestamp
     */
    @NonNull
    public static com.hedera.hapi.node.base.Timestamp toPbj(
            final com.hederahashgraph.api.proto.java.Timestamp sourceTimestamp) {
        return com.hedera.hapi.node.base.Timestamp.newBuilder()
                .seconds(sourceTimestamp.getSeconds())
                .nanos(sourceTimestamp.getNanos())
                .build();
    }

    /**
     * Converts a PBJ Timestamp to a Legacy Timestamp.
     *
     * @param sourceTimestamp the PBJ Timestamp to convert
     * @return the converted Legacy Timestamp
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.Timestamp fromPbj(
            final com.hedera.hapi.node.base.Timestamp sourceTimestamp) {
        return com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                .setSeconds(sourceTimestamp.seconds())
                .setNanos(sourceTimestamp.nanos())
                .build();
    }

    /**
     * Converts a Legacy ByteString to a PBJ Bytes.
     *
     * @param sourceBytes the Legacy ByteString to convert
     * @return the converted PBJ Bytes
     */
    @NonNull
    public static com.hedera.pbj.runtime.io.buffer.Bytes toPbj(final com.google.protobuf.ByteString sourceBytes) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(sourceBytes.toByteArray());
    }

    /**
     * Converts a PBJ Bytes to a Legacy ByteString.
     *
     * @param sourceBytes the PBJ Bytes to convert
     * @return the converted Legacy ByteString
     */
    @NonNull
    public static com.google.protobuf.ByteString fromPbj(final com.hedera.pbj.runtime.io.buffer.Bytes sourceBytes) {
        return com.google.protobuf.ByteString.copyFrom(sourceBytes.toByteArray());
    }

    /**
     * Converts a Legacy EventConsensusData to a PBJ EventConsensusData.
     *
     * @param sourceEventConsensusData the Legacy EventConsensusData to convert
     * @return the converted PBJ EventConsensusData
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventConsensusData toPbj(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventConsensusData sourceEventConsensusData) {
        return com.hedera.hapi.platform.event.EventConsensusData.newBuilder()
                .consensusTimestamp(toPbj(sourceEventConsensusData.getConsensusTimestamp()))
                .consensusOrder(sourceEventConsensusData.getConsensusOrder())
                .build();
    }

    /**
     * Converts a PBJ EventConsensusData to a Legacy EventConsensusData.
     *
     * @param sourceEventConsensusData the PBJ EventConsensusData to convert
     * @return the converted Legacy EventConsensusData
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventConsensusData fromPbj(
            @NonNull final com.hedera.hapi.platform.event.EventConsensusData sourceEventConsensusData) {
        return com.hedera.hapi.platform.event.legacy.EventConsensusData.newBuilder()
                .setConsensusTimestamp(
                        sourceEventConsensusData.consensusTimestamp() != null
                                ? fromPbj(sourceEventConsensusData.consensusTimestamp())
                                : null)
                .setConsensusOrder(sourceEventConsensusData.consensusOrder())
                .build();
    }

    /**
     * Converts a Legacy ConsensusSnapshot to a PBJ ConsensusSnapshot.
     *
     * @param sourceConsensusSnapshot the Legacy ConsensusSnapshot to convert
     * @return the converted PBJ ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.ConsensusSnapshot toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.ConsensusSnapshot sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.ConsensusSnapshot.newBuilder()
                .round(sourceConsensusSnapshot.getRound())
                .minimumJudgeInfoList(sourceConsensusSnapshot.getMinimumJudgeInfoListList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
                .nextConsensusNumber(sourceConsensusSnapshot.getNextConsensusNumber())
                .consensusTimestamp(toPbj(sourceConsensusSnapshot.getConsensusTimestamp()))
                .judgeIds(sourceConsensusSnapshot.getJudgeIdsList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ ConsensusSnapshot to a Legacy ConsensusSnapshot.
     *
     * @param sourceConsensusSnapshot the PBJ ConsensusSnapshot to convert
     * @return the converted Legacy ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.ConsensusSnapshot fromPbj(
            @NonNull final com.hedera.hapi.platform.state.ConsensusSnapshot sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.ConsensusSnapshot.newBuilder()
                .setRound(sourceConsensusSnapshot.round())
                .addAllMinimumJudgeInfoList(sourceConsensusSnapshot.minimumJudgeInfoList().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .setNextConsensusNumber(sourceConsensusSnapshot.nextConsensusNumber())
                .setConsensusTimestamp(
                        sourceConsensusSnapshot.consensusTimestamp() != null
                                ? fromPbj(sourceConsensusSnapshot.consensusTimestamp())
                                : null)
                .addAllJudgeIds(sourceConsensusSnapshot.judgeIds().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a Legacy MinimumJudgeInfo to a PBJ MinimumJudgeInfo.
     *
     * @param sourceConsensusSnapshot the Legacy MinimumJudgeInfo to convert
     * @return the converted PBJ MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.MinimumJudgeInfo toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.MinimumJudgeInfo.newBuilder()
                .round(sourceConsensusSnapshot.getRound())
                .minimumJudgeBirthRound(sourceConsensusSnapshot.getMinimumJudgeBirthRound())
                .build();
    }

    /**
     * Converts a PBJ MinimumJudgeInfo to a Legacy MinimumJudgeInfo.
     *
     * @param sourceConsensusSnapshot the PBJ MinimumJudgeInfo to convert
     * @return the converted Legacy MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo fromPbj(
            @NonNull final com.hedera.hapi.platform.state.MinimumJudgeInfo sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo.newBuilder()
                .setRound(sourceConsensusSnapshot.round())
                .setMinimumJudgeBirthRound(sourceConsensusSnapshot.minimumJudgeBirthRound())
                .build();
    }

    /**
     * Converts a Legacy JudgeId to a PBJ JudgeId.
     *
     * @param sourceJudgeId the Legacy JudgeId to convert
     * @return the converted PBJ JudgeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.JudgeId toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.JudgeId sourceJudgeId) {
        return com.hedera.hapi.platform.state.JudgeId.newBuilder()
                .creatorId(sourceJudgeId.getCreatorId())
                .judgeHash(toPbj(sourceJudgeId.getJudgeHash()))
                .build();
    }

    /**
     * Converts a PBJ JudgeId to a Legacy JudgeId.
     *
     * @param sourceJudgeId the PBJ JudgeId to convert
     * @return the converted Legacy JudgeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.JudgeId fromPbj(
            @NonNull final com.hedera.hapi.platform.state.JudgeId sourceJudgeId) {
        return com.hedera.hapi.platform.state.legacy.JudgeId.newBuilder()
                .setCreatorId(sourceJudgeId.creatorId())
                .setJudgeHash(fromPbj(sourceJudgeId.judgeHash()))
                .build();
    }

    /**
     * Converts a ProtoConsensusRounds to List<ConsensusRound>
     *
     * @param sourceRounds the ProtoConsensusRounds to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static List<org.hiero.consensus.model.hashgraph.ConsensusRound> toPbj(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds sourceRounds) {
        return sourceRounds.getRoundsList().stream()
                .map(ProtobufConverter::toPlatform)
                .collect(Collectors.toList());
    }

    /**
     * Converts a List<ConsensusRound> to ProtoConsensusRounds
     *
     * @param sourceRounds the ConsensusRounds to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds fromPlatform(
            @NonNull final List<org.hiero.consensus.model.hashgraph.ConsensusRound> sourceRounds) {
        final List<ProtoConsensusRound> legacyRounds =
                sourceRounds.stream().map(ProtobufConverter::fromPlatform).toList();
        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds.newBuilder()
                .addAllRounds(legacyRounds)
                .build();
    }

    /**
     * Converts a ProtoConsensusRound to ConsensusRound.
     *
     * @param sourceRound the ProtoConsensusRound to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.ConsensusRound toPlatform(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRound sourceRound) {
        final com.hedera.hapi.node.state.roster.Roster consensusRoster = toPbj(sourceRound.getConsensusRoster());
        final List<org.hiero.consensus.model.event.PlatformEvent> consensusEvents =
                sourceRound.getConsensusEventsList().stream()
                        .map(ProtobufConverter::toPlatform)
                        .toList();
        final org.hiero.consensus.model.hashgraph.EventWindow eventWindow = toPlatform(sourceRound.getEventWindow());
        final com.hedera.hapi.platform.state.ConsensusSnapshot snapshot = toPbj(sourceRound.getSnapshot());
        final Instant reachedConsTimestamp = Instant.ofEpochSecond(sourceRound.getReachedConsTimestamp());

        return new org.hiero.consensus.model.hashgraph.ConsensusRound(
                consensusRoster,
                consensusEvents,
                eventWindow,
                snapshot,
                sourceRound.getPcesRound(),
                reachedConsTimestamp);
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
                .addAllConsensusEvents(events)
                .setEventWindow(fromPlatform(sourceRound.getEventWindow()))
                .setNumAppTransactions(sourceRound.getNumAppTransactions())
                .setSnapshot(fromPbj(sourceRound.getSnapshot()))
                .setConsensusRoster(fromPbj(sourceRound.getConsensusRoster()))
                .setPcesRound(sourceRound.isPcesRound())
                .setReachedConsTimestamp(sourceRound.getReachedConsTimestamp().getEpochSecond())
                .build();
    }

    private static org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent fromPlatform(
            @NonNull final org.hiero.consensus.model.event.PlatformEvent platformEvent) {
        return org.hiero.otter.fixtures.container.proto.ProtoPlatformEvent.newBuilder()
                .setGossipEvent(fromPbj(platformEvent.getGossipEvent()))
                .setConsensusData(
                        platformEvent.getConsensusData() == null ? null : toPbj(platformEvent.getConsensusData()))
                .build();
    }

    private static com.hedera.hapi.platform.event.legacy.GossipEvent fromPbj(
            @NonNull final com.hedera.hapi.platform.event.GossipEvent gossipEvent) {
        return com.hedera.hapi.platform.event.legacy.GossipEvent.newBuilder()
                .setEventCore(fromPbj(requireNonNull(gossipEvent.eventCore())))
                .addAllParents(fromPbj(gossipEvent.parents()))
                .build();
    }

    private static Iterable<com.hedera.hapi.platform.event.legacy.EventDescriptor> fromPbj(
            final List<com.hedera.hapi.platform.event.EventDescriptor> parents) {
        return parents.stream().map(ProtobufConverter::fromPbj).toList();
    }

    private static EventConsensusData toPbj(final com.hedera.hapi.platform.event.EventConsensusData consensusData) {
        return EventConsensusData.newBuilder()
                .setConsensusTimestamp(toPbj(requireNonNull(consensusData.consensusTimestamp())))
                .setConsensusOrder(consensusData.consensusOrder())
                .build();
    }

    private static Timestamp toPbj(final com.hedera.hapi.node.base.Timestamp timestamp) {
        return com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                .setSeconds(timestamp.seconds())
                .setNanos(timestamp.nanos())
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
                sourceEventWindow.getLatestConsensusRound(),
                sourceEventWindow.getNewEventBirthRound(),
                sourceEventWindow.getAncientThreshold(),
                sourceEventWindow.getExpiredThreshold());
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
                .setLatestConsensusRound(sourceEventWindow.latestConsensusRound())
                .setNewEventBirthRound(sourceEventWindow.newEventBirthRound())
                .setAncientThreshold(sourceEventWindow.ancientThreshold())
                .setExpiredThreshold(sourceEventWindow.expiredThreshold())
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
                sourceLog.getTimestamp(),
                Level.toLevel(sourceLog.getLevel()),
                sourceLog.getMessage(),
                sourceLog.getLoggerName(),
                sourceLog.getThread(),
                MarkerManager.getMarker(sourceLog.getMarker()),
                sourceLog.getNodeId() < 0 ? null : org.hiero.consensus.model.node.NodeId.of(sourceLog.getNodeId()));
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
                .setTimestamp(sourceLog.timestamp())
                .setLevel(sourceLog.level().toString())
                .setLoggerName(sourceLog.loggerName())
                .setThread(sourceLog.threadName())
                .setMessage(sourceLog.message())
                .setMarker(sourceLog.marker() != null ? sourceLog.marker().toString() : "")
                .setNodeId(sourceLog.nodeId() != null ? sourceLog.nodeId().id() : -1L)
                .build();
    }
}
