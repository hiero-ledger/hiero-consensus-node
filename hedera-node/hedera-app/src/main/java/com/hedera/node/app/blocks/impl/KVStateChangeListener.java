// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.swirlds.state.StateChangeListener.StateType.MAP;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.history.RecordedHistorySignature;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.state.tss.TssMessageMapKey;
import com.hedera.hapi.node.state.tss.TssVoteMapKey;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody;
import com.swirlds.state.StateChangeListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A state change listener that tracks an entire sequence of changes, even if this sequence
 * repeats changes to the same key multiple times in a block boundary.
 */
public class KVStateChangeListener implements StateChangeListener {
    private static final Set<StateType> TARGET_DATA_TYPES = EnumSet.of(MAP);

    private final List<StateChange> stateChanges = new ArrayList<>();

    /**
     * Resets the state changes.
     */
    public void reset() {
        stateChanges.clear();
    }

    @Override
    public Set<StateType> stateTypes() {
        return TARGET_DATA_TYPES;
    }

    @Override
    public int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        Objects.requireNonNull(stateKey, "stateKey must not be null");

        return BlockImplUtils.stateIdFor(serviceName, stateKey);
    }

    @Override
    public <K, V> void mapUpdateChange(final int stateId, @NonNull final K key, @NonNull final V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        final var change = MapUpdateChange.newBuilder()
                .key(mapChangeKeyFor(key))
                .value(mapChangeValueFor(value))
                .build();
        final var stateChange =
                StateChange.newBuilder().stateId(stateId).mapUpdate(change).build();
        stateChanges.add(stateChange);
    }

    @Override
    public <K> void mapDeleteChange(final int stateId, @NonNull final K key) {
        Objects.requireNonNull(key, "key must not be null");
        final var change =
                MapDeleteChange.newBuilder().key(mapChangeKeyFor(key)).build();
        stateChanges.add(
                StateChange.newBuilder().stateId(stateId).mapDelete(change).build());
    }

    /**
     * Returns the list of state changes.
     * @return the list of state changes
     */
    public List<StateChange> getStateChanges() {
        return stateChanges;
    }

    private static <K> MapChangeKey mapChangeKeyFor(@NonNull final K key) {
        return switch (key) {
            case AccountID accountID ->
                MapChangeKey.newBuilder().accountIdKey(accountID).build();
            case EntityIDPair entityIDPair ->
                MapChangeKey.newBuilder()
                        .tokenRelationshipKey(new TokenAssociation(entityIDPair.tokenId(), entityIDPair.accountId()))
                        .build();
            case EntityNumber entityNumber ->
                MapChangeKey.newBuilder().entityNumberKey(entityNumber.number()).build();
            case FileID fileID -> MapChangeKey.newBuilder().fileIdKey(fileID).build();
            case NftID nftID -> MapChangeKey.newBuilder().nftIdKey(nftID).build();
            case ProtoBytes protoBytes ->
                MapChangeKey.newBuilder().protoBytesKey(protoBytes.value()).build();
            case ProtoLong protoLong ->
                MapChangeKey.newBuilder().protoLongKey(protoLong.value()).build();
            case ProtoString protoString ->
                MapChangeKey.newBuilder().protoStringKey(protoString.value()).build();
            case ScheduleID scheduleID ->
                MapChangeKey.newBuilder().scheduleIdKey(scheduleID).build();
            case SlotKey slotKey ->
                MapChangeKey.newBuilder().slotKeyKey(slotKey).build();
            case TokenID tokenID ->
                MapChangeKey.newBuilder().tokenIdKey(tokenID).build();
            case TopicID topicID ->
                MapChangeKey.newBuilder().topicIdKey(topicID).build();
            case ContractID contractID ->
                MapChangeKey.newBuilder().contractIdKey(contractID).build();
            case PendingAirdropId pendingAirdropId ->
                MapChangeKey.newBuilder().pendingAirdropIdKey(pendingAirdropId).build();
            case TimestampSeconds timestampSeconds ->
                MapChangeKey.newBuilder().timestampSecondsKey(timestampSeconds).build();
            case ScheduledOrder scheduledOrder ->
                MapChangeKey.newBuilder().scheduledOrderKey(scheduledOrder).build();
            case TssMessageMapKey tssMessageMapKey ->
                MapChangeKey.newBuilder().tssMessageMapKey(tssMessageMapKey).build();
            case TssVoteMapKey tssVoteMapKey ->
                MapChangeKey.newBuilder().tssVoteMapKey(tssVoteMapKey).build();
            case HintsPartyId hintsPartyId ->
                MapChangeKey.newBuilder().hintsPartyIdKey(hintsPartyId).build();
            case PreprocessingVoteId preprocessingVoteId ->
                MapChangeKey.newBuilder()
                        .preprocessingVoteIdKey(preprocessingVoteId)
                        .build();
            case NodeId nodeId -> MapChangeKey.newBuilder().nodeIdKey(nodeId).build();
            case ConstructionNodeId constructionNodeId ->
                MapChangeKey.newBuilder()
                        .constructionNodeIdKey(constructionNodeId)
                        .build();
            default ->
                throw new IllegalStateException(
                        "Unrecognized key type " + key.getClass().getSimpleName());
        };
    }

    private static <V> MapChangeValue mapChangeValueFor(@NonNull final V value) {
        return switch (value) {
            case Node node -> MapChangeValue.newBuilder().nodeValue(node).build();
            case Account account ->
                MapChangeValue.newBuilder().accountValue(account).build();
            case AccountID accountID ->
                MapChangeValue.newBuilder().accountIdValue(accountID).build();
            case Bytecode bytecode ->
                MapChangeValue.newBuilder().bytecodeValue(bytecode).build();
            case File file -> MapChangeValue.newBuilder().fileValue(file).build();
            case Nft nft -> MapChangeValue.newBuilder().nftValue(nft).build();
            case ProtoString protoString ->
                MapChangeValue.newBuilder()
                        .protoStringValue(protoString.value())
                        .build();
            case Roster roster ->
                MapChangeValue.newBuilder().rosterValue(roster).build();
            case Schedule schedule ->
                MapChangeValue.newBuilder().scheduleValue(schedule).build();
            case ScheduleID scheduleID ->
                MapChangeValue.newBuilder().scheduleIdValue(scheduleID).build();
            case ScheduleList scheduleList ->
                MapChangeValue.newBuilder().scheduleListValue(scheduleList).build();
            case SlotValue slotValue ->
                MapChangeValue.newBuilder().slotValueValue(slotValue).build();
            case StakingNodeInfo stakingNodeInfo ->
                MapChangeValue.newBuilder()
                        .stakingNodeInfoValue(stakingNodeInfo)
                        .build();
            case Token token -> MapChangeValue.newBuilder().tokenValue(token).build();
            case TokenRelation tokenRelation ->
                MapChangeValue.newBuilder().tokenRelationValue(tokenRelation).build();
            case Topic topic -> MapChangeValue.newBuilder().topicValue(topic).build();
            case AccountPendingAirdrop accountPendingAirdrop ->
                MapChangeValue.newBuilder()
                        .accountPendingAirdropValue(accountPendingAirdrop)
                        .build();
            case ScheduledCounts scheduledCounts ->
                MapChangeValue.newBuilder()
                        .scheduledCountsValue(scheduledCounts)
                        .build();
            case ThrottleUsageSnapshots throttleUsageSnapshots ->
                MapChangeValue.newBuilder()
                        .throttleUsageSnapshotsValue(throttleUsageSnapshots)
                        .build();
            case TssMessageTransactionBody tssMessageTransactionBody ->
                MapChangeValue.newBuilder()
                        .tssMessageValue(tssMessageTransactionBody)
                        .build();
            case TssVoteTransactionBody tssVoteTransactionBody ->
                MapChangeValue.newBuilder().tssVoteValue(tssVoteTransactionBody).build();
            case HintsKeySet hintsKeySet ->
                MapChangeValue.newBuilder().hintsKeySetValue(hintsKeySet).build();
            case PreprocessingVote preprocessingVote ->
                MapChangeValue.newBuilder()
                        .preprocessingVoteValue(preprocessingVote)
                        .build();
            case RecordedHistorySignature recordedHistorySignature ->
                MapChangeValue.newBuilder()
                        .historySignatureValue(recordedHistorySignature)
                        .build();
            case HistoryProofVote historyProofVote ->
                MapChangeValue.newBuilder()
                        .historyProofVoteValue(historyProofVote)
                        .build();
            case ProofKeySet proofKeySet ->
                MapChangeValue.newBuilder().proofKeySetValue(proofKeySet).build();
            case CrsPublicationTransactionBody crsPublicationTransactionBody ->
                MapChangeValue.newBuilder()
                        .crsPublicationValue(crsPublicationTransactionBody)
                        .build();
            default ->
                throw new IllegalStateException(
                        "Unexpected value: " + value.getClass().getSimpleName());
        };
    }
}
