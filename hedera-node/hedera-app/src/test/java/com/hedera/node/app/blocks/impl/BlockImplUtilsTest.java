// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.output.StateIdentifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BlockImplUtilsTest {
    @Test
    void testCombineNormalCase() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash = BlockImplUtils.combine(leftHash, rightHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineEmptyHashes() throws NoSuchAlgorithmException {
        byte[] emptyHash = MessageDigest.getInstance("SHA-384").digest(new byte[0]);
        byte[] combinedHash = BlockImplUtils.combine(emptyHash, emptyHash);

        assertNotNull(combinedHash);
        assertEquals(48, combinedHash.length); // SHA-384 produces 48-byte hash
    }

    @Test
    void testCombineDifferentHashes() throws NoSuchAlgorithmException {
        byte[] leftHash = MessageDigest.getInstance("SHA-384").digest("left".getBytes());
        byte[] rightHash = MessageDigest.getInstance("SHA-384").digest("right".getBytes());
        byte[] combinedHash1 = BlockImplUtils.combine(leftHash, rightHash);
        byte[] combinedHash2 = BlockImplUtils.combine(rightHash, leftHash);

        assertNotNull(combinedHash1);
        assertNotNull(combinedHash2);
        assertNotEquals(new String(combinedHash1), new String(combinedHash2));
    }

    @Test
    void testCombineWithNull() {
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> BlockImplUtils.combine(new byte[0], null));
    }

    @ParameterizedTest
    @MethodSource("stateIdsByName")
    void stateIdsByNameAsExpected(@NonNull final String stateName, @NonNull final StateIdentifier stateId) {
        final var parts = stateName.split("\\.");
        assertThat(BlockImplUtils.stateIdFor(parts[0], parts[1])).isEqualTo(stateId.protoOrdinal());
    }

    public static Stream<Arguments> stateIdsByName() {
        return Arrays.stream(StateIdentifier.values())
                .filter(v -> v != StateIdentifier.UNKNOWN)
                .map(stateId -> Arguments.of(nameOf(stateId), stateId));
    }

    private static String nameOf(@NonNull final StateIdentifier stateId) {
        return switch (stateId) {
            case UNKNOWN -> throw new IllegalArgumentException("Unknown state identifier");
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
            case STATE_ID_UPGRADE_DATA_150 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=150]]";
            case STATE_ID_UPGRADE_DATA_151 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=151]]";
            case STATE_ID_UPGRADE_DATA_152 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=152]]";
            case STATE_ID_UPGRADE_DATA_153 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=153]]";
            case STATE_ID_UPGRADE_DATA_154 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=154]]";
            case STATE_ID_UPGRADE_DATA_155 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=155]]";
            case STATE_ID_UPGRADE_DATA_156 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=156]]";
            case STATE_ID_UPGRADE_DATA_157 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=157]]";
            case STATE_ID_UPGRADE_DATA_158 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=158]]";
            case STATE_ID_UPGRADE_DATA_159 -> "FileService.UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=159]]";
            case STATE_ID_FREEZE_TIME -> "FreezeService.FREEZE_TIME";
            case STATE_ID_UPGRADE_FILE_HASH -> "FreezeService.UPGRADE_FILE_HASH";
            case STATE_ID_PLATFORM_STATE -> "PlatformStateService.PLATFORM_STATE";
            case STATE_ID_ROSTER_STATE -> "RosterService.ROSTER_STATE";
            case STATE_ID_ROSTERS -> "RosterService.ROSTERS";
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
            case STATE_ID_TSS_ENCRYPTION_KEYS -> "TssBaseService.TSS_ENCRYPTION_KEYS";
            case STATE_ID_TSS_STATUS -> "TssBaseService.TSS_STATUS";
            case STATE_ID_HINTS_KEY_SETS -> "HintsService.HINTS_KEY_SETS";
            case STATE_ID_ACTIVE_HINTS_CONSTRUCTION -> "HintsService.ACTIVE_HINT_CONSTRUCTION";
            case STATE_ID_NEXT_HINTS_CONSTRUCTION -> "HintsService.NEXT_HINT_CONSTRUCTION";
            case STATE_ID_PREPROCESSING_VOTES -> "HintsService.PREPROCESSING_VOTES";
            case STATE_ID_ENTITY_COUNTS -> "EntityIdService.ENTITY_COUNTS";
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
