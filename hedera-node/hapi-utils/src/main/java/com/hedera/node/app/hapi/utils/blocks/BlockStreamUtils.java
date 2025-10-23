// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class BlockStreamUtils {

    private static final String UPGRADE_DATA_FILE_NUM_FORMAT = "FileService.UPGRADE_DATA_%d";

    private BlockStreamUtils() {}

    public static String stateNameOf(final int stateId) {
        return switch (StateIdentifier.fromProtobufOrdinal(stateId)) {
            case UNKNOWN -> throw new IllegalArgumentException("Unknown state identifier");
            case STATE_ID_NODES -> "AddressBookService.NODES";
            case STATE_ID_ACCOUNT_NODE_REL -> "AddressBookService.ACCOUNT_NODE_REL";
            case STATE_ID_BLOCKS -> "BlockRecordService.BLOCKS";
            case STATE_ID_RUNNING_HASHES -> "BlockRecordService.RUNNING_HASHES";
            case STATE_ID_BLOCK_STREAM_INFO -> "BlockStreamService.BLOCK_STREAM_INFO";
            case STATE_ID_CONGESTION_LEVEL_STARTS -> "CongestionThrottleService.CONGESTION_LEVEL_STARTS";
            case STATE_ID_THROTTLE_USAGE_SNAPSHOTS -> "CongestionThrottleService.THROTTLE_USAGE_SNAPSHOTS";
            case STATE_ID_TOPICS -> "ConsensusService.TOPICS";
            case STATE_ID_BYTECODE -> "ContractService.BYTECODE";
            case STATE_ID_STORAGE -> "ContractService.STORAGE";
            case STATE_ID_EVM_HOOK_STATES -> "ContractService.EVM_HOOK_STATES";
            case STATE_ID_LAMBDA_STORAGE -> "ContractService.LAMBDA_STORAGE";
            case STATE_ID_ENTITY_ID -> "EntityIdService.ENTITY_ID";
            case STATE_ID_MIDNIGHT_RATES -> "FeeService.MIDNIGHT_RATES";
            case STATE_ID_FILES -> "FileService.FILES";
            case STATE_ID_UPGRADE_DATA_150 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(150);
            case STATE_ID_UPGRADE_DATA_151 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(151);
            case STATE_ID_UPGRADE_DATA_152 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(152);
            case STATE_ID_UPGRADE_DATA_153 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(153);
            case STATE_ID_UPGRADE_DATA_154 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(154);
            case STATE_ID_UPGRADE_DATA_155 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(155);
            case STATE_ID_UPGRADE_DATA_156 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(156);
            case STATE_ID_UPGRADE_DATA_157 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(157);
            case STATE_ID_UPGRADE_DATA_158 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(158);
            case STATE_ID_UPGRADE_DATA_159 -> UPGRADE_DATA_FILE_NUM_FORMAT.formatted(159);
            case STATE_ID_FREEZE_TIME -> "FreezeService.FREEZE_TIME";
            case STATE_ID_UPGRADE_FILE_HASH -> "FreezeService.UPGRADE_FILE_HASH";
            case STATE_ID_PLATFORM_STATE -> "PlatformStateService.PLATFORM_STATE";
            case STATE_ID_ROSTER_STATE -> "RosterService.ROSTER_STATE";
            case STATE_ID_ROSTERS -> "RosterService.ROSTERS";
            case STATE_ID_ENTITY_COUNTS -> "EntityIdService.ENTITY_COUNTS";
            case STATE_ID_TRANSACTION_RECEIPTS -> "RecordCache.TRANSACTION_RECEIPTS";
            case STATE_ID_SCHEDULES_BY_EQUALITY -> "ScheduleService.SCHEDULES_BY_EQUALITY";
            case STATE_ID_SCHEDULES_BY_EXPIRY_SEC -> "ScheduleService.SCHEDULES_BY_EXPIRY_SEC";
            case STATE_ID_SCHEDULES_BY_ID -> "ScheduleService.SCHEDULES_BY_ID";
            case STATE_ID_SCHEDULE_ID_BY_EQUALITY -> "ScheduleService.SCHEDULE_ID_BY_EQUALITY";
            case STATE_ID_SCHEDULED_COUNTS -> "ScheduleService.SCHEDULED_COUNTS";
            case STATE_ID_SCHEDULED_ORDERS -> "ScheduleService.SCHEDULED_ORDERS";
            case STATE_ID_SCHEDULED_USAGES -> "ScheduleService.SCHEDULED_USAGES";
            case STATE_ID_ACCOUNTS -> "TokenService.ACCOUNTS";
            case STATE_ID_ALIASES -> "TokenService.ALIASES";
            case STATE_ID_NFTS -> "TokenService.NFTS";
            case STATE_ID_PENDING_AIRDROPS -> "TokenService.PENDING_AIRDROPS";
            case STATE_ID_STAKING_INFOS -> "TokenService.STAKING_INFOS";
            case STATE_ID_STAKING_NETWORK_REWARDS -> "TokenService.STAKING_NETWORK_REWARDS";
            case STATE_ID_TOKEN_RELS -> "TokenService.TOKEN_RELS";
            case STATE_ID_TOKENS -> "TokenService.TOKENS";
            case STATE_ID_TSS_MESSAGES -> "TssBaseService.TSS_MESSAGES";
            case STATE_ID_TSS_VOTES -> "TssBaseService.TSS_VOTES";
            case STATE_ID_TSS_ENCRYPTION_KEYS -> "TssBaseService.TSS_ENCRYPTION_KEYS";
            // FUTURE WORK: consider removing as there is no TSS_STATUS state
            case STATE_ID_TSS_STATUS -> "TssBaseService.TSS_STATUS";
            case STATE_ID_HINTS_KEY_SETS -> "HintsService.HINTS_KEY_SETS";
            case STATE_ID_ACTIVE_HINTS_CONSTRUCTION -> "HintsService.ACTIVE_HINTS_CONSTRUCTION";
            case STATE_ID_NEXT_HINTS_CONSTRUCTION -> "HintsService.NEXT_HINTS_CONSTRUCTION";
            case STATE_ID_PREPROCESSING_VOTES -> "HintsService.PREPROCESSING_VOTES";
            case STATE_ID_LEDGER_ID -> "HistoryService.LEDGER_ID";
            case STATE_ID_PROOF_KEY_SETS -> "HistoryService.PROOF_KEY_SETS";
            case STATE_ID_ACTIVE_PROOF_CONSTRUCTION -> "HistoryService.ACTIVE_PROOF_CONSTRUCTION";
            case STATE_ID_NEXT_PROOF_CONSTRUCTION -> "HistoryService.NEXT_PROOF_CONSTRUCTION";
            case STATE_ID_HISTORY_SIGNATURES -> "HistoryService.HISTORY_SIGNATURES";
            case STATE_ID_PROOF_VOTES -> "HistoryService.PROOF_VOTES";
            case STATE_ID_CRS_STATE -> "HintsService.CRS_STATE";
            case STATE_ID_CRS_PUBLICATIONS -> "HintsService.CRS_PUBLICATIONS";
            case STATE_ID_NODE_REWARDS -> "TokenService.NODE_REWARDS";
        };
    }

    public static Object singletonPutFor(@NonNull final SingletonUpdateChange singletonUpdateChange) {
        return switch (singletonUpdateChange.newValue().kind()) {
            case UNSET -> throw new IllegalStateException("Singleton update value is not set");
            case BLOCK_INFO_VALUE -> singletonUpdateChange.blockInfoValueOrThrow();
            case CONGESTION_LEVEL_STARTS_VALUE -> singletonUpdateChange.congestionLevelStartsValueOrThrow();
            case ENTITY_NUMBER_VALUE -> new EntityNumber(singletonUpdateChange.entityNumberValueOrThrow());
            case EXCHANGE_RATE_SET_VALUE -> singletonUpdateChange.exchangeRateSetValueOrThrow();
            case NETWORK_STAKING_REWARDS_VALUE -> singletonUpdateChange.networkStakingRewardsValueOrThrow();
            case NODE_REWARDS_VALUE -> singletonUpdateChange.nodeRewardsValueOrThrow();
            case BYTES_VALUE -> new ProtoBytes(singletonUpdateChange.bytesValueOrThrow());
            case STRING_VALUE -> new ProtoString(singletonUpdateChange.stringValueOrThrow());
            case RUNNING_HASHES_VALUE -> singletonUpdateChange.runningHashesValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> singletonUpdateChange.throttleUsageSnapshotsValueOrThrow();
            case TIMESTAMP_VALUE -> singletonUpdateChange.timestampValueOrThrow();
            case BLOCK_STREAM_INFO_VALUE -> singletonUpdateChange.blockStreamInfoValueOrThrow();
            case PLATFORM_STATE_VALUE -> singletonUpdateChange.platformStateValueOrThrow();
            case ROSTER_STATE_VALUE -> singletonUpdateChange.rosterStateValueOrThrow();
            case HINTS_CONSTRUCTION_VALUE -> singletonUpdateChange.hintsConstructionValueOrThrow();
            case ENTITY_COUNTS_VALUE -> singletonUpdateChange.entityCountsValueOrThrow();
            case HISTORY_PROOF_CONSTRUCTION_VALUE -> singletonUpdateChange.historyProofConstructionValueOrThrow();
            case CRS_STATE_VALUE -> singletonUpdateChange.crsStateValueOrThrow();
        };
    }

    public static Object queuePushFor(@NonNull final QueuePushChange queuePushChange) {
        return switch (queuePushChange.value().kind()) {
            case UNSET, PROTO_STRING_ELEMENT -> throw new IllegalStateException("Queue push value is not supported");
            case PROTO_BYTES_ELEMENT -> new ProtoBytes(queuePushChange.protoBytesElementOrThrow());
            case TRANSACTION_RECEIPT_ENTRIES_ELEMENT -> queuePushChange.transactionReceiptEntriesElementOrThrow();
        };
    }

    public static Object mapKeyFor(@NonNull final MapChangeKey mapChangeKey) {
        return switch (mapChangeKey.keyChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Key choice is not set for " + mapChangeKey);
            case ACCOUNT_ID_KEY -> mapChangeKey.accountIdKeyOrThrow();
            case TOKEN_RELATIONSHIP_KEY -> pairFrom(mapChangeKey.tokenRelationshipKeyOrThrow());
            case ENTITY_NUMBER_KEY -> new EntityNumber(mapChangeKey.entityNumberKeyOrThrow());
            case FILE_ID_KEY -> mapChangeKey.fileIdKeyOrThrow();
            case NFT_ID_KEY -> mapChangeKey.nftIdKeyOrThrow();
            case PROTO_BYTES_KEY -> new ProtoBytes(mapChangeKey.protoBytesKeyOrThrow());
            case PROTO_LONG_KEY -> new ProtoLong(mapChangeKey.protoLongKeyOrThrow());
            case PROTO_STRING_KEY -> new ProtoString(mapChangeKey.protoStringKeyOrThrow());
            case SCHEDULE_ID_KEY -> mapChangeKey.scheduleIdKeyOrThrow();
            case SLOT_KEY_KEY -> mapChangeKey.slotKeyKeyOrThrow();
            case TOKEN_ID_KEY -> mapChangeKey.tokenIdKeyOrThrow();
            case TOPIC_ID_KEY -> mapChangeKey.topicIdKeyOrThrow();
            case CONTRACT_ID_KEY -> mapChangeKey.contractIdKeyOrThrow();
            case PENDING_AIRDROP_ID_KEY -> mapChangeKey.pendingAirdropIdKeyOrThrow();
            case TIMESTAMP_SECONDS_KEY -> mapChangeKey.timestampSecondsKeyOrThrow();
            case SCHEDULED_ORDER_KEY -> mapChangeKey.scheduledOrderKeyOrThrow();
            case TSS_MESSAGE_MAP_KEY -> mapChangeKey.tssMessageMapKeyOrThrow();
            case TSS_VOTE_MAP_KEY -> mapChangeKey.tssVoteMapKeyOrThrow();
            case HINTS_PARTY_ID_KEY -> mapChangeKey.hintsPartyIdKeyOrThrow();
            case PREPROCESSING_VOTE_ID_KEY -> mapChangeKey.preprocessingVoteIdKeyOrThrow();
            case NODE_ID_KEY -> mapChangeKey.nodeIdKeyOrThrow();
            case CONSTRUCTION_NODE_ID_KEY -> mapChangeKey.constructionNodeIdKeyOrThrow();
            case HOOK_ID_KEY -> mapChangeKey.hookIdKeyOrThrow();
            case LAMBDA_SLOT_KEY -> mapChangeKey.lambdaSlotKeyOrThrow();
        };
    }

    public static Object mapValueFor(@NonNull final MapChangeValue mapChangeValue) {
        return switch (mapChangeValue.valueChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Value choice is not set for " + mapChangeValue);
            case ACCOUNT_VALUE -> mapChangeValue.accountValueOrThrow();
            case ACCOUNT_ID_VALUE -> mapChangeValue.accountIdValueOrThrow();
            case BYTECODE_VALUE -> mapChangeValue.bytecodeValueOrThrow();
            case FILE_VALUE -> mapChangeValue.fileValueOrThrow();
            case NFT_VALUE -> mapChangeValue.nftValueOrThrow();
            case PROTO_STRING_VALUE -> new ProtoString(mapChangeValue.protoStringValueOrThrow());
            case SCHEDULE_VALUE -> mapChangeValue.scheduleValueOrThrow();
            case SCHEDULE_ID_VALUE -> mapChangeValue.scheduleIdValueOrThrow();
            case SCHEDULE_LIST_VALUE -> mapChangeValue.scheduleListValueOrThrow();
            case SLOT_VALUE_VALUE -> mapChangeValue.slotValueValueOrThrow();
            case STAKING_NODE_INFO_VALUE -> mapChangeValue.stakingNodeInfoValueOrThrow();
            case TOKEN_VALUE -> mapChangeValue.tokenValueOrThrow();
            case TOKEN_RELATION_VALUE -> mapChangeValue.tokenRelationValueOrThrow();
            case TOPIC_VALUE -> mapChangeValue.topicValueOrThrow();
            case NODE_VALUE -> mapChangeValue.nodeValueOrThrow();
            case ACCOUNT_PENDING_AIRDROP_VALUE -> mapChangeValue.accountPendingAirdropValueOrThrow();
            case ROSTER_VALUE -> mapChangeValue.rosterValueOrThrow();
            case SCHEDULED_COUNTS_VALUE -> mapChangeValue.scheduledCountsValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> mapChangeValue.throttleUsageSnapshotsValue();
            case TSS_ENCRYPTION_KEYS_VALUE -> mapChangeValue.tssEncryptionKeysValue();
            case TSS_MESSAGE_VALUE -> mapChangeValue.tssMessageValueOrThrow();
            case TSS_VOTE_VALUE -> mapChangeValue.tssVoteValueOrThrow();
            case HINTS_KEY_SET_VALUE -> mapChangeValue.hintsKeySetValueOrThrow();
            case PREPROCESSING_VOTE_VALUE -> mapChangeValue.preprocessingVoteValueOrThrow();
            case CRS_PUBLICATION_VALUE -> mapChangeValue.crsPublicationValueOrThrow();
            case HISTORY_PROOF_VOTE_VALUE -> mapChangeValue.historyProofVoteValue();
            case HISTORY_SIGNATURE_VALUE -> mapChangeValue.historySignatureValue();
            case PROOF_KEY_SET_VALUE -> mapChangeValue.proofKeySetValue();
            case EVM_HOOK_STATE_VALUE -> mapChangeValue.evmHookStateValueOrThrow();
            case NODE_ID_VALUE -> mapChangeValue.nodeIdValueOrThrow();
        };
    }

    public static EntityIDPair pairFrom(@NonNull final TokenAssociation tokenAssociation) {
        return new EntityIDPair(tokenAssociation.accountId(), tokenAssociation.tokenId());
    }
}
