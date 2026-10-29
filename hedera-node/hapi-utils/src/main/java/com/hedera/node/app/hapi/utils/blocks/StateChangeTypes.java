// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Map.entry;

import com.hedera.hapi.block.stream.output.MapChangeValue.ValueChoiceOneOfType;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange.NewValueOneOfType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.history.RecordedHistorySignature;
import com.hedera.hapi.node.state.history.WrapsMessageHistory;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.state.tss.TssEncryptionKeys;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import com.hedera.pbj.runtime.Codec;
import java.util.List;
import java.util.Map;

/**
 * Block-stream payload codecs, keyed by model class and by {@code MapChangeValue} / {@code SingletonUpdateChange}
 * field number. Add an entry here when a new payload is added to either oneof; the tests over every oneof arm fail
 * until it is registered.
 */
final class StateChangeTypes {
    private StateChangeTypes() {}

    static final ValueType ACCOUNT_ID = type(AccountID.class, AccountID.PROTOBUF);
    static final ValueType SCHEDULE_ID = type(ScheduleID.class, ScheduleID.PROTOBUF);
    static final ValueType TIMESTAMP = type(Timestamp.class, Timestamp.PROTOBUF);
    static final ValueType NODE = type(Node.class, Node.PROTOBUF);
    static final ValueType REGISTERED_NODE = type(RegisteredNode.class, RegisteredNode.PROTOBUF);
    static final ValueType BLOCK_INFO = type(BlockInfo.class, BlockInfo.PROTOBUF);
    static final ValueType RUNNING_HASHES = type(RunningHashes.class, RunningHashes.PROTOBUF);
    static final ValueType BLOCK_STREAM_INFO = type(BlockStreamInfo.class, BlockStreamInfo.PROTOBUF);
    static final ValueType CLPR_CHANNEL = type(ClprChannel.class, ClprChannel.PROTOBUF);
    static final ValueType CLPR_CONNECTOR = type(ClprConnector.class, ClprConnector.PROTOBUF);
    static final ValueType CLPR_ENDPOINT_MANIFEST = type(ClprEndpointManifest.class, ClprEndpointManifest.PROTOBUF);
    static final ValueType CLPR_ENDPOINT_MANIFEST_CONSTRUCTION =
            type(ClprEndpointManifestConstruction.class, ClprEndpointManifestConstruction.PROTOBUF);
    static final ValueType CLPR_LEDGER_CONFIGURATION =
            type(ClprLedgerConfiguration.class, ClprLedgerConfiguration.PROTOBUF);
    static final ValueType CLPR_MESSAGE_VALUE = type(ClprMessageValue.class, ClprMessageValue.PROTOBUF);
    static final ValueType ENTITY_NUMBER = type(EntityNumber.class, EntityNumber.PROTOBUF);
    static final ValueType CONGESTION_LEVEL_STARTS = type(CongestionLevelStarts.class, CongestionLevelStarts.PROTOBUF);
    static final ValueType TOPIC = type(Topic.class, Topic.PROTOBUF);
    static final ValueType BYTECODE = type(Bytecode.class, Bytecode.PROTOBUF);
    static final ValueType SLOT_VALUE = type(SlotValue.class, SlotValue.PROTOBUF);
    static final ValueType ENTITY_COUNTS = type(EntityCounts.class, EntityCounts.PROTOBUF);
    static final ValueType FILE = type(File.class, File.PROTOBUF);
    static final ValueType CRS_STATE = type(CRSState.class, CRSState.PROTOBUF);
    static final ValueType HINTS_CONSTRUCTION = type(HintsConstruction.class, HintsConstruction.PROTOBUF);
    static final ValueType HINTS_KEY_SET = type(HintsKeySet.class, HintsKeySet.PROTOBUF);
    static final ValueType PREPROCESSING_VOTE = type(PreprocessingVote.class, PreprocessingVote.PROTOBUF);
    static final ValueType HISTORY_PROOF_CONSTRUCTION =
            type(HistoryProofConstruction.class, HistoryProofConstruction.PROTOBUF);
    static final ValueType HISTORY_PROOF_VOTE = type(HistoryProofVote.class, HistoryProofVote.PROTOBUF);
    static final ValueType PROOF_KEY_SET = type(ProofKeySet.class, ProofKeySet.PROTOBUF);
    static final ValueType RECORDED_HISTORY_SIGNATURE =
            type(RecordedHistorySignature.class, RecordedHistorySignature.PROTOBUF);
    static final ValueType WRAPS_MESSAGE_HISTORY = type(WrapsMessageHistory.class, WrapsMessageHistory.PROTOBUF);
    static final ValueType EVM_HOOK_STATE = type(EvmHookState.class, EvmHookState.PROTOBUF);
    static final ValueType PROTO_BYTES = type(ProtoBytes.class, ProtoBytes.PROTOBUF);
    static final ValueType PROTO_STRING = type(ProtoString.class, ProtoString.PROTOBUF);
    static final ValueType ROSTER = type(Roster.class, Roster.PROTOBUF);
    static final ValueType ROSTER_STATE = type(RosterState.class, RosterState.PROTOBUF);
    static final ValueType SCHEDULE = type(Schedule.class, Schedule.PROTOBUF);
    static final ValueType SCHEDULE_LIST = type(ScheduleList.class, ScheduleList.PROTOBUF);
    static final ValueType SCHEDULED_COUNTS = type(ScheduledCounts.class, ScheduledCounts.PROTOBUF);
    static final ValueType THROTTLE_USAGE_SNAPSHOTS =
            type(ThrottleUsageSnapshots.class, ThrottleUsageSnapshots.PROTOBUF);
    static final ValueType ACCOUNT = type(Account.class, Account.PROTOBUF);
    static final ValueType ACCOUNT_PENDING_AIRDROP = type(AccountPendingAirdrop.class, AccountPendingAirdrop.PROTOBUF);
    static final ValueType NETWORK_STAKING_REWARDS = type(NetworkStakingRewards.class, NetworkStakingRewards.PROTOBUF);
    static final ValueType NFT = type(Nft.class, Nft.PROTOBUF);
    static final ValueType NODE_PAYMENTS = type(NodePayments.class, NodePayments.PROTOBUF);
    static final ValueType NODE_REWARDS = type(NodeRewards.class, NodeRewards.PROTOBUF);
    static final ValueType STAKING_NODE_INFO = type(StakingNodeInfo.class, StakingNodeInfo.PROTOBUF);
    static final ValueType TOKEN = type(Token.class, Token.PROTOBUF);
    static final ValueType TOKEN_RELATION = type(TokenRelation.class, TokenRelation.PROTOBUF);
    static final ValueType TSS_ENCRYPTION_KEYS = type(TssEncryptionKeys.class, TssEncryptionKeys.PROTOBUF);
    static final ValueType EXCHANGE_RATE_SET = type(ExchangeRateSet.class, ExchangeRateSet.PROTOBUF);
    static final ValueType NODE_ID = type(NodeId.class, NodeId.PROTOBUF);
    static final ValueType PLATFORM_STATE = type(PlatformState.class, PlatformState.PROTOBUF);
    static final ValueType CRS_PUBLICATION_TRANSACTION_BODY =
            type(CrsPublicationTransactionBody.class, CrsPublicationTransactionBody.PROTOBUF);
    static final ValueType TSS_MESSAGE_TRANSACTION_BODY =
            type(TssMessageTransactionBody.class, TssMessageTransactionBody.PROTOBUF);
    static final ValueType TSS_VOTE_TRANSACTION_BODY =
            type(TssVoteTransactionBody.class, TssVoteTransactionBody.PROTOBUF);

    private static final List<ValueType> TYPES = List.of(
            ACCOUNT_ID,
            SCHEDULE_ID,
            TIMESTAMP,
            NODE,
            REGISTERED_NODE,
            BLOCK_INFO,
            RUNNING_HASHES,
            BLOCK_STREAM_INFO,
            CLPR_CHANNEL,
            CLPR_CONNECTOR,
            CLPR_ENDPOINT_MANIFEST,
            CLPR_ENDPOINT_MANIFEST_CONSTRUCTION,
            CLPR_LEDGER_CONFIGURATION,
            CLPR_MESSAGE_VALUE,
            ENTITY_NUMBER,
            CONGESTION_LEVEL_STARTS,
            TOPIC,
            BYTECODE,
            SLOT_VALUE,
            ENTITY_COUNTS,
            FILE,
            CRS_STATE,
            HINTS_CONSTRUCTION,
            HINTS_KEY_SET,
            PREPROCESSING_VOTE,
            HISTORY_PROOF_CONSTRUCTION,
            HISTORY_PROOF_VOTE,
            PROOF_KEY_SET,
            RECORDED_HISTORY_SIGNATURE,
            WRAPS_MESSAGE_HISTORY,
            EVM_HOOK_STATE,
            PROTO_BYTES,
            PROTO_STRING,
            ROSTER,
            ROSTER_STATE,
            SCHEDULE,
            SCHEDULE_LIST,
            SCHEDULED_COUNTS,
            THROTTLE_USAGE_SNAPSHOTS,
            ACCOUNT,
            ACCOUNT_PENDING_AIRDROP,
            NETWORK_STAKING_REWARDS,
            NFT,
            NODE_PAYMENTS,
            NODE_REWARDS,
            STAKING_NODE_INFO,
            TOKEN,
            TOKEN_RELATION,
            TSS_ENCRYPTION_KEYS,
            EXCHANGE_RATE_SET,
            NODE_ID,
            PLATFORM_STATE,
            CRS_PUBLICATION_TRANSACTION_BODY,
            TSS_MESSAGE_TRANSACTION_BODY,
            TSS_VOTE_TRANSACTION_BODY);

    private static final Map<Integer, ValueType> MAP_TYPES = Map.ofEntries(
            entry(ValueChoiceOneOfType.ACCOUNT_VALUE.protoOrdinal(), ACCOUNT),
            entry(ValueChoiceOneOfType.ACCOUNT_ID_VALUE.protoOrdinal(), ACCOUNT_ID),
            entry(ValueChoiceOneOfType.BYTECODE_VALUE.protoOrdinal(), BYTECODE),
            entry(ValueChoiceOneOfType.FILE_VALUE.protoOrdinal(), FILE),
            entry(ValueChoiceOneOfType.NFT_VALUE.protoOrdinal(), NFT),
            entry(ValueChoiceOneOfType.PROTO_STRING_VALUE.protoOrdinal(), PROTO_STRING),
            entry(ValueChoiceOneOfType.SCHEDULE_VALUE.protoOrdinal(), SCHEDULE),
            entry(ValueChoiceOneOfType.SCHEDULE_LIST_VALUE.protoOrdinal(), SCHEDULE_LIST),
            entry(ValueChoiceOneOfType.SLOT_VALUE_VALUE.protoOrdinal(), SLOT_VALUE),
            entry(ValueChoiceOneOfType.STAKING_NODE_INFO_VALUE.protoOrdinal(), STAKING_NODE_INFO),
            entry(ValueChoiceOneOfType.TOKEN_VALUE.protoOrdinal(), TOKEN),
            entry(ValueChoiceOneOfType.TOKEN_RELATION_VALUE.protoOrdinal(), TOKEN_RELATION),
            entry(ValueChoiceOneOfType.TOPIC_VALUE.protoOrdinal(), TOPIC),
            entry(ValueChoiceOneOfType.NODE_VALUE.protoOrdinal(), NODE),
            entry(ValueChoiceOneOfType.ACCOUNT_PENDING_AIRDROP_VALUE.protoOrdinal(), ACCOUNT_PENDING_AIRDROP),
            entry(ValueChoiceOneOfType.ROSTER_VALUE.protoOrdinal(), ROSTER),
            entry(ValueChoiceOneOfType.SCHEDULED_COUNTS_VALUE.protoOrdinal(), SCHEDULED_COUNTS),
            entry(ValueChoiceOneOfType.SCHEDULE_ID_VALUE.protoOrdinal(), SCHEDULE_ID),
            entry(ValueChoiceOneOfType.THROTTLE_USAGE_SNAPSHOTS_VALUE.protoOrdinal(), THROTTLE_USAGE_SNAPSHOTS),
            entry(ValueChoiceOneOfType.TSS_ENCRYPTION_KEYS_VALUE.protoOrdinal(), TSS_ENCRYPTION_KEYS),
            entry(ValueChoiceOneOfType.TSS_MESSAGE_VALUE.protoOrdinal(), TSS_MESSAGE_TRANSACTION_BODY),
            entry(ValueChoiceOneOfType.TSS_VOTE_VALUE.protoOrdinal(), TSS_VOTE_TRANSACTION_BODY),
            entry(ValueChoiceOneOfType.HINTS_KEY_SET_VALUE.protoOrdinal(), HINTS_KEY_SET),
            entry(ValueChoiceOneOfType.PREPROCESSING_VOTE_VALUE.protoOrdinal(), PREPROCESSING_VOTE),
            entry(ValueChoiceOneOfType.CRS_PUBLICATION_VALUE.protoOrdinal(), CRS_PUBLICATION_TRANSACTION_BODY),
            entry(ValueChoiceOneOfType.HISTORY_SIGNATURE_VALUE.protoOrdinal(), RECORDED_HISTORY_SIGNATURE),
            entry(ValueChoiceOneOfType.HISTORY_PROOF_VOTE_VALUE.protoOrdinal(), HISTORY_PROOF_VOTE),
            entry(ValueChoiceOneOfType.PROOF_KEY_SET_VALUE.protoOrdinal(), PROOF_KEY_SET),
            entry(ValueChoiceOneOfType.EVM_HOOK_STATE_VALUE.protoOrdinal(), EVM_HOOK_STATE),
            entry(ValueChoiceOneOfType.NODE_ID_VALUE.protoOrdinal(), NODE_ID),
            entry(ValueChoiceOneOfType.WRAPS_MESSAGE_HISTORY_VALUE.protoOrdinal(), WRAPS_MESSAGE_HISTORY),
            entry(ValueChoiceOneOfType.REGISTERED_NODE_VALUE.protoOrdinal(), REGISTERED_NODE),
            entry(ValueChoiceOneOfType.CLPR_CHANNEL_VALUE.protoOrdinal(), CLPR_CHANNEL),
            entry(ValueChoiceOneOfType.PROTO_BYTES_VALUE.protoOrdinal(), PROTO_BYTES),
            entry(ValueChoiceOneOfType.CLPR_MESSAGE_VALUE.protoOrdinal(), CLPR_MESSAGE_VALUE),
            entry(ValueChoiceOneOfType.CLPR_CONNECTOR_VALUE.protoOrdinal(), CLPR_CONNECTOR));

    private static final Map<Integer, ValueType> SINGLETON_TYPES = Map.ofEntries(
            entry(NewValueOneOfType.BLOCK_INFO_VALUE.protoOrdinal(), BLOCK_INFO),
            entry(NewValueOneOfType.CONGESTION_LEVEL_STARTS_VALUE.protoOrdinal(), CONGESTION_LEVEL_STARTS),
            entry(NewValueOneOfType.ENTITY_NUMBER_VALUE.protoOrdinal(), ENTITY_NUMBER),
            entry(NewValueOneOfType.EXCHANGE_RATE_SET_VALUE.protoOrdinal(), EXCHANGE_RATE_SET),
            entry(NewValueOneOfType.NETWORK_STAKING_REWARDS_VALUE.protoOrdinal(), NETWORK_STAKING_REWARDS),
            entry(NewValueOneOfType.BYTES_VALUE.protoOrdinal(), PROTO_BYTES),
            entry(NewValueOneOfType.STRING_VALUE.protoOrdinal(), PROTO_STRING),
            entry(NewValueOneOfType.RUNNING_HASHES_VALUE.protoOrdinal(), RUNNING_HASHES),
            entry(NewValueOneOfType.THROTTLE_USAGE_SNAPSHOTS_VALUE.protoOrdinal(), THROTTLE_USAGE_SNAPSHOTS),
            entry(NewValueOneOfType.TIMESTAMP_VALUE.protoOrdinal(), TIMESTAMP),
            entry(NewValueOneOfType.BLOCK_STREAM_INFO_VALUE.protoOrdinal(), BLOCK_STREAM_INFO),
            entry(NewValueOneOfType.PLATFORM_STATE_VALUE.protoOrdinal(), PLATFORM_STATE),
            entry(NewValueOneOfType.ROSTER_STATE_VALUE.protoOrdinal(), ROSTER_STATE),
            entry(NewValueOneOfType.HINTS_CONSTRUCTION_VALUE.protoOrdinal(), HINTS_CONSTRUCTION),
            entry(NewValueOneOfType.ENTITY_COUNTS_VALUE.protoOrdinal(), ENTITY_COUNTS),
            entry(NewValueOneOfType.HISTORY_PROOF_CONSTRUCTION_VALUE.protoOrdinal(), HISTORY_PROOF_CONSTRUCTION),
            entry(NewValueOneOfType.CRS_STATE_VALUE.protoOrdinal(), CRS_STATE),
            entry(NewValueOneOfType.NODE_REWARDS_VALUE.protoOrdinal(), NODE_REWARDS),
            entry(NewValueOneOfType.NODE_PAYMENTS_VALUE.protoOrdinal(), NODE_PAYMENTS),
            entry(NewValueOneOfType.NODE_ID_VALUE.protoOrdinal(), NODE_ID),
            entry(NewValueOneOfType.CLPR_LEDGER_CONFIGURATION_VALUE.protoOrdinal(), CLPR_LEDGER_CONFIGURATION),
            entry(NewValueOneOfType.CLPR_ENDPOINT_MANIFEST_VALUE.protoOrdinal(), CLPR_ENDPOINT_MANIFEST),
            entry(
                    NewValueOneOfType.CLPR_ENDPOINT_MANIFEST_CONSTRUCTION_VALUE.protoOrdinal(),
                    CLPR_ENDPOINT_MANIFEST_CONSTRUCTION));

    static ValueType forValue(Object value) {
        return switch (value) {
            case AccountID ignored -> ACCOUNT_ID;
            case ScheduleID ignored -> SCHEDULE_ID;
            case Timestamp ignored -> TIMESTAMP;
            case Node ignored -> NODE;
            case RegisteredNode ignored -> REGISTERED_NODE;
            case BlockInfo ignored -> BLOCK_INFO;
            case RunningHashes ignored -> RUNNING_HASHES;
            case BlockStreamInfo ignored -> BLOCK_STREAM_INFO;
            case ClprChannel ignored -> CLPR_CHANNEL;
            case ClprConnector ignored -> CLPR_CONNECTOR;
            case ClprEndpointManifest ignored -> CLPR_ENDPOINT_MANIFEST;
            case ClprEndpointManifestConstruction ignored -> CLPR_ENDPOINT_MANIFEST_CONSTRUCTION;
            case ClprLedgerConfiguration ignored -> CLPR_LEDGER_CONFIGURATION;
            case ClprMessageValue ignored -> CLPR_MESSAGE_VALUE;
            case EntityNumber ignored -> ENTITY_NUMBER;
            case CongestionLevelStarts ignored -> CONGESTION_LEVEL_STARTS;
            case Topic ignored -> TOPIC;
            case Bytecode ignored -> BYTECODE;
            case SlotValue ignored -> SLOT_VALUE;
            case EntityCounts ignored -> ENTITY_COUNTS;
            case File ignored -> FILE;
            case CRSState ignored -> CRS_STATE;
            case HintsConstruction ignored -> HINTS_CONSTRUCTION;
            case HintsKeySet ignored -> HINTS_KEY_SET;
            case PreprocessingVote ignored -> PREPROCESSING_VOTE;
            case HistoryProofConstruction ignored -> HISTORY_PROOF_CONSTRUCTION;
            case HistoryProofVote ignored -> HISTORY_PROOF_VOTE;
            case ProofKeySet ignored -> PROOF_KEY_SET;
            case RecordedHistorySignature ignored -> RECORDED_HISTORY_SIGNATURE;
            case WrapsMessageHistory ignored -> WRAPS_MESSAGE_HISTORY;
            case EvmHookState ignored -> EVM_HOOK_STATE;
            case ProtoBytes ignored -> PROTO_BYTES;
            case ProtoString ignored -> PROTO_STRING;
            case Roster ignored -> ROSTER;
            case RosterState ignored -> ROSTER_STATE;
            case Schedule ignored -> SCHEDULE;
            case ScheduleList ignored -> SCHEDULE_LIST;
            case ScheduledCounts ignored -> SCHEDULED_COUNTS;
            case ThrottleUsageSnapshots ignored -> THROTTLE_USAGE_SNAPSHOTS;
            case Account ignored -> ACCOUNT;
            case AccountPendingAirdrop ignored -> ACCOUNT_PENDING_AIRDROP;
            case NetworkStakingRewards ignored -> NETWORK_STAKING_REWARDS;
            case Nft ignored -> NFT;
            case NodePayments ignored -> NODE_PAYMENTS;
            case NodeRewards ignored -> NODE_REWARDS;
            case StakingNodeInfo ignored -> STAKING_NODE_INFO;
            case Token ignored -> TOKEN;
            case TokenRelation ignored -> TOKEN_RELATION;
            case TssEncryptionKeys ignored -> TSS_ENCRYPTION_KEYS;
            case ExchangeRateSet ignored -> EXCHANGE_RATE_SET;
            case NodeId ignored -> NODE_ID;
            case PlatformState ignored -> PLATFORM_STATE;
            case CrsPublicationTransactionBody ignored -> CRS_PUBLICATION_TRANSACTION_BODY;
            case TssMessageTransactionBody ignored -> TSS_MESSAGE_TRANSACTION_BODY;
            case TssVoteTransactionBody ignored -> TSS_VOTE_TRANSACTION_BODY;
            case null, default -> null;
        };
    }

    static ValueType mapType(int field) {
        return MAP_TYPES.get(field);
    }

    static ValueType singletonType(int field) {
        return SINGLETON_TYPES.get(field);
    }

    static List<ValueType> types() {
        return TYPES;
    }

    private static <T> ValueType type(Class<T> javaType, Codec<T> codec) {
        return new ValueType(javaType.getName(), javaType, codec);
    }
}
