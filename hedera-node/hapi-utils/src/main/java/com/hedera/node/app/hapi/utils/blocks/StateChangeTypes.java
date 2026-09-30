// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas.ValueType;
import java.util.List;

/** Block-stream payload adapters. Field comparison, masks, sizing and object replay belong to PBJ. */
final class StateChangeTypes {
    private StateChangeTypes() {}

    static final ValueType ACCOUNT_ID = new ValueType(
            "com.hedera.hapi.node.base.AccountID",
            com.hedera.hapi.node.base.AccountID.class,
            com.hedera.hapi.node.base.AccountID.PROTOBUF,
            com.hedera.hapi.node.base.schema.AccountIDSchema::getField,
            field -> switch (field) {
                case 3 -> "account";
                case 4 -> "account";
                default -> "";
            },
            com.hedera.hapi.node.base.AccountID::diff,
            com.hedera.hapi.node.base.AccountID::partial,
            com.hedera.hapi.node.base.AccountID::clearedFields,
            com.hedera.hapi.node.base.AccountID::worthPartial,
            com.hedera.hapi.node.base.AccountID::applyPartial);

    static final ValueType SCHEDULE_ID = new ValueType(
            "com.hedera.hapi.node.base.ScheduleID",
            com.hedera.hapi.node.base.ScheduleID.class,
            com.hedera.hapi.node.base.ScheduleID.PROTOBUF,
            com.hedera.hapi.node.base.schema.ScheduleIDSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.base.ScheduleID::diff,
            com.hedera.hapi.node.base.ScheduleID::partial,
            com.hedera.hapi.node.base.ScheduleID::clearedFields,
            com.hedera.hapi.node.base.ScheduleID::worthPartial,
            com.hedera.hapi.node.base.ScheduleID::applyPartial);

    static final ValueType TIMESTAMP = new ValueType(
            "com.hedera.hapi.node.base.Timestamp",
            com.hedera.hapi.node.base.Timestamp.class,
            com.hedera.hapi.node.base.Timestamp.PROTOBUF,
            com.hedera.hapi.node.base.schema.TimestampSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.base.Timestamp::diff,
            com.hedera.hapi.node.base.Timestamp::partial,
            com.hedera.hapi.node.base.Timestamp::clearedFields,
            com.hedera.hapi.node.base.Timestamp::worthPartial,
            com.hedera.hapi.node.base.Timestamp::applyPartial);

    static final ValueType NODE = new ValueType(
            "com.hedera.hapi.node.state.addressbook.Node",
            com.hedera.hapi.node.state.addressbook.Node.class,
            com.hedera.hapi.node.state.addressbook.Node.PROTOBUF,
            com.hedera.hapi.node.state.addressbook.schema.NodeSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.addressbook.Node::diff,
            com.hedera.hapi.node.state.addressbook.Node::partial,
            com.hedera.hapi.node.state.addressbook.Node::clearedFields,
            com.hedera.hapi.node.state.addressbook.Node::worthPartial,
            com.hedera.hapi.node.state.addressbook.Node::applyPartial);

    static final ValueType REGISTERED_NODE = new ValueType(
            "com.hedera.hapi.node.state.addressbook.RegisteredNode",
            com.hedera.hapi.node.state.addressbook.RegisteredNode.class,
            com.hedera.hapi.node.state.addressbook.RegisteredNode.PROTOBUF,
            com.hedera.hapi.node.state.addressbook.schema.RegisteredNodeSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.addressbook.RegisteredNode::diff,
            com.hedera.hapi.node.state.addressbook.RegisteredNode::partial,
            com.hedera.hapi.node.state.addressbook.RegisteredNode::clearedFields,
            com.hedera.hapi.node.state.addressbook.RegisteredNode::worthPartial,
            com.hedera.hapi.node.state.addressbook.RegisteredNode::applyPartial);

    static final ValueType BLOCK_INFO = new ValueType(
            "com.hedera.hapi.node.state.blockrecords.BlockInfo",
            com.hedera.hapi.node.state.blockrecords.BlockInfo.class,
            com.hedera.hapi.node.state.blockrecords.BlockInfo.PROTOBUF,
            com.hedera.hapi.node.state.blockrecords.schema.BlockInfoSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.blockrecords.BlockInfo::diff,
            com.hedera.hapi.node.state.blockrecords.BlockInfo::partial,
            com.hedera.hapi.node.state.blockrecords.BlockInfo::clearedFields,
            com.hedera.hapi.node.state.blockrecords.BlockInfo::worthPartial,
            com.hedera.hapi.node.state.blockrecords.BlockInfo::applyPartial);

    static final ValueType RUNNING_HASHES = new ValueType(
            "com.hedera.hapi.node.state.blockrecords.RunningHashes",
            com.hedera.hapi.node.state.blockrecords.RunningHashes.class,
            com.hedera.hapi.node.state.blockrecords.RunningHashes.PROTOBUF,
            com.hedera.hapi.node.state.blockrecords.schema.RunningHashesSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.blockrecords.RunningHashes::diff,
            com.hedera.hapi.node.state.blockrecords.RunningHashes::partial,
            com.hedera.hapi.node.state.blockrecords.RunningHashes::clearedFields,
            com.hedera.hapi.node.state.blockrecords.RunningHashes::worthPartial,
            com.hedera.hapi.node.state.blockrecords.RunningHashes::applyPartial);

    static final ValueType BLOCK_STREAM_INFO = new ValueType(
            "com.hedera.hapi.node.state.blockstream.BlockStreamInfo",
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo.class,
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo.PROTOBUF,
            com.hedera.hapi.node.state.blockstream.schema.BlockStreamInfoSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo::diff,
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo::partial,
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo::clearedFields,
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo::worthPartial,
            com.hedera.hapi.node.state.blockstream.BlockStreamInfo::applyPartial);

    static final ValueType CLPR_CHANNEL = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprChannel",
            com.hedera.hapi.node.state.clpr.ClprChannel.class,
            com.hedera.hapi.node.state.clpr.ClprChannel.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprChannelSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprChannel::diff,
            com.hedera.hapi.node.state.clpr.ClprChannel::partial,
            com.hedera.hapi.node.state.clpr.ClprChannel::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprChannel::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprChannel::applyPartial);

    static final ValueType CLPR_CONNECTOR = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprConnector",
            com.hedera.hapi.node.state.clpr.ClprConnector.class,
            com.hedera.hapi.node.state.clpr.ClprConnector.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprConnectorSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprConnector::diff,
            com.hedera.hapi.node.state.clpr.ClprConnector::partial,
            com.hedera.hapi.node.state.clpr.ClprConnector::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprConnector::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprConnector::applyPartial);

    static final ValueType CLPR_ENDPOINT_MANIFEST = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprEndpointManifest",
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest.class,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprEndpointManifestSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest::diff,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest::partial,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifest::applyPartial);

    static final ValueType CLPR_ENDPOINT_MANIFEST_CONSTRUCTION = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction",
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction.class,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprEndpointManifestConstructionSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction::diff,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction::partial,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction::applyPartial);

    static final ValueType CLPR_LEDGER_CONFIGURATION = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration",
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.class,
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprLedgerConfigurationSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration::diff,
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration::partial,
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration::applyPartial);

    static final ValueType CLPR_MESSAGE_VALUE = new ValueType(
            "com.hedera.hapi.node.state.clpr.ClprMessageValue",
            com.hedera.hapi.node.state.clpr.ClprMessageValue.class,
            com.hedera.hapi.node.state.clpr.ClprMessageValue.PROTOBUF,
            com.hedera.hapi.node.state.clpr.schema.ClprMessageValueSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.clpr.ClprMessageValue::diff,
            com.hedera.hapi.node.state.clpr.ClprMessageValue::partial,
            com.hedera.hapi.node.state.clpr.ClprMessageValue::clearedFields,
            com.hedera.hapi.node.state.clpr.ClprMessageValue::worthPartial,
            com.hedera.hapi.node.state.clpr.ClprMessageValue::applyPartial);

    static final ValueType ENTITY_NUMBER = new ValueType(
            "com.hedera.hapi.node.state.common.EntityNumber",
            com.hedera.hapi.node.state.common.EntityNumber.class,
            com.hedera.hapi.node.state.common.EntityNumber.PROTOBUF,
            com.hedera.hapi.node.state.common.schema.EntityNumberSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.common.EntityNumber::diff,
            com.hedera.hapi.node.state.common.EntityNumber::partial,
            com.hedera.hapi.node.state.common.EntityNumber::clearedFields,
            com.hedera.hapi.node.state.common.EntityNumber::worthPartial,
            com.hedera.hapi.node.state.common.EntityNumber::applyPartial);

    static final ValueType CONGESTION_LEVEL_STARTS = new ValueType(
            "com.hedera.hapi.node.state.congestion.CongestionLevelStarts",
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts.class,
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts.PROTOBUF,
            com.hedera.hapi.node.state.congestion.schema.CongestionLevelStartsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts::diff,
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts::partial,
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts::clearedFields,
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts::worthPartial,
            com.hedera.hapi.node.state.congestion.CongestionLevelStarts::applyPartial);

    static final ValueType TOPIC = new ValueType(
            "com.hedera.hapi.node.state.consensus.Topic",
            com.hedera.hapi.node.state.consensus.Topic.class,
            com.hedera.hapi.node.state.consensus.Topic.PROTOBUF,
            com.hedera.hapi.node.state.consensus.schema.TopicSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.consensus.Topic::diff,
            com.hedera.hapi.node.state.consensus.Topic::partial,
            com.hedera.hapi.node.state.consensus.Topic::clearedFields,
            com.hedera.hapi.node.state.consensus.Topic::worthPartial,
            com.hedera.hapi.node.state.consensus.Topic::applyPartial);

    static final ValueType BYTECODE = new ValueType(
            "com.hedera.hapi.node.state.contract.Bytecode",
            com.hedera.hapi.node.state.contract.Bytecode.class,
            com.hedera.hapi.node.state.contract.Bytecode.PROTOBUF,
            com.hedera.hapi.node.state.contract.schema.BytecodeSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.contract.Bytecode::diff,
            com.hedera.hapi.node.state.contract.Bytecode::partial,
            com.hedera.hapi.node.state.contract.Bytecode::clearedFields,
            com.hedera.hapi.node.state.contract.Bytecode::worthPartial,
            com.hedera.hapi.node.state.contract.Bytecode::applyPartial);

    static final ValueType SLOT_VALUE = new ValueType(
            "com.hedera.hapi.node.state.contract.SlotValue",
            com.hedera.hapi.node.state.contract.SlotValue.class,
            com.hedera.hapi.node.state.contract.SlotValue.PROTOBUF,
            com.hedera.hapi.node.state.contract.schema.SlotValueSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.contract.SlotValue::diff,
            com.hedera.hapi.node.state.contract.SlotValue::partial,
            com.hedera.hapi.node.state.contract.SlotValue::clearedFields,
            com.hedera.hapi.node.state.contract.SlotValue::worthPartial,
            com.hedera.hapi.node.state.contract.SlotValue::applyPartial);

    static final ValueType ENTITY_COUNTS = new ValueType(
            "com.hedera.hapi.node.state.entity.EntityCounts",
            com.hedera.hapi.node.state.entity.EntityCounts.class,
            com.hedera.hapi.node.state.entity.EntityCounts.PROTOBUF,
            com.hedera.hapi.node.state.entity.schema.EntityCountsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.entity.EntityCounts::diff,
            com.hedera.hapi.node.state.entity.EntityCounts::partial,
            com.hedera.hapi.node.state.entity.EntityCounts::clearedFields,
            com.hedera.hapi.node.state.entity.EntityCounts::worthPartial,
            com.hedera.hapi.node.state.entity.EntityCounts::applyPartial);

    static final ValueType FILE = new ValueType(
            "com.hedera.hapi.node.state.file.File",
            com.hedera.hapi.node.state.file.File.class,
            com.hedera.hapi.node.state.file.File.PROTOBUF,
            com.hedera.hapi.node.state.file.schema.FileSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.file.File::diff,
            com.hedera.hapi.node.state.file.File::partial,
            com.hedera.hapi.node.state.file.File::clearedFields,
            com.hedera.hapi.node.state.file.File::worthPartial,
            com.hedera.hapi.node.state.file.File::applyPartial);

    static final ValueType CRS_STATE = new ValueType(
            "com.hedera.hapi.node.state.hints.CRSState",
            com.hedera.hapi.node.state.hints.CRSState.class,
            com.hedera.hapi.node.state.hints.CRSState.PROTOBUF,
            com.hedera.hapi.node.state.hints.schema.CRSStateSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.hints.CRSState::diff,
            com.hedera.hapi.node.state.hints.CRSState::partial,
            com.hedera.hapi.node.state.hints.CRSState::clearedFields,
            com.hedera.hapi.node.state.hints.CRSState::worthPartial,
            com.hedera.hapi.node.state.hints.CRSState::applyPartial);

    static final ValueType HINTS_CONSTRUCTION = new ValueType(
            "com.hedera.hapi.node.state.hints.HintsConstruction",
            com.hedera.hapi.node.state.hints.HintsConstruction.class,
            com.hedera.hapi.node.state.hints.HintsConstruction.PROTOBUF,
            com.hedera.hapi.node.state.hints.schema.HintsConstructionSchema::getField,
            field -> switch (field) {
                case 4 -> "preprocessingState";
                case 5 -> "preprocessingState";
                case 6 -> "preprocessingState";
                default -> "";
            },
            com.hedera.hapi.node.state.hints.HintsConstruction::diff,
            com.hedera.hapi.node.state.hints.HintsConstruction::partial,
            com.hedera.hapi.node.state.hints.HintsConstruction::clearedFields,
            com.hedera.hapi.node.state.hints.HintsConstruction::worthPartial,
            com.hedera.hapi.node.state.hints.HintsConstruction::applyPartial);

    static final ValueType HINTS_KEY_SET = new ValueType(
            "com.hedera.hapi.node.state.hints.HintsKeySet",
            com.hedera.hapi.node.state.hints.HintsKeySet.class,
            com.hedera.hapi.node.state.hints.HintsKeySet.PROTOBUF,
            com.hedera.hapi.node.state.hints.schema.HintsKeySetSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.hints.HintsKeySet::diff,
            com.hedera.hapi.node.state.hints.HintsKeySet::partial,
            com.hedera.hapi.node.state.hints.HintsKeySet::clearedFields,
            com.hedera.hapi.node.state.hints.HintsKeySet::worthPartial,
            com.hedera.hapi.node.state.hints.HintsKeySet::applyPartial);

    static final ValueType PREPROCESSING_VOTE = new ValueType(
            "com.hedera.hapi.node.state.hints.PreprocessingVote",
            com.hedera.hapi.node.state.hints.PreprocessingVote.class,
            com.hedera.hapi.node.state.hints.PreprocessingVote.PROTOBUF,
            com.hedera.hapi.node.state.hints.schema.PreprocessingVoteSchema::getField,
            field -> switch (field) {
                case 1 -> "vote";
                case 2 -> "vote";
                default -> "";
            },
            com.hedera.hapi.node.state.hints.PreprocessingVote::diff,
            com.hedera.hapi.node.state.hints.PreprocessingVote::partial,
            com.hedera.hapi.node.state.hints.PreprocessingVote::clearedFields,
            com.hedera.hapi.node.state.hints.PreprocessingVote::worthPartial,
            com.hedera.hapi.node.state.hints.PreprocessingVote::applyPartial);

    static final ValueType HISTORY_PROOF_CONSTRUCTION = new ValueType(
            "com.hedera.hapi.node.state.history.HistoryProofConstruction",
            com.hedera.hapi.node.state.history.HistoryProofConstruction.class,
            com.hedera.hapi.node.state.history.HistoryProofConstruction.PROTOBUF,
            com.hedera.hapi.node.state.history.schema.HistoryProofConstructionSchema::getField,
            field -> switch (field) {
                case 5 -> "proofState";
                case 6 -> "proofState";
                case 7 -> "proofState";
                case 8 -> "proofState";
                case 9 -> "proofState";
                default -> "";
            },
            com.hedera.hapi.node.state.history.HistoryProofConstruction::diff,
            com.hedera.hapi.node.state.history.HistoryProofConstruction::partial,
            com.hedera.hapi.node.state.history.HistoryProofConstruction::clearedFields,
            com.hedera.hapi.node.state.history.HistoryProofConstruction::worthPartial,
            com.hedera.hapi.node.state.history.HistoryProofConstruction::applyPartial);

    static final ValueType HISTORY_PROOF_VOTE = new ValueType(
            "com.hedera.hapi.node.state.history.HistoryProofVote",
            com.hedera.hapi.node.state.history.HistoryProofVote.class,
            com.hedera.hapi.node.state.history.HistoryProofVote.PROTOBUF,
            com.hedera.hapi.node.state.history.schema.HistoryProofVoteSchema::getField,
            field -> switch (field) {
                case 1 -> "vote";
                case 2 -> "vote";
                default -> "";
            },
            com.hedera.hapi.node.state.history.HistoryProofVote::diff,
            com.hedera.hapi.node.state.history.HistoryProofVote::partial,
            com.hedera.hapi.node.state.history.HistoryProofVote::clearedFields,
            com.hedera.hapi.node.state.history.HistoryProofVote::worthPartial,
            com.hedera.hapi.node.state.history.HistoryProofVote::applyPartial);

    static final ValueType PROOF_KEY_SET = new ValueType(
            "com.hedera.hapi.node.state.history.ProofKeySet",
            com.hedera.hapi.node.state.history.ProofKeySet.class,
            com.hedera.hapi.node.state.history.ProofKeySet.PROTOBUF,
            com.hedera.hapi.node.state.history.schema.ProofKeySetSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.history.ProofKeySet::diff,
            com.hedera.hapi.node.state.history.ProofKeySet::partial,
            com.hedera.hapi.node.state.history.ProofKeySet::clearedFields,
            com.hedera.hapi.node.state.history.ProofKeySet::worthPartial,
            com.hedera.hapi.node.state.history.ProofKeySet::applyPartial);

    static final ValueType RECORDED_HISTORY_SIGNATURE = new ValueType(
            "com.hedera.hapi.node.state.history.RecordedHistorySignature",
            com.hedera.hapi.node.state.history.RecordedHistorySignature.class,
            com.hedera.hapi.node.state.history.RecordedHistorySignature.PROTOBUF,
            com.hedera.hapi.node.state.history.schema.RecordedHistorySignatureSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.history.RecordedHistorySignature::diff,
            com.hedera.hapi.node.state.history.RecordedHistorySignature::partial,
            com.hedera.hapi.node.state.history.RecordedHistorySignature::clearedFields,
            com.hedera.hapi.node.state.history.RecordedHistorySignature::worthPartial,
            com.hedera.hapi.node.state.history.RecordedHistorySignature::applyPartial);

    static final ValueType WRAPS_MESSAGE_HISTORY = new ValueType(
            "com.hedera.hapi.node.state.history.WrapsMessageHistory",
            com.hedera.hapi.node.state.history.WrapsMessageHistory.class,
            com.hedera.hapi.node.state.history.WrapsMessageHistory.PROTOBUF,
            com.hedera.hapi.node.state.history.schema.WrapsMessageHistorySchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.history.WrapsMessageHistory::diff,
            com.hedera.hapi.node.state.history.WrapsMessageHistory::partial,
            com.hedera.hapi.node.state.history.WrapsMessageHistory::clearedFields,
            com.hedera.hapi.node.state.history.WrapsMessageHistory::worthPartial,
            com.hedera.hapi.node.state.history.WrapsMessageHistory::applyPartial);

    static final ValueType EVM_HOOK_STATE = new ValueType(
            "com.hedera.hapi.node.state.hooks.EvmHookState",
            com.hedera.hapi.node.state.hooks.EvmHookState.class,
            com.hedera.hapi.node.state.hooks.EvmHookState.PROTOBUF,
            com.hedera.hapi.node.state.hooks.schema.EvmHookStateSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.hooks.EvmHookState::diff,
            com.hedera.hapi.node.state.hooks.EvmHookState::partial,
            com.hedera.hapi.node.state.hooks.EvmHookState::clearedFields,
            com.hedera.hapi.node.state.hooks.EvmHookState::worthPartial,
            com.hedera.hapi.node.state.hooks.EvmHookState::applyPartial);

    static final ValueType PROTO_BYTES = new ValueType(
            "com.hedera.hapi.node.state.primitives.ProtoBytes",
            com.hedera.hapi.node.state.primitives.ProtoBytes.class,
            com.hedera.hapi.node.state.primitives.ProtoBytes.PROTOBUF,
            com.hedera.hapi.node.state.primitives.schema.ProtoBytesSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.primitives.ProtoBytes::diff,
            com.hedera.hapi.node.state.primitives.ProtoBytes::partial,
            com.hedera.hapi.node.state.primitives.ProtoBytes::clearedFields,
            com.hedera.hapi.node.state.primitives.ProtoBytes::worthPartial,
            com.hedera.hapi.node.state.primitives.ProtoBytes::applyPartial);

    static final ValueType PROTO_STRING = new ValueType(
            "com.hedera.hapi.node.state.primitives.ProtoString",
            com.hedera.hapi.node.state.primitives.ProtoString.class,
            com.hedera.hapi.node.state.primitives.ProtoString.PROTOBUF,
            com.hedera.hapi.node.state.primitives.schema.ProtoStringSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.primitives.ProtoString::diff,
            com.hedera.hapi.node.state.primitives.ProtoString::partial,
            com.hedera.hapi.node.state.primitives.ProtoString::clearedFields,
            com.hedera.hapi.node.state.primitives.ProtoString::worthPartial,
            com.hedera.hapi.node.state.primitives.ProtoString::applyPartial);

    static final ValueType ROSTER = new ValueType(
            "com.hedera.hapi.node.state.roster.Roster",
            com.hedera.hapi.node.state.roster.Roster.class,
            com.hedera.hapi.node.state.roster.Roster.PROTOBUF,
            com.hedera.hapi.node.state.roster.schema.RosterSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.roster.Roster::diff,
            com.hedera.hapi.node.state.roster.Roster::partial,
            com.hedera.hapi.node.state.roster.Roster::clearedFields,
            com.hedera.hapi.node.state.roster.Roster::worthPartial,
            com.hedera.hapi.node.state.roster.Roster::applyPartial);

    static final ValueType ROSTER_STATE = new ValueType(
            "com.hedera.hapi.node.state.roster.RosterState",
            com.hedera.hapi.node.state.roster.RosterState.class,
            com.hedera.hapi.node.state.roster.RosterState.PROTOBUF,
            com.hedera.hapi.node.state.roster.schema.RosterStateSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.roster.RosterState::diff,
            com.hedera.hapi.node.state.roster.RosterState::partial,
            com.hedera.hapi.node.state.roster.RosterState::clearedFields,
            com.hedera.hapi.node.state.roster.RosterState::worthPartial,
            com.hedera.hapi.node.state.roster.RosterState::applyPartial);

    static final ValueType SCHEDULE = new ValueType(
            "com.hedera.hapi.node.state.schedule.Schedule",
            com.hedera.hapi.node.state.schedule.Schedule.class,
            com.hedera.hapi.node.state.schedule.Schedule.PROTOBUF,
            com.hedera.hapi.node.state.schedule.schema.ScheduleSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.schedule.Schedule::diff,
            com.hedera.hapi.node.state.schedule.Schedule::partial,
            com.hedera.hapi.node.state.schedule.Schedule::clearedFields,
            com.hedera.hapi.node.state.schedule.Schedule::worthPartial,
            com.hedera.hapi.node.state.schedule.Schedule::applyPartial);

    static final ValueType SCHEDULE_LIST = new ValueType(
            "com.hedera.hapi.node.state.schedule.ScheduleList",
            com.hedera.hapi.node.state.schedule.ScheduleList.class,
            com.hedera.hapi.node.state.schedule.ScheduleList.PROTOBUF,
            com.hedera.hapi.node.state.schedule.schema.ScheduleListSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.schedule.ScheduleList::diff,
            com.hedera.hapi.node.state.schedule.ScheduleList::partial,
            com.hedera.hapi.node.state.schedule.ScheduleList::clearedFields,
            com.hedera.hapi.node.state.schedule.ScheduleList::worthPartial,
            com.hedera.hapi.node.state.schedule.ScheduleList::applyPartial);

    static final ValueType SCHEDULED_COUNTS = new ValueType(
            "com.hedera.hapi.node.state.schedule.ScheduledCounts",
            com.hedera.hapi.node.state.schedule.ScheduledCounts.class,
            com.hedera.hapi.node.state.schedule.ScheduledCounts.PROTOBUF,
            com.hedera.hapi.node.state.schedule.schema.ScheduledCountsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.schedule.ScheduledCounts::diff,
            com.hedera.hapi.node.state.schedule.ScheduledCounts::partial,
            com.hedera.hapi.node.state.schedule.ScheduledCounts::clearedFields,
            com.hedera.hapi.node.state.schedule.ScheduledCounts::worthPartial,
            com.hedera.hapi.node.state.schedule.ScheduledCounts::applyPartial);

    static final ValueType THROTTLE_USAGE_SNAPSHOTS = new ValueType(
            "com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots",
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots.class,
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots.PROTOBUF,
            com.hedera.hapi.node.state.throttles.schema.ThrottleUsageSnapshotsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots::diff,
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots::partial,
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots::clearedFields,
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots::worthPartial,
            com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots::applyPartial);

    static final ValueType ACCOUNT = new ValueType(
            "com.hedera.hapi.node.state.token.Account",
            com.hedera.hapi.node.state.token.Account.class,
            com.hedera.hapi.node.state.token.Account.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.AccountSchema::getField,
            field -> switch (field) {
                case 10 -> "stakedId";
                case 11 -> "stakedId";
                default -> "";
            },
            com.hedera.hapi.node.state.token.Account::diff,
            com.hedera.hapi.node.state.token.Account::partial,
            com.hedera.hapi.node.state.token.Account::clearedFields,
            com.hedera.hapi.node.state.token.Account::worthPartial,
            com.hedera.hapi.node.state.token.Account::applyPartial);

    static final ValueType ACCOUNT_PENDING_AIRDROP = new ValueType(
            "com.hedera.hapi.node.state.token.AccountPendingAirdrop",
            com.hedera.hapi.node.state.token.AccountPendingAirdrop.class,
            com.hedera.hapi.node.state.token.AccountPendingAirdrop.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.AccountPendingAirdropSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.AccountPendingAirdrop::diff,
            com.hedera.hapi.node.state.token.AccountPendingAirdrop::partial,
            com.hedera.hapi.node.state.token.AccountPendingAirdrop::clearedFields,
            com.hedera.hapi.node.state.token.AccountPendingAirdrop::worthPartial,
            com.hedera.hapi.node.state.token.AccountPendingAirdrop::applyPartial);

    static final ValueType NETWORK_STAKING_REWARDS = new ValueType(
            "com.hedera.hapi.node.state.token.NetworkStakingRewards",
            com.hedera.hapi.node.state.token.NetworkStakingRewards.class,
            com.hedera.hapi.node.state.token.NetworkStakingRewards.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.NetworkStakingRewardsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.NetworkStakingRewards::diff,
            com.hedera.hapi.node.state.token.NetworkStakingRewards::partial,
            com.hedera.hapi.node.state.token.NetworkStakingRewards::clearedFields,
            com.hedera.hapi.node.state.token.NetworkStakingRewards::worthPartial,
            com.hedera.hapi.node.state.token.NetworkStakingRewards::applyPartial);

    static final ValueType NFT = new ValueType(
            "com.hedera.hapi.node.state.token.Nft",
            com.hedera.hapi.node.state.token.Nft.class,
            com.hedera.hapi.node.state.token.Nft.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.NftSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.Nft::diff,
            com.hedera.hapi.node.state.token.Nft::partial,
            com.hedera.hapi.node.state.token.Nft::clearedFields,
            com.hedera.hapi.node.state.token.Nft::worthPartial,
            com.hedera.hapi.node.state.token.Nft::applyPartial);

    static final ValueType NODE_PAYMENTS = new ValueType(
            "com.hedera.hapi.node.state.token.NodePayments",
            com.hedera.hapi.node.state.token.NodePayments.class,
            com.hedera.hapi.node.state.token.NodePayments.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.NodePaymentsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.NodePayments::diff,
            com.hedera.hapi.node.state.token.NodePayments::partial,
            com.hedera.hapi.node.state.token.NodePayments::clearedFields,
            com.hedera.hapi.node.state.token.NodePayments::worthPartial,
            com.hedera.hapi.node.state.token.NodePayments::applyPartial);

    static final ValueType NODE_REWARDS = new ValueType(
            "com.hedera.hapi.node.state.token.NodeRewards",
            com.hedera.hapi.node.state.token.NodeRewards.class,
            com.hedera.hapi.node.state.token.NodeRewards.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.NodeRewardsSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.NodeRewards::diff,
            com.hedera.hapi.node.state.token.NodeRewards::partial,
            com.hedera.hapi.node.state.token.NodeRewards::clearedFields,
            com.hedera.hapi.node.state.token.NodeRewards::worthPartial,
            com.hedera.hapi.node.state.token.NodeRewards::applyPartial);

    static final ValueType STAKING_NODE_INFO = new ValueType(
            "com.hedera.hapi.node.state.token.StakingNodeInfo",
            com.hedera.hapi.node.state.token.StakingNodeInfo.class,
            com.hedera.hapi.node.state.token.StakingNodeInfo.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.StakingNodeInfoSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.StakingNodeInfo::diff,
            com.hedera.hapi.node.state.token.StakingNodeInfo::partial,
            com.hedera.hapi.node.state.token.StakingNodeInfo::clearedFields,
            com.hedera.hapi.node.state.token.StakingNodeInfo::worthPartial,
            com.hedera.hapi.node.state.token.StakingNodeInfo::applyPartial);

    static final ValueType TOKEN = new ValueType(
            "com.hedera.hapi.node.state.token.Token",
            com.hedera.hapi.node.state.token.Token.class,
            com.hedera.hapi.node.state.token.Token.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.TokenSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.Token::diff,
            com.hedera.hapi.node.state.token.Token::partial,
            com.hedera.hapi.node.state.token.Token::clearedFields,
            com.hedera.hapi.node.state.token.Token::worthPartial,
            com.hedera.hapi.node.state.token.Token::applyPartial);

    static final ValueType TOKEN_RELATION = new ValueType(
            "com.hedera.hapi.node.state.token.TokenRelation",
            com.hedera.hapi.node.state.token.TokenRelation.class,
            com.hedera.hapi.node.state.token.TokenRelation.PROTOBUF,
            com.hedera.hapi.node.state.token.schema.TokenRelationSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.token.TokenRelation::diff,
            com.hedera.hapi.node.state.token.TokenRelation::partial,
            com.hedera.hapi.node.state.token.TokenRelation::clearedFields,
            com.hedera.hapi.node.state.token.TokenRelation::worthPartial,
            com.hedera.hapi.node.state.token.TokenRelation::applyPartial);

    static final ValueType TSS_ENCRYPTION_KEYS = new ValueType(
            "com.hedera.hapi.node.state.tss.TssEncryptionKeys",
            com.hedera.hapi.node.state.tss.TssEncryptionKeys.class,
            com.hedera.hapi.node.state.tss.TssEncryptionKeys.PROTOBUF,
            com.hedera.hapi.node.state.tss.schema.TssEncryptionKeysSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.state.tss.TssEncryptionKeys::diff,
            com.hedera.hapi.node.state.tss.TssEncryptionKeys::partial,
            com.hedera.hapi.node.state.tss.TssEncryptionKeys::clearedFields,
            com.hedera.hapi.node.state.tss.TssEncryptionKeys::worthPartial,
            com.hedera.hapi.node.state.tss.TssEncryptionKeys::applyPartial);

    static final ValueType EXCHANGE_RATE_SET = new ValueType(
            "com.hedera.hapi.node.transaction.ExchangeRateSet",
            com.hedera.hapi.node.transaction.ExchangeRateSet.class,
            com.hedera.hapi.node.transaction.ExchangeRateSet.PROTOBUF,
            com.hedera.hapi.node.transaction.schema.ExchangeRateSetSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.node.transaction.ExchangeRateSet::diff,
            com.hedera.hapi.node.transaction.ExchangeRateSet::partial,
            com.hedera.hapi.node.transaction.ExchangeRateSet::clearedFields,
            com.hedera.hapi.node.transaction.ExchangeRateSet::worthPartial,
            com.hedera.hapi.node.transaction.ExchangeRateSet::applyPartial);

    static final ValueType NODE_ID = new ValueType(
            "com.hedera.hapi.platform.state.NodeId",
            com.hedera.hapi.platform.state.NodeId.class,
            com.hedera.hapi.platform.state.NodeId.PROTOBUF,
            com.hedera.hapi.platform.state.schema.NodeIdSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.platform.state.NodeId::diff,
            com.hedera.hapi.platform.state.NodeId::partial,
            com.hedera.hapi.platform.state.NodeId::clearedFields,
            com.hedera.hapi.platform.state.NodeId::worthPartial,
            com.hedera.hapi.platform.state.NodeId::applyPartial);

    static final ValueType PLATFORM_STATE = new ValueType(
            "com.hedera.hapi.platform.state.PlatformState",
            com.hedera.hapi.platform.state.PlatformState.class,
            com.hedera.hapi.platform.state.PlatformState.PROTOBUF,
            com.hedera.hapi.platform.state.schema.PlatformStateSchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.platform.state.PlatformState::diff,
            com.hedera.hapi.platform.state.PlatformState::partial,
            com.hedera.hapi.platform.state.PlatformState::clearedFields,
            com.hedera.hapi.platform.state.PlatformState::worthPartial,
            com.hedera.hapi.platform.state.PlatformState::applyPartial);

    static final ValueType CRS_PUBLICATION_TRANSACTION_BODY = new ValueType(
            "com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody",
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody.class,
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody.PROTOBUF,
            com.hedera.hapi.services.auxiliary.hints.schema.CrsPublicationTransactionBodySchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody::diff,
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody::partial,
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody::clearedFields,
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody::worthPartial,
            com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody::applyPartial);

    static final ValueType TSS_MESSAGE_TRANSACTION_BODY = new ValueType(
            "com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody",
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody.class,
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody.PROTOBUF,
            com.hedera.hapi.services.auxiliary.tss.schema.TssMessageTransactionBodySchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody::diff,
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody::partial,
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody::clearedFields,
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody::worthPartial,
            com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody::applyPartial);

    static final ValueType TSS_VOTE_TRANSACTION_BODY = new ValueType(
            "com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody",
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody.class,
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody.PROTOBUF,
            com.hedera.hapi.services.auxiliary.tss.schema.TssVoteTransactionBodySchema::getField,
            field -> switch (field) {
                default -> "";
            },
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody::diff,
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody::partial,
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody::clearedFields,
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody::worthPartial,
            com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody::applyPartial);

    static ValueType forValue(final Object value) {
        return switch (value) {
            case com.hedera.hapi.node.base.AccountID ignored -> ACCOUNT_ID;
            case com.hedera.hapi.node.base.ScheduleID ignored -> SCHEDULE_ID;
            case com.hedera.hapi.node.base.Timestamp ignored -> TIMESTAMP;
            case com.hedera.hapi.node.state.addressbook.Node ignored -> NODE;
            case com.hedera.hapi.node.state.addressbook.RegisteredNode ignored -> REGISTERED_NODE;
            case com.hedera.hapi.node.state.blockrecords.BlockInfo ignored -> BLOCK_INFO;
            case com.hedera.hapi.node.state.blockrecords.RunningHashes ignored -> RUNNING_HASHES;
            case com.hedera.hapi.node.state.blockstream.BlockStreamInfo ignored -> BLOCK_STREAM_INFO;
            case com.hedera.hapi.node.state.clpr.ClprChannel ignored -> CLPR_CHANNEL;
            case com.hedera.hapi.node.state.clpr.ClprConnector ignored -> CLPR_CONNECTOR;
            case com.hedera.hapi.node.state.clpr.ClprEndpointManifest ignored -> CLPR_ENDPOINT_MANIFEST;
            case com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction ignored ->
                CLPR_ENDPOINT_MANIFEST_CONSTRUCTION;
            case com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration ignored -> CLPR_LEDGER_CONFIGURATION;
            case com.hedera.hapi.node.state.clpr.ClprMessageValue ignored -> CLPR_MESSAGE_VALUE;
            case com.hedera.hapi.node.state.common.EntityNumber ignored -> ENTITY_NUMBER;
            case com.hedera.hapi.node.state.congestion.CongestionLevelStarts ignored -> CONGESTION_LEVEL_STARTS;
            case com.hedera.hapi.node.state.consensus.Topic ignored -> TOPIC;
            case com.hedera.hapi.node.state.contract.Bytecode ignored -> BYTECODE;
            case com.hedera.hapi.node.state.contract.SlotValue ignored -> SLOT_VALUE;
            case com.hedera.hapi.node.state.entity.EntityCounts ignored -> ENTITY_COUNTS;
            case com.hedera.hapi.node.state.file.File ignored -> FILE;
            case com.hedera.hapi.node.state.hints.CRSState ignored -> CRS_STATE;
            case com.hedera.hapi.node.state.hints.HintsConstruction ignored -> HINTS_CONSTRUCTION;
            case com.hedera.hapi.node.state.hints.HintsKeySet ignored -> HINTS_KEY_SET;
            case com.hedera.hapi.node.state.hints.PreprocessingVote ignored -> PREPROCESSING_VOTE;
            case com.hedera.hapi.node.state.history.HistoryProofConstruction ignored -> HISTORY_PROOF_CONSTRUCTION;
            case com.hedera.hapi.node.state.history.HistoryProofVote ignored -> HISTORY_PROOF_VOTE;
            case com.hedera.hapi.node.state.history.ProofKeySet ignored -> PROOF_KEY_SET;
            case com.hedera.hapi.node.state.history.RecordedHistorySignature ignored -> RECORDED_HISTORY_SIGNATURE;
            case com.hedera.hapi.node.state.history.WrapsMessageHistory ignored -> WRAPS_MESSAGE_HISTORY;
            case com.hedera.hapi.node.state.hooks.EvmHookState ignored -> EVM_HOOK_STATE;
            case com.hedera.hapi.node.state.primitives.ProtoBytes ignored -> PROTO_BYTES;
            case com.hedera.hapi.node.state.primitives.ProtoString ignored -> PROTO_STRING;
            case com.hedera.hapi.node.state.roster.Roster ignored -> ROSTER;
            case com.hedera.hapi.node.state.roster.RosterState ignored -> ROSTER_STATE;
            case com.hedera.hapi.node.state.schedule.Schedule ignored -> SCHEDULE;
            case com.hedera.hapi.node.state.schedule.ScheduleList ignored -> SCHEDULE_LIST;
            case com.hedera.hapi.node.state.schedule.ScheduledCounts ignored -> SCHEDULED_COUNTS;
            case com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots ignored -> THROTTLE_USAGE_SNAPSHOTS;
            case com.hedera.hapi.node.state.token.Account ignored -> ACCOUNT;
            case com.hedera.hapi.node.state.token.AccountPendingAirdrop ignored -> ACCOUNT_PENDING_AIRDROP;
            case com.hedera.hapi.node.state.token.NetworkStakingRewards ignored -> NETWORK_STAKING_REWARDS;
            case com.hedera.hapi.node.state.token.Nft ignored -> NFT;
            case com.hedera.hapi.node.state.token.NodePayments ignored -> NODE_PAYMENTS;
            case com.hedera.hapi.node.state.token.NodeRewards ignored -> NODE_REWARDS;
            case com.hedera.hapi.node.state.token.StakingNodeInfo ignored -> STAKING_NODE_INFO;
            case com.hedera.hapi.node.state.token.Token ignored -> TOKEN;
            case com.hedera.hapi.node.state.token.TokenRelation ignored -> TOKEN_RELATION;
            case com.hedera.hapi.node.state.tss.TssEncryptionKeys ignored -> TSS_ENCRYPTION_KEYS;
            case com.hedera.hapi.node.transaction.ExchangeRateSet ignored -> EXCHANGE_RATE_SET;
            case com.hedera.hapi.platform.state.NodeId ignored -> NODE_ID;
            case com.hedera.hapi.platform.state.PlatformState ignored -> PLATFORM_STATE;
            case com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody ignored ->
                CRS_PUBLICATION_TRANSACTION_BODY;
            case com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody ignored ->
                TSS_MESSAGE_TRANSACTION_BODY;
            case com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody ignored -> TSS_VOTE_TRANSACTION_BODY;
            default -> null;
        };
    }

    static ValueType mapType(final int field) {
        return switch (field) {
            case 1 -> ACCOUNT;
            case 2 -> ACCOUNT_ID;
            case 3 -> BYTECODE;
            case 4 -> FILE;
            case 5 -> NFT;
            case 6 -> PROTO_STRING;
            case 7 -> SCHEDULE;
            case 8 -> SCHEDULE_LIST;
            case 9 -> SLOT_VALUE;
            case 10 -> STAKING_NODE_INFO;
            case 11 -> TOKEN;
            case 12 -> TOKEN_RELATION;
            case 13 -> TOPIC;
            case 14 -> NODE;
            case 15 -> ACCOUNT_PENDING_AIRDROP;
            case 16 -> ROSTER;
            case 17 -> SCHEDULED_COUNTS;
            case 18 -> SCHEDULE_ID;
            case 19 -> THROTTLE_USAGE_SNAPSHOTS;
            case 20 -> TSS_ENCRYPTION_KEYS;
            case 21 -> TSS_MESSAGE_TRANSACTION_BODY;
            case 22 -> TSS_VOTE_TRANSACTION_BODY;
            case 23 -> HINTS_KEY_SET;
            case 24 -> PREPROCESSING_VOTE;
            case 25 -> CRS_PUBLICATION_TRANSACTION_BODY;
            case 26 -> RECORDED_HISTORY_SIGNATURE;
            case 27 -> HISTORY_PROOF_VOTE;
            case 28 -> PROOF_KEY_SET;
            case 29 -> EVM_HOOK_STATE;
            case 30 -> NODE_ID;
            case 31 -> WRAPS_MESSAGE_HISTORY;
            case 32 -> REGISTERED_NODE;
            case 33 -> CLPR_CHANNEL;
            case 34 -> PROTO_BYTES;
            case 35 -> CLPR_MESSAGE_VALUE;
            case 36 -> CLPR_CONNECTOR;
            default -> null;
        };
    }

    static ValueType singletonType(final int field) {
        return switch (field) {
            case 1 -> BLOCK_INFO;
            case 2 -> CONGESTION_LEVEL_STARTS;
            case 3 -> ENTITY_NUMBER;
            case 4 -> EXCHANGE_RATE_SET;
            case 5 -> NETWORK_STAKING_REWARDS;
            case 6 -> PROTO_BYTES;
            case 7 -> PROTO_STRING;
            case 8 -> RUNNING_HASHES;
            case 9 -> THROTTLE_USAGE_SNAPSHOTS;
            case 10 -> TIMESTAMP;
            case 11 -> BLOCK_STREAM_INFO;
            case 12 -> PLATFORM_STATE;
            case 13 -> ROSTER_STATE;
            case 14 -> HINTS_CONSTRUCTION;
            case 15 -> ENTITY_COUNTS;
            case 16 -> HISTORY_PROOF_CONSTRUCTION;
            case 17 -> CRS_STATE;
            case 18 -> NODE_REWARDS;
            case 19 -> NODE_PAYMENTS;
            case 20 -> NODE_ID;
            case 21 -> CLPR_LEDGER_CONFIGURATION;
            case 22 -> CLPR_ENDPOINT_MANIFEST;
            case 23 -> CLPR_ENDPOINT_MANIFEST_CONSTRUCTION;
            default -> null;
        };
    }

    static List<ValueType> types() {
        return List.of(
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
    }
}
