// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRound;

public class ProtobufConverter {
    private ProtobufConverter() {}

    /**
     * Converts a Google NodeId to a PBJ NodeId.
     *
     * @param sourceNodeId the Google NodeId to convert
     * @return the converted PBJ NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.NodeId toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.NodeId sourceNodeId) {
        return com.hedera.hapi.platform.state.NodeId.newBuilder()
                .id(sourceNodeId.getId())
                .build();
    }

    /**
     * Converts a PBJ NodeId to a Google NodeId.
     *
     * @param sourceNodeId the PBJ NodeId to convert
     * @return the converted Google NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.NodeId fromPbj(
            @NonNull final com.hedera.hapi.platform.state.NodeId sourceNodeId) {
        return com.hedera.hapi.platform.state.legacy.NodeId.newBuilder()
                .setId(sourceNodeId.id())
                .build();
    }

    /**
     * Converts a Google SemanticVersion to a PBJ SemanticVersion.
     *
     * @param sourceVersion the Google SemanticVersion to convert
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
     * Converts a PBJ SemanticVersion to a Google SemanticVersion.
     *
     * @param sourceVersion the PBJ SemanticVersion to convert
     * @return the converted Google SemanticVersion
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
     * Converts a Google Roster to a PBJ Roster.
     *
     * @param sourceRoster the Google Roster to convert
     * @return the converted PBJ Roster
     */
    @NonNull
    public static com.hedera.hapi.node.state.roster.Roster toPbj(
            @NonNull final com.hederahashgraph.api.proto.java.Roster sourceRoster) {
        return com.hedera.hapi.node.state.roster.Roster.newBuilder()
                .rosterEntries(sourceRoster.getRosterEntriesList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ Roster to a Google Roster.
     *
     * @param sourceRoster the PBJ Roster to convert
     * @return the converted Google Roster
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.Roster fromPbj(
            @NonNull final com.hedera.hapi.node.state.roster.Roster sourceRoster) {
        return com.hederahashgraph.api.proto.java.Roster.newBuilder()
                .addAllRosterEntries(sourceRoster.rosterEntries().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a Google RosterEntry to a PBJ RosterEntry.
     *
     * @param sourceEntry the Google RosterEntry to convert
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
     * Converts a PBJ RosterEntry to a Google RosterEntry.
     *
     * @param sourceEntry the PBJ RosterEntry to convert
     * @return the converted Google RosterEntry
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
     * Converts a Google ServiceEndpoint to a PBJ ServiceEndpoint.
     *
     * @param sourceEntry the Google ServiceEndpoint to convert
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
     * Converts a PBJ ServiceEndpoint to a Google ServiceEndpoint.
     *
     * @param sourceEntry the PBJ ServiceEndpoint to convert
     * @return the converted Google ServiceEndpoint
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
     * Converts a Google EventDescriptor to a PBJ EventDescriptor.
     *
     * @param sourceEventDescriptor the Google EventDescriptor to convert
     * @return the converted PBJ EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventDescriptor toPbj(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventDescriptor sourceEventDescriptor) {
        return com.hedera.hapi.platform.event.EventDescriptor.newBuilder()
                .hash(toPbj(sourceEventDescriptor.getHash()))
                .creatorNodeId(sourceEventDescriptor.getCreatorNodeId())
                .birthRound(sourceEventDescriptor.getBirthRound())
                .generation(sourceEventDescriptor.getGeneration())
                .build();
    }

    /**
     * Converts a PBJ EventDescriptor to a Google EventDescriptor.
     *
     * @param sourceEventDescriptor the PBJ EventDescriptor to convert
     * @return the converted Google EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventDescriptor fromPbj(
            @NonNull final com.hedera.hapi.platform.event.EventDescriptor sourceEventDescriptor) {
        return com.hedera.hapi.platform.event.legacy.EventDescriptor.newBuilder()
                .setHash(fromPbj(sourceEventDescriptor.hash()))
                .setCreatorNodeId(sourceEventDescriptor.creatorNodeId())
                .setBirthRound(sourceEventDescriptor.birthRound())
                .setGeneration(sourceEventDescriptor.generation())
                .build();
    }

    /**
     * Converts a Google GossipEvent to a PBJ GossipEvent.
     *
     * @param sourceGossipEvent the Google GossipEvent to convert
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
     * Converts a PBJ GossipEvent to a Google GossipEvent.
     *
     * @param sourceGossipEvent the PBJ GossipEvent to convert
     * @return the converted Google GossipEvent
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.GossipEvent fromPbj(
            @NonNull final com.hedera.hapi.platform.event.GossipEvent sourceGossipEvent) {
        return com.hedera.hapi.platform.event.legacy.GossipEvent.newBuilder()
                .setEventCore(sourceGossipEvent.eventCore() != null ? fromPbj(sourceGossipEvent.eventCore()) : null)
                .setSignature(fromPbj(sourceGossipEvent.signature()))
                .addAllTransactions(sourceGossipEvent.transactions().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .addAllParents(sourceGossipEvent.parents().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
                .build();
    }

    /**
     * Converts a Google EventCore to a PBJ EventCore.
     *
     * @param sourceEventCore the Google EventCore to convert
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
     * Converts a PBJ EventCore to a Google EventCore.
     *
     * @param sourceEventCore the PBJ EventCore to convert
     * @return the converted Google EventCore
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
     * Converts a Google Timestamp to a PBJ Timestamp.
     *
     * @param sourceTimestamp the Google Timestamp to convert
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
     * Converts a PBJ Timestamp to a Google Timestamp.
     *
     * @param sourceTimestamp the PBJ Timestamp to convert
     * @return the converted Google Timestamp
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
     * Converts a Google ByteString to a PBJ Bytes.
     *
     * @param sourceBytes the Google ByteString to convert
     * @return the converted PBJ Bytes
     */
    @NonNull
    public static com.hedera.pbj.runtime.io.buffer.Bytes toPbj(final com.google.protobuf.ByteString sourceBytes) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(sourceBytes.toByteArray());
    }

    /**
     * Converts a PBJ Bytes to a Google ByteString.
     *
     * @param sourceBytes the PBJ Bytes to convert
     * @return the converted Google ByteString
     */
    @NonNull
    public static com.google.protobuf.ByteString fromPbj(final com.hedera.pbj.runtime.io.buffer.Bytes sourceBytes) {
        return com.google.protobuf.ByteString.copyFrom(sourceBytes.toByteArray());
    }

    /**
     * Converts a Google EventConsensusData to a PBJ EventConsensusData.
     *
     * @param sourceEventConsensusData the Google EventConsensusData to convert
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
     * Converts a PBJ EventConsensusData to a Google EventConsensusData.
     *
     * @param sourceEventConsensusData the PBJ EventConsensusData to convert
     * @return the converted Google EventConsensusData
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
     * Converts a Google ConsensusSnapshot to a PBJ ConsensusSnapshot.
     *
     * @param sourceConsensusSnapshot the Google ConsensusSnapshot to convert
     * @return the converted PBJ ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.ConsensusSnapshot toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.ConsensusSnapshot sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.ConsensusSnapshot.newBuilder()
                .round(sourceConsensusSnapshot.getRound())
                .judgeHashes(sourceConsensusSnapshot.getJudgeHashesList().stream()
                        .map(ProtobufConverter::toPbj)
                        .toList())
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
     * Converts a PBJ ConsensusSnapshot to a Google ConsensusSnapshot.
     *
     * @param sourceConsensusSnapshot the PBJ ConsensusSnapshot to convert
     * @return the converted Google ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.ConsensusSnapshot fromPbj(
            @NonNull final com.hedera.hapi.platform.state.ConsensusSnapshot sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.ConsensusSnapshot.newBuilder()
                .setRound(sourceConsensusSnapshot.round())
                .addAllJudgeHashes(sourceConsensusSnapshot.judgeHashes().stream()
                        .map(ProtobufConverter::fromPbj)
                        .toList())
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
     * Converts a Google MinimumJudgeInfo to a PBJ MinimumJudgeInfo.
     *
     * @param sourceConsensusSnapshot the Google MinimumJudgeInfo to convert
     * @return the converted PBJ MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.MinimumJudgeInfo toPbj(
            @NonNull final com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.MinimumJudgeInfo.newBuilder()
                .round(sourceConsensusSnapshot.getRound())
                .minimumJudgeAncientThreshold(sourceConsensusSnapshot.getMinimumJudgeAncientThreshold())
                .build();
    }

    /**
     * Converts a PBJ MinimumJudgeInfo to a Google MinimumJudgeInfo.
     *
     * @param sourceConsensusSnapshot the PBJ MinimumJudgeInfo to convert
     * @return the converted Google MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo fromPbj(
            @NonNull final com.hedera.hapi.platform.state.MinimumJudgeInfo sourceConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo.newBuilder()
                .setRound(sourceConsensusSnapshot.round())
                .setMinimumJudgeAncientThreshold(sourceConsensusSnapshot.minimumJudgeAncientThreshold())
                .build();
    }

    /**
     * Converts a Google JudgeId to a PBJ JudgeId.
     *
     * @param sourceJudgeId the Google JudgeId to convert
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
     * Converts a PBJ JudgeId to a Google JudgeId.
     *
     * @param sourceJudgeId the PBJ JudgeId to convert
     * @return the converted Google JudgeId
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
                .map(ProtobufConverter::toPbj)
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
        final List<ProtoConsensusRound> googleRounds =
                sourceRounds.stream().map(ProtobufConverter::fromPlatform).toList();
        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds.newBuilder()
                .addAllRounds(googleRounds)
                .build();
    }

    /**
     * Converts a ProtoConsensusRound to ConsensusRound.
     *
     * @param sourceRound the ProtoConsensusRound to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.ConsensusRound toPbj(
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
        final List<com.hedera.hapi.platform.event.legacy.GossipEvent> gossipEvents =
                sourceRound.getConsensusEvents().stream()
                        .map(org.hiero.consensus.model.event.PlatformEvent::getGossipEvent)
                        .map(ProtobufConverter::fromPbj)
                        .toList();
        final List<org.hiero.otter.fixtures.container.proto.CesEvent> streamedEvents =
                sourceRound.getStreamedEvents().stream()
                        .map(ProtobufConverter::fromPlatform)
                        .toList();

        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRound.newBuilder()
                .addAllConsensusEvents(gossipEvents)
                .addAllStreamedEvents(streamedEvents)
                .build();
    }

    /**
     * Converts a CesEvent to a Proto CesEvent.
     *
     * @param sourceEvent the CesEvent to convert
     * @return the converted Proto CesEvent
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.CesEvent fromPlatform(
            @NonNull final org.hiero.consensus.model.event.CesEvent sourceEvent) {
        final com.google.protobuf.ByteString runningHash = sourceEvent.getRunningHash() != null
                        && sourceEvent.getRunningHash().getHash() != null
                ? com.google.protobuf.ByteString.copyFrom(
                        sourceEvent.getRunningHash().getHash().copyToByteArray())
                : null;
        return org.hiero.otter.fixtures.container.proto.CesEvent.newBuilder()
                .setPlatformEvent(fromPbj(sourceEvent.getPlatformEvent().getGossipEvent()))
                .setRunningHash(runningHash)
                .setRoundReceived(sourceEvent.getRoundReceived())
                .setLastInRoundReceived(sourceEvent.isLastInRoundReceived())
                .build();
    }

    /**
     * Converts a legacy GossipEvent to a PlatformEvent.
     *
     * @param sourceEvent the legacy GossipEvent to convert
     * @return the converted PlatformEvent
     */
    @NonNull
    public static org.hiero.consensus.model.event.PlatformEvent toPlatform(
            @NonNull final com.hedera.hapi.platform.event.legacy.GossipEvent sourceEvent) {
        return new org.hiero.consensus.model.event.PlatformEvent(toPbj(sourceEvent));
    }

    /**
     * Converts a Google EventWindow to a Platform EventWindow.
     *
     * @param sourceEventWindow the Google EventWindow to convert
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
     * Converts a Platform EventWindow to a Google EventWindow.
     *
     * @param sourceEventWindow the Platform EventWindow to convert
     * @return the converted Google EventWindow
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
     * Converts a Google LogEntry to a StructuredLog.
     *
     * @param sourceLog the Google LogEntry to convert
     * @return the converted StructuredLog
     */
    @NonNull
    public static org.hiero.otter.fixtures.logging.StructuredLog toPbj(
            @NonNull final org.hiero.otter.fixtures.container.proto.LogEntry sourceLog) {
        return new org.hiero.otter.fixtures.logging.StructuredLog(
                sourceLog.getTimestamp(),
                Level.toLevel(sourceLog.getLevel()),
                sourceLog.getMessage(),
                sourceLog.getLoggerName(),
                sourceLog.getThread(),
                MarkerManager.getMarker(sourceLog.getMarker()),
                sourceLog.getNodeId());
    }

    /**
     * Converts a StructuredLog to a Google LogEntry.
     *
     * @param sourceLog the StructuredLog to convert
     * @return the converted Google LogEntry
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
                .setMarker((sourceLog.marker() != null ? sourceLog.marker().toString() : null))
                .setNodeId(sourceLog.nodeId())
                .build();
    }
}
