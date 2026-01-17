// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.QueuePopChange;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HookId;
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
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.recordcache.TransactionReceiptEntries;
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
import com.hedera.hapi.node.state.tss.TssEncryptionKeys;
import com.hedera.hapi.node.state.tss.TssMessageMapKey;
import com.hedera.hapi.node.state.tss.TssVoteMapKey;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody;
import com.hedera.pbj.runtime.OneOf;
import com.swirlds.state.StateChangeListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A state change listener that tracks an entire sequence of changes, even if this sequence
 * repeats changes to the same key multiple times in a block boundary.
 */
public class ImmediateStateChangeListener implements StateChangeListener {
    private static final Set<StateType> TARGET_DATA_TYPES = EnumSet.of(MAP, QUEUE);

    private final List<StateChange> kvStateChanges = new ArrayList<>();

    private final List<StateChange> queueStateChanges = new ArrayList<>();

    @Nullable
    private Predicate<Object> logicallyIdenticalMapping;

    /**
     * Resets kv state changes.
     */
    public void resetKvStateChanges(@Nullable final Predicate<Object> logicallyIdenticalMapping) {
        this.logicallyIdenticalMapping = logicallyIdenticalMapping;
        kvStateChanges.clear();
    }

    /**
     * Resets queue state changes.
     */
    public void resetQueueStateChanges() {
        queueStateChanges.clear();
    }

    /**
     * Resets all state changes.
     */
    public void reset(@Nullable final Predicate<Object> logicallyIdenticalMapping) {
        this.logicallyIdenticalMapping = logicallyIdenticalMapping;
        kvStateChanges.clear();
        queueStateChanges.clear();
    }

    @Override
    public Set<StateType> stateTypes() {
        return TARGET_DATA_TYPES;
    }

    @Override
    public <K, V> void mapUpdateChange(final int stateId, @NonNull final K key, @NonNull final V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        final boolean identical = logicallyIdenticalMapping != null && logicallyIdenticalMapping.test(key);

        final var change = new MapUpdateChange(mapChangeKeyFor(key), mapChangeValueFor(value), identical);
        final var stateChange =
                StateChange.newBuilder().stateId(stateId).mapUpdate(change).build();
        kvStateChanges.add(stateChange);
    }

    @Override
    public <K> void mapDeleteChange(final int stateId, @NonNull final K key) {
        Objects.requireNonNull(key, "key must not be null");
        final var change =
                MapDeleteChange.newBuilder().key(mapChangeKeyFor(key)).build();
        kvStateChanges.add(
                StateChange.newBuilder().stateId(stateId).mapDelete(change).build());
    }

    @Override
    public <V> void queuePushChange(final int stateId, @NonNull final V value) {
        requireNonNull(value);
        final var stateChange = StateChange.newBuilder()
                .stateId(stateId)
                .queuePush(new QueuePushChange(queuePushChangeValueFor(value)))
                .build();
        queueStateChanges.add(stateChange);
    }

    @Override
    public void queuePopChange(final int stateId) {
        final var stateChange = StateChange.newBuilder()
                .stateId(stateId)
                .queuePop(new QueuePopChange())
                .build();
        queueStateChanges.add(stateChange);
    }

    /**
     * Returns the list of kv state changes.
     * @return the list of kv state changes
     */
    public List<StateChange> getKvStateChanges() {
        return kvStateChanges;
    }

    /**
     * Returns the list of queue state changes.
     * @return the list of queue state changes
     */
    public List<StateChange> getQueueStateChanges() {
        return queueStateChanges;
    }

    /**
     * Returns the list of state changes.
     * @return the list of state changes
     */
    public List<StateChange> getStateChanges() {
        final var allStateChanges = new LinkedList<StateChange>();
        allStateChanges.addAll(kvStateChanges);
        allStateChanges.addAll(queueStateChanges);
        return allStateChanges;
    }

    private static <K> MapChangeKey mapChangeKeyFor(@NonNull final K key) {
        return switch (key) {
            case AccountID accountID ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.ACCOUNT_ID_KEY, accountID));
            case EntityIDPair entityIDPair ->
                new MapChangeKey(new OneOf<>(
                        MapChangeKey.KeyChoiceOneOfType.TOKEN_RELATIONSHIP_KEY,
                        new TokenAssociation(entityIDPair.tokenId(), entityIDPair.accountId())));
            case EntityNumber entityNumber ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.ENTITY_NUMBER_KEY, entityNumber.number()));
            case FileID fileID -> new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.FILE_ID_KEY, fileID));
            case NftID nftID -> new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.NFT_ID_KEY, nftID));
            case ProtoBytes protoBytes ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.PROTO_BYTES_KEY, protoBytes.value()));
            case ProtoLong protoLong ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.PROTO_LONG_KEY, protoLong.value()));
            case ProtoString protoString ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.PROTO_STRING_KEY, protoString.value()));
            case ScheduleID scheduleID ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.SCHEDULE_ID_KEY, scheduleID));
            case SlotKey slotKey ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.SLOT_KEY_KEY, slotKey));
            case TokenID tokenID ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.TOKEN_ID_KEY, tokenID));
            case TopicID topicID ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.TOPIC_ID_KEY, topicID));
            case ContractID contractID ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.CONTRACT_ID_KEY, contractID));
            case PendingAirdropId pendingAirdropId ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.PENDING_AIRDROP_ID_KEY, pendingAirdropId));
            case TimestampSeconds timestampSeconds ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.TIMESTAMP_SECONDS_KEY, timestampSeconds));
            case ScheduledOrder scheduledOrder ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.SCHEDULED_ORDER_KEY, scheduledOrder));
            case TssMessageMapKey tssMessageMapKey ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.TSS_MESSAGE_MAP_KEY, tssMessageMapKey));
            case TssVoteMapKey tssVoteMapKey ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.TSS_VOTE_MAP_KEY, tssVoteMapKey));
            case HintsPartyId hintsPartyId ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.HINTS_PARTY_ID_KEY, hintsPartyId));
            case PreprocessingVoteId preprocessingVoteId ->
                new MapChangeKey(
                        new OneOf<>(MapChangeKey.KeyChoiceOneOfType.PREPROCESSING_VOTE_ID_KEY, preprocessingVoteId));
            case NodeId nodeId -> new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.NODE_ID_KEY, nodeId));
            case ConstructionNodeId constructionNodeId ->
                new MapChangeKey(
                        new OneOf<>(MapChangeKey.KeyChoiceOneOfType.CONSTRUCTION_NODE_ID_KEY, constructionNodeId));
            case LambdaSlotKey lambdaSlotKey ->
                new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.LAMBDA_SLOT_KEY, lambdaSlotKey));
            case HookId HookId -> new MapChangeKey(new OneOf<>(MapChangeKey.KeyChoiceOneOfType.HOOK_ID_KEY, HookId));
            default ->
                throw new IllegalStateException(
                        "Unrecognized key type " + key.getClass().getSimpleName());
        };
    }

    private static <V> MapChangeValue mapChangeValueFor(@NonNull final V value) {
        return switch (value) {
            case Node node -> new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.NODE_VALUE, node));
            case NodeId nodeId ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.NODE_ID_VALUE, nodeId));
            case Account account ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.ACCOUNT_VALUE, account));
            case AccountID accountID ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.ACCOUNT_ID_VALUE, accountID));
            case Bytecode bytecode ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.BYTECODE_VALUE, bytecode));
            case File file -> new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.FILE_VALUE, file));
            case Nft nft -> new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.NFT_VALUE, nft));
            case ProtoString protoString ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.PROTO_STRING_VALUE, protoString));
            case Roster roster ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.ROSTER_VALUE, roster));
            case Schedule schedule ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.SCHEDULE_VALUE, schedule));
            case ScheduleID scheduleID ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.SCHEDULE_ID_VALUE, scheduleID));
            case ScheduleList scheduleList ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.SCHEDULE_LIST_VALUE, scheduleList));
            case SlotValue slotValue ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.SLOT_VALUE_VALUE, slotValue));
            case StakingNodeInfo stakingNodeInfo ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.STAKING_NODE_INFO_VALUE, stakingNodeInfo));
            case Token token -> new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TOKEN_VALUE, token));
            case TokenRelation tokenRelation ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TOKEN_RELATION_VALUE, tokenRelation));
            case Topic topic -> new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TOPIC_VALUE, topic));
            case AccountPendingAirdrop accountPendingAirdrop ->
                new MapChangeValue(new OneOf<>(
                        MapChangeValue.ValueChoiceOneOfType.ACCOUNT_PENDING_AIRDROP_VALUE, accountPendingAirdrop));
            case ScheduledCounts scheduledCounts ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.SCHEDULED_COUNTS_VALUE, scheduledCounts));
            case ThrottleUsageSnapshots throttleUsageSnapshots ->
                new MapChangeValue(new OneOf<>(
                        MapChangeValue.ValueChoiceOneOfType.THROTTLE_USAGE_SNAPSHOTS_VALUE, throttleUsageSnapshots));
            case TssMessageTransactionBody tssMessageTransactionBody ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TSS_MESSAGE_VALUE, tssMessageTransactionBody));
            case TssVoteTransactionBody tssVoteTransactionBody ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TSS_VOTE_VALUE, tssVoteTransactionBody));
            case TssEncryptionKeys tssEncryptionKeys ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.TSS_ENCRYPTION_KEYS_VALUE, tssEncryptionKeys));
            case HintsKeySet hintsKeySet ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.HINTS_KEY_SET_VALUE, hintsKeySet));
            case PreprocessingVote preprocessingVote ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.PREPROCESSING_VOTE_VALUE, preprocessingVote));
            case RecordedHistorySignature recordedHistorySignature ->
                new MapChangeValue(new OneOf<>(
                        MapChangeValue.ValueChoiceOneOfType.HISTORY_SIGNATURE_VALUE, recordedHistorySignature));
            case HistoryProofVote historyProofVote ->
                new MapChangeValue(
                        new OneOf<>(MapChangeValue.ValueChoiceOneOfType.HISTORY_PROOF_VOTE_VALUE, historyProofVote));
            case ProofKeySet proofKeySet ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.PROOF_KEY_SET_VALUE, proofKeySet));
            case CrsPublicationTransactionBody crsPublicationTransactionBody ->
                new MapChangeValue(new OneOf<>(
                        MapChangeValue.ValueChoiceOneOfType.CRS_PUBLICATION_VALUE, crsPublicationTransactionBody));
            case EvmHookState evmHookState ->
                new MapChangeValue(new OneOf<>(MapChangeValue.ValueChoiceOneOfType.EVM_HOOK_STATE_VALUE, evmHookState));
            default ->
                throw new IllegalStateException(
                        "Unexpected value: " + value.getClass().getSimpleName());
        };
    }

    private static <V> OneOf<QueuePushChange.ValueOneOfType> queuePushChangeValueFor(@NonNull final V value) {
        switch (value) {
            case ProtoBytes protoBytesElement -> {
                return new OneOf<>(QueuePushChange.ValueOneOfType.PROTO_BYTES_ELEMENT, protoBytesElement.value());
            }
            case TransactionReceiptEntries transactionReceiptEntriesElement -> {
                return new OneOf<>(
                        QueuePushChange.ValueOneOfType.TRANSACTION_RECEIPT_ENTRIES_ELEMENT,
                        transactionReceiptEntriesElement);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unknown value type " + value.getClass().getName());
        }
    }
}
