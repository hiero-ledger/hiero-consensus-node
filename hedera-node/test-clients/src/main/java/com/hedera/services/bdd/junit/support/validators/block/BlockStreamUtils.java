// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.spec.HapiPropertySource.realm;
import static com.hedera.services.bdd.spec.HapiPropertySource.shard;

import com.hedera.hapi.block.stream.output.StateIdentifier;

public class BlockStreamUtils {
    private static final String UPGRADE_DATA_FILE_NUM_FORMAT =
            "FileService.UPGRADE_DATA[FileID[shardNum=%d, realmNum=%d, fileNum=%d]]";

    public static String stateNameOf(final int stateId) {
        return switch (StateIdentifier.fromProtobufOrdinal(stateId)) {
            case STATE_ID_NODES -> "AddressBookService.NODES";
            case STATE_ID_BLOCK_INFO -> "BlockRecordService.BLOCKS";
            case STATE_ID_RUNNING_HASHES -> "BlockRecordService.RUNNING_HASHES";
            case STATE_ID_BLOCK_STREAM_INFO -> "BlockStreamService.BLOCK_STREAM_INFO";
            case STATE_ID_CONGESTION_STARTS -> "CongestionThrottleService.CONGESTION_LEVEL_STARTS";
            case STATE_ID_THROTTLE_USAGE -> "CongestionThrottleService.THROTTLE_USAGE_SNAPSHOTS";
            case STATE_ID_TOPICS -> "ConsensusService.TOPICS";
            case STATE_ID_CONTRACT_BYTECODE -> "ContractService.BYTECODE";
            case STATE_ID_CONTRACT_STORAGE -> "ContractService.STORAGE";
            case STATE_ID_ENTITY_ID -> "EntityIdService.ENTITY_ID";
            case STATE_ID_MIDNIGHT_RATES -> "FeeService.MIDNIGHT_RATES";
            case STATE_ID_FILES -> "FileService.FILES";
            case STATE_ID_UPGRADE_DATA_150 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 150);
            case STATE_ID_UPGRADE_DATA_151 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 151);
            case STATE_ID_UPGRADE_DATA_152 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 152);
            case STATE_ID_UPGRADE_DATA_153 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 153);
            case STATE_ID_UPGRADE_DATA_154 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 154);
            case STATE_ID_UPGRADE_DATA_155 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 155);
            case STATE_ID_UPGRADE_DATA_156 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 156);
            case STATE_ID_UPGRADE_DATA_157 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 157);
            case STATE_ID_UPGRADE_DATA_158 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 158);
            case STATE_ID_UPGRADE_DATA_159 -> String.format(UPGRADE_DATA_FILE_NUM_FORMAT, shard, realm, 159);
            case STATE_ID_UPGRADE_FILE -> "FileService.UPGRADE_FILE";
            case STATE_ID_FREEZE_TIME -> "FreezeService.FREEZE_TIME";
            case STATE_ID_UPGRADE_FILE_HASH -> "FreezeService.UPGRADE_FILE_HASH";
            case STATE_ID_PLATFORM_STATE -> "PlatformStateService.PLATFORM_STATE";
            case STATE_ID_ROSTER_STATE -> "RosterService.ROSTER_STATE";
            case STATE_ID_ROSTERS -> "RosterService.ROSTERS";
            case STATE_ID_ENTITY_COUNTS -> "EntityIdService.ENTITY_COUNTS";
            case STATE_ID_TRANSACTION_RECEIPTS_QUEUE -> "RecordCache.TransactionReceiptQueue";
            case STATE_ID_SCHEDULES_BY_EQUALITY -> "ScheduleService.SCHEDULES_BY_EQUALITY";
            case STATE_ID_SCHEDULES_BY_EXPIRY -> "ScheduleService.SCHEDULES_BY_EXPIRY_SEC";
            case STATE_ID_SCHEDULES_BY_ID -> "ScheduleService.SCHEDULES_BY_ID";
            case STATE_ID_SCHEDULE_ID_BY_EQUALITY -> "ScheduleService.SCHEDULE_ID_BY_EQUALITY";
            case STATE_ID_SCHEDULED_COUNTS -> "ScheduleService.SCHEDULED_COUNTS";
            case STATE_ID_SCHEDULED_ORDERS -> "ScheduleService.SCHEDULED_ORDERS";
            case STATE_ID_SCHEDULED_USAGES -> "ScheduleService.SCHEDULED_USAGES";
            case STATE_ID_ACCOUNTS -> "TokenService.ACCOUNTS";
            case STATE_ID_ALIASES -> "TokenService.ALIASES";
            case STATE_ID_NFTS -> "TokenService.NFTS";
            case STATE_ID_PENDING_AIRDROPS -> "TokenService.PENDING_AIRDROPS";
            case STATE_ID_STAKING_INFO -> "TokenService.STAKING_INFOS";
            case STATE_ID_NETWORK_REWARDS -> "TokenService.STAKING_NETWORK_REWARDS";
            case STATE_ID_TOKEN_RELATIONS -> "TokenService.TOKEN_RELS";
            case STATE_ID_TOKENS -> "TokenService.TOKENS";
            case STATE_ID_TSS_MESSAGES -> "TssBaseService.TSS_MESSAGES";
            case STATE_ID_TSS_VOTES -> "TssBaseService.TSS_VOTES";
            case STATE_ID_TSS_ENCRYPTION_KEYS -> "TssBaseService.TSS_ENCRYPTION_KEY";
            case STATE_ID_TSS_STATUS -> "TssBaseService.TSS_STATUS";
            case STATE_ID_HINTS_KEY_SETS -> "HintsService.HINTS_KEY_SETS";
            case STATE_ID_ACTIVE_HINTS_CONSTRUCTION -> "HintsService.ACTIVE_HINT_CONSTRUCTION";
            case STATE_ID_NEXT_HINTS_CONSTRUCTION -> "HintsService.NEXT_HINT_CONSTRUCTION";
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
}
