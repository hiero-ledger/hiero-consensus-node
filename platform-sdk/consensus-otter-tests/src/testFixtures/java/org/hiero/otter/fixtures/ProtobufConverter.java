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
     * @param googleNodeId the Google NodeId to convert
     * @return the converted PBJ NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.NodeId fromGoogle(
            @NonNull final com.hedera.hapi.platform.state.legacy.NodeId googleNodeId) {
        return com.hedera.hapi.platform.state.NodeId.newBuilder()
                .id(googleNodeId.getId())
                .build();
    }

    /**
     * Converts a PBJ NodeId to a Google NodeId.
     *
     * @param pbjNodeId the PBJ NodeId to convert
     * @return the converted Google NodeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.NodeId toGoogle(
            @NonNull final com.hedera.hapi.platform.state.NodeId pbjNodeId) {
        return com.hedera.hapi.platform.state.legacy.NodeId.newBuilder()
                .setId(pbjNodeId.id())
                .build();
    }

    /**
     * Converts a Google SemanticVersion to a PBJ SemanticVersion.
     *
     * @param googleVersion the Google SemanticVersion to convert
     * @return the converted PBJ SemanticVersion
     */
    @NonNull
    public static com.hedera.hapi.node.base.SemanticVersion fromGoogle(
            @NonNull final com.hederahashgraph.api.proto.java.SemanticVersion googleVersion) {
        return com.hedera.hapi.node.base.SemanticVersion.newBuilder()
                .major(googleVersion.getMajor())
                .minor(googleVersion.getMinor())
                .pre(googleVersion.getPre())
                .patch(googleVersion.getPatch())
                .build(googleVersion.getBuild())
                .build();
    }

    /**
     * Converts a PBJ SemanticVersion to a Google SemanticVersion.
     *
     * @param pbjVersion the PBJ SemanticVersion to convert
     * @return the converted Google SemanticVersion
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.SemanticVersion toGoogle(
            @NonNull final com.hedera.hapi.node.base.SemanticVersion pbjVersion) {
        return com.hederahashgraph.api.proto.java.SemanticVersion.newBuilder()
                .setMajor(pbjVersion.major())
                .setMinor(pbjVersion.minor())
                .setPre(pbjVersion.pre())
                .setPatch(pbjVersion.patch())
                .setBuild(pbjVersion.build())
                .build();
    }

    /**
     * Converts a Google Roster to a PBJ Roster.
     *
     * @param googleRoster the Google Roster to convert
     * @return the converted PBJ Roster
     */
    @NonNull
    public static com.hedera.hapi.node.state.roster.Roster fromGoogle(
            @NonNull final com.hederahashgraph.api.proto.java.Roster googleRoster) {
        return com.hedera.hapi.node.state.roster.Roster.newBuilder()
                .rosterEntries(googleRoster.getRosterEntriesList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ Roster to a Google Roster.
     *
     * @param pbjRoster the PBJ Roster to convert
     * @return the converted Google Roster
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.Roster toGoogle(
            @NonNull final com.hedera.hapi.node.state.roster.Roster pbjRoster) {
        return com.hederahashgraph.api.proto.java.Roster.newBuilder()
                .addAllRosterEntries(pbjRoster.rosterEntries().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a Google RosterEntry to a PBJ RosterEntry.
     *
     * @param googleEntry the Google RosterEntry to convert
     * @return the converted PBJ RosterEntry
     */
    @NonNull
    public static com.hedera.hapi.node.state.roster.RosterEntry fromGoogle(
            @NonNull final com.hederahashgraph.api.proto.java.RosterEntry googleEntry) {
        return com.hedera.hapi.node.state.roster.RosterEntry.newBuilder()
                .nodeId(googleEntry.getNodeId())
                .weight(googleEntry.getWeight())
                .gossipCaCertificate(fromGoogle(googleEntry.getGossipCaCertificate()))
                .gossipEndpoint(googleEntry.getGossipEndpointList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ RosterEntry to a Google RosterEntry.
     *
     * @param pbjEntry the PBJ RosterEntry to convert
     * @return the converted Google RosterEntry
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.RosterEntry toGoogle(
            @NonNull final com.hedera.hapi.node.state.roster.RosterEntry pbjEntry) {
        return com.hederahashgraph.api.proto.java.RosterEntry.newBuilder()
                .setNodeId(pbjEntry.nodeId())
                .setWeight(pbjEntry.weight())
                .setGossipCaCertificate(toGoogle(pbjEntry.gossipCaCertificate()))
                .addAllGossipEndpoint(pbjEntry.gossipEndpoint().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a Google ServiceEndpoint to a PBJ ServiceEndpoint.
     *
     * @param googleEntry the Google ServiceEndpoint to convert
     * @return the converted PBJ ServiceEndpoint
     */
    @NonNull
    public static com.hedera.hapi.node.base.ServiceEndpoint fromGoogle(
            @NonNull final com.hederahashgraph.api.proto.java.ServiceEndpoint googleEntry) {
        return com.hedera.hapi.node.base.ServiceEndpoint.newBuilder()
                .ipAddressV4(fromGoogle(googleEntry.getIpAddressV4()))
                .port(googleEntry.getPort())
                .domainName(googleEntry.getDomainName())
                .build();
    }

    /**
     * Converts a PBJ ServiceEndpoint to a Google ServiceEndpoint.
     *
     * @param pbjEntry the PBJ ServiceEndpoint to convert
     * @return the converted Google ServiceEndpoint
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.ServiceEndpoint toGoogle(
            @NonNull final com.hedera.hapi.node.base.ServiceEndpoint pbjEntry) {
        return com.hederahashgraph.api.proto.java.ServiceEndpoint.newBuilder()
                .setIpAddressV4(toGoogle(pbjEntry.ipAddressV4()))
                .setPort(pbjEntry.port())
                .setDomainName(pbjEntry.domainName())
                .build();
    }

    /**
     * Converts a Google EventDescriptor to a PBJ EventDescriptor.
     *
     * @param googleEventDescriptor the Google EventDescriptor to convert
     * @return the converted PBJ EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventDescriptor fromGoogle(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventDescriptor googleEventDescriptor) {
        return com.hedera.hapi.platform.event.EventDescriptor.newBuilder()
                .hash(fromGoogle(googleEventDescriptor.getHash()))
                .creatorNodeId(googleEventDescriptor.getCreatorNodeId())
                .birthRound(googleEventDescriptor.getBirthRound())
                .generation(googleEventDescriptor.getGeneration())
                .build();
    }

    /**
     * Converts a PBJ EventDescriptor to a Google EventDescriptor.
     *
     * @param pbjEventDescriptor the PBJ EventDescriptor to convert
     * @return the converted Google EventDescriptor
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventDescriptor toGoogle(
            @NonNull final com.hedera.hapi.platform.event.EventDescriptor pbjEventDescriptor) {
        return com.hedera.hapi.platform.event.legacy.EventDescriptor.newBuilder()
                .setHash(toGoogle(pbjEventDescriptor.hash()))
                .setCreatorNodeId(pbjEventDescriptor.creatorNodeId())
                .setBirthRound(pbjEventDescriptor.birthRound())
                .setGeneration(pbjEventDescriptor.generation())
                .build();
    }

    /**
     * Converts a Google GossipEvent to a PBJ GossipEvent.
     *
     * @param googleGossipEvent the Google GossipEvent to convert
     * @return the converted PBJ GossipEvent
     */
    @NonNull
    public static com.hedera.hapi.platform.event.GossipEvent fromGoogle(
            @NonNull final com.hedera.hapi.platform.event.legacy.GossipEvent googleGossipEvent) {
        return com.hedera.hapi.platform.event.GossipEvent.newBuilder()
                .eventCore(fromGoogle(googleGossipEvent.getEventCore()))
                .signature(fromGoogle(googleGossipEvent.getSignature()))
                .transactions(googleGossipEvent.getTransactionsList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .parents(googleGossipEvent.getParentsList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ GossipEvent to a Google GossipEvent.
     *
     * @param pbjGossipEvent the PBJ GossipEvent to convert
     * @return the converted Google GossipEvent
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.GossipEvent toGoogle(
            @NonNull final com.hedera.hapi.platform.event.GossipEvent pbjGossipEvent) {
        return com.hedera.hapi.platform.event.legacy.GossipEvent.newBuilder()
                .setEventCore(pbjGossipEvent.eventCore() != null ? toGoogle(pbjGossipEvent.eventCore()) : null)
                .setSignature(toGoogle(pbjGossipEvent.signature()))
                .addAllTransactions(pbjGossipEvent.transactions().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .addAllParents(pbjGossipEvent.parents().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a Google EventCore to a PBJ EventCore.
     *
     * @param googleEventCore the Google EventCore to convert
     * @return the converted PBJ EventCore
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventCore fromGoogle(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventCore googleEventCore) {
        return com.hedera.hapi.platform.event.EventCore.newBuilder()
                .creatorNodeId(googleEventCore.getCreatorNodeId())
                .birthRound(googleEventCore.getBirthRound())
                .timeCreated(fromGoogle(googleEventCore.getTimeCreated()))
                .build();
    }

    /**
     * Converts a PBJ EventCore to a Google EventCore.
     *
     * @param pbjEventCore the PBJ EventCore to convert
     * @return the converted Google EventCore
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventCore toGoogle(
            @NonNull final com.hedera.hapi.platform.event.EventCore pbjEventCore) {
        return com.hedera.hapi.platform.event.legacy.EventCore.newBuilder()
                .setCreatorNodeId(pbjEventCore.creatorNodeId())
                .setBirthRound(pbjEventCore.birthRound())
                .setTimeCreated(pbjEventCore.timeCreated() != null ? toGoogle(pbjEventCore.timeCreated()) : null)
                .build();
    }

    /**
     * Converts a Google Timestamp to a PBJ Timestamp.
     *
     * @param googleTimestamp the Google Timestamp to convert
     * @return the converted PBJ Timestamp
     */
    @NonNull
    public static com.hedera.hapi.node.base.Timestamp fromGoogle(
            final com.hederahashgraph.api.proto.java.Timestamp googleTimestamp) {
        return com.hedera.hapi.node.base.Timestamp.newBuilder()
                .seconds(googleTimestamp.getSeconds())
                .nanos(googleTimestamp.getNanos())
                .build();
    }

    /**
     * Converts a PBJ Timestamp to a Google Timestamp.
     *
     * @param pbjTimestamp the PBJ Timestamp to convert
     * @return the converted Google Timestamp
     */
    @NonNull
    public static com.hederahashgraph.api.proto.java.Timestamp toGoogle(
            final com.hedera.hapi.node.base.Timestamp pbjTimestamp) {
        return com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                .setSeconds(pbjTimestamp.seconds())
                .setNanos(pbjTimestamp.nanos())
                .build();
    }

    /**
     * Converts a Google ByteString to a PBJ Bytes.
     *
     * @param googleBytes the Google ByteString to convert
     * @return the converted PBJ Bytes
     */
    @NonNull
    public static com.hedera.pbj.runtime.io.buffer.Bytes fromGoogle(final com.google.protobuf.ByteString googleBytes) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(googleBytes.toByteArray());
    }

    /**
     * Converts a PBJ Bytes to a Google ByteString.
     *
     * @param pbjBytes the PBJ Bytes to convert
     * @return the converted Google ByteString
     */
    @NonNull
    public static com.google.protobuf.ByteString toGoogle(final com.hedera.pbj.runtime.io.buffer.Bytes pbjBytes) {
        return com.google.protobuf.ByteString.copyFrom(pbjBytes.toByteArray());
    }

    /**
     * Converts a Google EventConsensusData to a PBJ EventConsensusData.
     *
     * @param googleEventConsensusData the Google EventConsensusData to convert
     * @return the converted PBJ EventConsensusData
     */
    @NonNull
    public static com.hedera.hapi.platform.event.EventConsensusData fromGoogle(
            @NonNull final com.hedera.hapi.platform.event.legacy.EventConsensusData googleEventConsensusData) {
        return com.hedera.hapi.platform.event.EventConsensusData.newBuilder()
                .consensusTimestamp(fromGoogle(googleEventConsensusData.getConsensusTimestamp()))
                .consensusOrder(googleEventConsensusData.getConsensusOrder())
                .build();
    }

    /**
     * Converts a PBJ EventConsensusData to a Google EventConsensusData.
     *
     * @param pbjEventConsensusData the PBJ EventConsensusData to convert
     * @return the converted Google EventConsensusData
     */
    @NonNull
    public static com.hedera.hapi.platform.event.legacy.EventConsensusData toGoogle(
            @NonNull final com.hedera.hapi.platform.event.EventConsensusData pbjEventConsensusData) {
        return com.hedera.hapi.platform.event.legacy.EventConsensusData.newBuilder()
                .setConsensusTimestamp(
                        pbjEventConsensusData.consensusTimestamp() != null
                                ? toGoogle(pbjEventConsensusData.consensusTimestamp())
                                : null)
                .setConsensusOrder(pbjEventConsensusData.consensusOrder())
                .build();
    }

    /**
     * Converts a Google ConsensusSnapshot to a PBJ ConsensusSnapshot.
     *
     * @param googleConsensusSnapshot the Google ConsensusSnapshot to convert
     * @return the converted PBJ ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.ConsensusSnapshot fromGoogle(
            @NonNull final com.hedera.hapi.platform.state.legacy.ConsensusSnapshot googleConsensusSnapshot) {
        return com.hedera.hapi.platform.state.ConsensusSnapshot.newBuilder()
                .round(googleConsensusSnapshot.getRound())
                .judgeHashes(googleConsensusSnapshot.getJudgeHashesList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .minimumJudgeInfoList(googleConsensusSnapshot.getMinimumJudgeInfoListList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .nextConsensusNumber(googleConsensusSnapshot.getNextConsensusNumber())
                .consensusTimestamp(fromGoogle(googleConsensusSnapshot.getConsensusTimestamp()))
                .judgeIds(googleConsensusSnapshot.getJudgeIdsList().stream()
                        .map(ProtobufConverter::fromGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a PBJ ConsensusSnapshot to a Google ConsensusSnapshot.
     *
     * @param pbjConsensusSnapshot the PBJ ConsensusSnapshot to convert
     * @return the converted Google ConsensusSnapshot
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.ConsensusSnapshot toGoogle(
            @NonNull final com.hedera.hapi.platform.state.ConsensusSnapshot pbjConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.ConsensusSnapshot.newBuilder()
                .setRound(pbjConsensusSnapshot.round())
                .addAllJudgeHashes(pbjConsensusSnapshot.judgeHashes().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .addAllMinimumJudgeInfoList(pbjConsensusSnapshot.minimumJudgeInfoList().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .setNextConsensusNumber(pbjConsensusSnapshot.nextConsensusNumber())
                .setConsensusTimestamp(
                        pbjConsensusSnapshot.consensusTimestamp() != null
                                ? toGoogle(pbjConsensusSnapshot.consensusTimestamp())
                                : null)
                .addAllJudgeIds(pbjConsensusSnapshot.judgeIds().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList())
                .build();
    }

    /**
     * Converts a Google MinimumJudgeInfo to a PBJ MinimumJudgeInfo.
     *
     * @param googleConsensusSnapshot the Google MinimumJudgeInfo to convert
     * @return the converted PBJ MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.MinimumJudgeInfo fromGoogle(
            @NonNull final com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo googleConsensusSnapshot) {
        return com.hedera.hapi.platform.state.MinimumJudgeInfo.newBuilder()
                .round(googleConsensusSnapshot.getRound())
                .minimumJudgeAncientThreshold(googleConsensusSnapshot.getMinimumJudgeAncientThreshold())
                .build();
    }

    /**
     * Converts a PBJ MinimumJudgeInfo to a Google MinimumJudgeInfo.
     *
     * @param pbjConsensusSnapshot the PBJ MinimumJudgeInfo to convert
     * @return the converted Google MinimumJudgeInfo
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo toGoogle(
            @NonNull final com.hedera.hapi.platform.state.MinimumJudgeInfo pbjConsensusSnapshot) {
        return com.hedera.hapi.platform.state.legacy.MinimumJudgeInfo.newBuilder()
                .setRound(pbjConsensusSnapshot.round())
                .setMinimumJudgeAncientThreshold(pbjConsensusSnapshot.minimumJudgeAncientThreshold())
                .build();
    }

    /**
     * Converts a Google JudgeId to a PBJ JudgeId.
     *
     * @param googleJudgeId the Google JudgeId to convert
     * @return the converted PBJ JudgeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.JudgeId fromGoogle(
            @NonNull final com.hedera.hapi.platform.state.legacy.JudgeId googleJudgeId) {
        return com.hedera.hapi.platform.state.JudgeId.newBuilder()
                .creatorId(googleJudgeId.getCreatorId())
                .judgeHash(fromGoogle(googleJudgeId.getJudgeHash()))
                .build();
    }

    /**
     * Converts a PBJ JudgeId to a Google JudgeId.
     *
     * @param googleJudgeId the PBJ JudgeId to convert
     * @return the converted Google JudgeId
     */
    @NonNull
    public static com.hedera.hapi.platform.state.legacy.JudgeId toGoogle(
            @NonNull final com.hedera.hapi.platform.state.JudgeId googleJudgeId) {
        return com.hedera.hapi.platform.state.legacy.JudgeId.newBuilder()
                .setCreatorId(googleJudgeId.creatorId())
                .setJudgeHash(toGoogle(googleJudgeId.judgeHash()))
                .build();
    }

    /**
     * Converts a ProtoConsensusRounds to List<ConsensusRound>
     *
     * @param googleRounds the ProtoConsensusRounds to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static List<org.hiero.consensus.model.hashgraph.ConsensusRound> fromGoogle(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds googleRounds) {
        return googleRounds.getRoundsList().stream()
                .map(ProtobufConverter::fromGoogle)
                .collect(Collectors.toList());
    }

    /**
     * Converts a List<ConsensusRound> to ProtoConsensusRounds
     *
     * @param platformRounds the ConsensusRounds to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds toGoogle(
            @NonNull final List<org.hiero.consensus.model.hashgraph.ConsensusRound> platformRounds) {
        final List<ProtoConsensusRound> googleRounds =
                platformRounds.stream().map(ProtobufConverter::toGoogle).toList();
        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds.newBuilder()
                .addAllRounds(googleRounds)
                .build();
    }

    /**
     * Converts a ProtoConsensusRound to ConsensusRound.
     *
     * @param googleRound the ProtoConsensusRound to convert
     * @return the converted ConsensusRound
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.ConsensusRound fromGoogle(
            @NonNull final org.hiero.otter.fixtures.container.proto.ProtoConsensusRound googleRound) {
        final com.hedera.hapi.node.state.roster.Roster consensusRoster = fromGoogle(googleRound.getConsensusRoster());
        final List<org.hiero.consensus.model.event.PlatformEvent> consensusEvents =
                googleRound.getConsensusEventsList().stream()
                        .map(ProtobufConverter::toSelfSerializable)
                        .toList();
        final org.hiero.consensus.model.hashgraph.EventWindow eventWindow = fromGoogle(googleRound.getEventWindow());
        final com.hedera.hapi.platform.state.ConsensusSnapshot snapshot = fromGoogle(googleRound.getSnapshot());
        final Instant reachedConsTimestamp = Instant.ofEpochSecond(googleRound.getReachedConsTimestamp());

        return new org.hiero.consensus.model.hashgraph.ConsensusRound(
                consensusRoster,
                consensusEvents,
                eventWindow,
                snapshot,
                googleRound.getPcesRound(),
                reachedConsTimestamp);
    }

    /**
     * Converts a ConsensusRound to a ProtoConsensusRound.
     *
     * @param platformRound the ConsensusRound to convert
     * @return the converted ProtoConsensusRound
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.ProtoConsensusRound toGoogle(
            @NonNull final org.hiero.consensus.model.hashgraph.ConsensusRound platformRound) {
        final List<com.hedera.hapi.platform.event.legacy.GossipEvent> gossipEvents =
                platformRound.getConsensusEvents().stream()
                        .map(org.hiero.consensus.model.event.PlatformEvent::getGossipEvent)
                        .map(ProtobufConverter::toGoogle)
                        .toList();
        final List<org.hiero.otter.fixtures.container.proto.CesEvent> streamedEvents =
                platformRound.getStreamedEvents().stream()
                        .map(ProtobufConverter::toGoogle)
                        .toList();

        return org.hiero.otter.fixtures.container.proto.ProtoConsensusRound.newBuilder()
                .addAllConsensusEvents(gossipEvents)
                .addAllStreamedEvents(streamedEvents)
                .build();
    }

    /**
     * Converts a CesEvent to a Proto CesEvent.
     *
     * @param cesEvent the CesEvent to convert
     * @return the converted Proto CesEvent
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.CesEvent toGoogle(
            @NonNull final org.hiero.consensus.model.event.CesEvent cesEvent) {
        final com.google.protobuf.ByteString runningHash =
                cesEvent.getRunningHash() != null && cesEvent.getRunningHash().getHash() != null
                        ? com.google.protobuf.ByteString.copyFrom(
                                cesEvent.getRunningHash().getHash().copyToByteArray())
                        : null;
        return org.hiero.otter.fixtures.container.proto.CesEvent.newBuilder()
                .setPlatformEvent(toGoogle(cesEvent.getPlatformEvent().getGossipEvent()))
                .setRunningHash(runningHash)
                .setRoundReceived(cesEvent.getRoundReceived())
                .setLastInRoundReceived(cesEvent.isLastInRoundReceived())
                .build();
    }

    /**
     * Converts a legacy GossipEvent to a PlatformEvent.
     *
     * @param protoEvent the legacy GossipEvent to convert
     * @return the converted PlatformEvent
     */
    @NonNull
    public static org.hiero.consensus.model.event.PlatformEvent toSelfSerializable(
            @NonNull final com.hedera.hapi.platform.event.legacy.GossipEvent protoEvent) {
        return new org.hiero.consensus.model.event.PlatformEvent(fromGoogle(protoEvent));
    }

    /**
     * Converts a Google EventWindow to a Platform EventWindow.
     *
     * @param googleEventWindow the Google EventWindow to convert
     * @return the converted Platform EventWindow
     */
    @NonNull
    public static org.hiero.consensus.model.hashgraph.EventWindow fromGoogle(
            @NonNull final org.hiero.otter.fixtures.container.proto.EventWindow googleEventWindow) {
        return new org.hiero.consensus.model.hashgraph.EventWindow(
                googleEventWindow.getLatestConsensusRound(),
                googleEventWindow.getNewEventBirthRound(),
                googleEventWindow.getAncientThreshold(),
                googleEventWindow.getExpiredThreshold());
    }

    /**
     * Converts a Platform EventWindow to a Google EventWindow.
     *
     * @param platformEventWindow the Platform EventWindow to convert
     * @return the converted Google EventWindow
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.EventWindow toGoogle(
            @NonNull final org.hiero.consensus.model.hashgraph.EventWindow platformEventWindow) {
        return org.hiero.otter.fixtures.container.proto.EventWindow.newBuilder()
                .setLatestConsensusRound(platformEventWindow.latestConsensusRound())
                .setNewEventBirthRound(platformEventWindow.newEventBirthRound())
                .setAncientThreshold(platformEventWindow.ancientThreshold())
                .setExpiredThreshold(platformEventWindow.expiredThreshold())
                .build();
    }

    /**
     * Converts a Google LogEntry to a StructuredLog.
     *
     * @param log the Google LogEntry to convert
     * @return the converted StructuredLog
     */
    @NonNull
    public static org.hiero.otter.fixtures.logging.StructuredLog fromGoogle(
            @NonNull final org.hiero.otter.fixtures.container.proto.LogEntry log) {
        return new org.hiero.otter.fixtures.logging.StructuredLog(
                log.getTimestamp(),
                Level.toLevel(log.getLevel()),
                log.getMessage(),
                log.getLoggerName(),
                log.getThread(),
                MarkerManager.getMarker(log.getMarker()),
                log.getNodeId());
    }

    /**
     * Converts a StructuredLog to a Google LogEntry.
     *
     * @param log the StructuredLog to convert
     * @return the converted Google LogEntry
     */
    @NonNull
    public static org.hiero.otter.fixtures.container.proto.LogEntry toGoogle(
            @NonNull final org.hiero.otter.fixtures.logging.StructuredLog log) {
        return org.hiero.otter.fixtures.container.proto.LogEntry.newBuilder()
                .setTimestamp(log.timestamp())
                .setLevel(log.level().toString())
                .setLoggerName(log.loggerName())
                .setThread(log.threadName())
                .setMessage(log.message())
                .setMarker((log.marker() != null ? log.marker().toString() : null))
                .setNodeId(log.nodeId())
                .build();
    }
}
