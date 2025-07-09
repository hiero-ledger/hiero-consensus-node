// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_PROOF_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ALIASES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_INFO;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BLOCK_STREAM_INFO;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CONGESTION_STARTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CONTRACT_BYTECODE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CONTRACT_STORAGE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CRS_PUBLICATIONS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CRS_STATE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ENTITY_COUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ENTITY_ID;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_FILES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_FREEZE_TIME;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_HINTS_KEY_SETS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_HISTORY_SIGNATURES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_LEDGER_ID;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_MIDNIGHT_RATES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NETWORK_REWARDS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NEXT_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NEXT_PROOF_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NFTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NODES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NODE_REWARDS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PENDING_AIRDROPS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PLATFORM_STATE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PREPROCESSING_VOTES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PROOF_KEY_SETS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PROOF_VOTES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ROSTERS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ROSTER_STATE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_RUNNING_HASHES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULED_COUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULED_ORDERS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULED_USAGES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULES_BY_EQUALITY;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULES_BY_EXPIRY;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULES_BY_ID;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_SCHEDULE_ID_BY_EQUALITY;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_STAKING_INFO;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_THROTTLE_USAGE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TOKENS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TOKEN_RELATIONS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TOPICS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TRANSACTION_RECEIPTS_QUEUE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TSS_ENCRYPTION_KEYS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TSS_MESSAGES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TSS_STATUS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_TSS_VOTES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_150;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_151;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_152;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_153;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_154;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_155;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_156;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_157;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_158;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_DATA_159;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_FILE_HASH;
import static com.swirlds.state.BinaryStateUtils.UPGRADE_DATA_FILE_FORMAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryStateUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StateUtilsTest {
    private static final int UNKNOWN_STATE_ID = -1;

    @ParameterizedTest
    @MethodSource("stateIdsByName")
    void stateIdsByNameAsExpected(@NonNull final String stateName, @NonNull final StateIdentifier stateId) {
        final var parts = stateName.split("\\.");
        assertThat(stateIdFor(parts[0], parts[1])).isEqualTo(stateId.protoOrdinal());
    }

    @ParameterizedTest
    @MethodSource("stateIdsByName")
    void mappingEquivalence(@NonNull final String stateName, @NonNull final StateIdentifier stateId) {
        final var parts = stateName.split("\\.");
        assertThat(stateIdFor(parts[0], parts[1])).isEqualTo(BinaryStateUtils.stateIdFor(parts[0], parts[1]));
    }

    @Test
    void singletonEquivalence() {
        final int stateId = stateIdFor("PlatformStateService", "PLATFORM_STATE");
        final Bytes expectedKey = VirtualMapKey.PROTOBUF.toBytes(new VirtualMapKey(
                new OneOf<>(VirtualMapKey.KeyOneOfType.SINGLETON, SingletonType.fromProtobufOrdinal(stateId))));

        assertThat(BinaryStateUtils.getVirtualMapKeyForSingleton("PlatformStateService", "PLATFORM_STATE"))
                .isEqualTo(expectedKey);
    }

    @Test
    void queueEquivalence() {
        assertThat(BinaryStateUtils.getVirtualMapKeyForQueue("RecordCache", "TransactionReceiptQueue", 123))
                .isEqualTo(BinaryStateUtils.createVirtualMapKeyBytesForQueue(
                        "RecordCache", "TransactionReceiptQueue", 123));
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
            case STATE_ID_UPGRADE_DATA_150 -> "FileService.UPGRADE_DATA[FileID[shardNum=11, realmNum=12, fileNum=150]]";
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

    private static int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        final var stateId =
                switch (serviceName) {
                    case "AddressBookService" ->
                        switch (stateKey) {
                            case "NODES" -> STATE_ID_NODES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockRecordService" ->
                        switch (stateKey) {
                            case "BLOCKS" -> STATE_ID_BLOCK_INFO.protoOrdinal();
                            case "RUNNING_HASHES" -> STATE_ID_RUNNING_HASHES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockStreamService" ->
                        switch (stateKey) {
                            case "BLOCK_STREAM_INFO" -> STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "CongestionThrottleService" ->
                        switch (stateKey) {
                            case "CONGESTION_LEVEL_STARTS" -> STATE_ID_CONGESTION_STARTS.protoOrdinal();
                            case "THROTTLE_USAGE_SNAPSHOTS" -> STATE_ID_THROTTLE_USAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ConsensusService" ->
                        switch (stateKey) {
                            case "TOPICS" -> STATE_ID_TOPICS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ContractService" ->
                        switch (stateKey) {
                            case "BYTECODE" -> STATE_ID_CONTRACT_BYTECODE.protoOrdinal();
                            case "STORAGE" -> STATE_ID_CONTRACT_STORAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "EntityIdService" ->
                        switch (stateKey) {
                            case "ENTITY_ID" -> STATE_ID_ENTITY_ID.protoOrdinal();
                            case "ENTITY_COUNTS" -> STATE_ID_ENTITY_COUNTS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FeeService" ->
                        switch (stateKey) {
                            case "MIDNIGHT_RATES" -> STATE_ID_MIDNIGHT_RATES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FileService" -> {
                        if ("FILES".equals(stateKey)) {
                            yield STATE_ID_FILES.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(150))) {
                            yield STATE_ID_UPGRADE_DATA_150.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(151))) {
                            yield STATE_ID_UPGRADE_DATA_151.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(152))) {
                            yield STATE_ID_UPGRADE_DATA_152.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(153))) {
                            yield STATE_ID_UPGRADE_DATA_153.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(154))) {
                            yield STATE_ID_UPGRADE_DATA_154.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(155))) {
                            yield STATE_ID_UPGRADE_DATA_155.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(156))) {
                            yield STATE_ID_UPGRADE_DATA_156.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(157))) {
                            yield STATE_ID_UPGRADE_DATA_157.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(158))) {
                            yield STATE_ID_UPGRADE_DATA_158.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(159))) {
                            yield STATE_ID_UPGRADE_DATA_159.protoOrdinal();
                        } else {
                            yield UNKNOWN_STATE_ID;
                        }
                    }
                    case "FreezeService" ->
                        switch (stateKey) {
                            case "FREEZE_TIME" -> STATE_ID_FREEZE_TIME.protoOrdinal();
                            case "UPGRADE_FILE_HASH" -> STATE_ID_UPGRADE_FILE_HASH.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "PlatformStateService" ->
                        switch (stateKey) {
                            case "PLATFORM_STATE" -> STATE_ID_PLATFORM_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RecordCache" ->
                        switch (stateKey) {
                            case "TransactionReceiptQueue" -> STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            // There is no such queue, but this needed for V0540RecordCacheSchema schema migration
                            case "TransactionRecordQueue" -> STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RosterService" ->
                        switch (stateKey) {
                            case "ROSTERS" -> STATE_ID_ROSTERS.protoOrdinal();
                            case "ROSTER_STATE" -> STATE_ID_ROSTER_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ScheduleService" ->
                        switch (stateKey) {
                            case "SCHEDULES_BY_EQUALITY" -> STATE_ID_SCHEDULES_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULES_BY_EXPIRY_SEC" -> STATE_ID_SCHEDULES_BY_EXPIRY.protoOrdinal();
                            case "SCHEDULES_BY_ID" -> STATE_ID_SCHEDULES_BY_ID.protoOrdinal();
                            case "SCHEDULE_ID_BY_EQUALITY" -> STATE_ID_SCHEDULE_ID_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULED_COUNTS" -> STATE_ID_SCHEDULED_COUNTS.protoOrdinal();
                            case "SCHEDULED_ORDERS" -> STATE_ID_SCHEDULED_ORDERS.protoOrdinal();
                            case "SCHEDULED_USAGES" -> STATE_ID_SCHEDULED_USAGES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TokenService" ->
                        switch (stateKey) {
                            case "ACCOUNTS" -> STATE_ID_ACCOUNTS.protoOrdinal();
                            case "ALIASES" -> STATE_ID_ALIASES.protoOrdinal();
                            case "NFTS" -> STATE_ID_NFTS.protoOrdinal();
                            case "PENDING_AIRDROPS" -> STATE_ID_PENDING_AIRDROPS.protoOrdinal();
                            case "STAKING_INFOS" -> STATE_ID_STAKING_INFO.protoOrdinal();
                            case "STAKING_NETWORK_REWARDS" -> STATE_ID_NETWORK_REWARDS.protoOrdinal();
                            case "TOKEN_RELS" -> STATE_ID_TOKEN_RELATIONS.protoOrdinal();
                            case "TOKENS" -> STATE_ID_TOKENS.protoOrdinal();
                            case "NODE_REWARDS" -> STATE_ID_NODE_REWARDS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TssBaseService" ->
                        switch (stateKey) {
                            case "TSS_MESSAGES" -> STATE_ID_TSS_MESSAGES.protoOrdinal();
                            case "TSS_VOTES" -> STATE_ID_TSS_VOTES.protoOrdinal();
                            case "TSS_ENCRYPTION_KEYS" -> STATE_ID_TSS_ENCRYPTION_KEYS.protoOrdinal();
                            case "TSS_STATUS" -> STATE_ID_TSS_STATUS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HintsService" ->
                        switch (stateKey) {
                            case "HINTS_KEY_SETS" -> STATE_ID_HINTS_KEY_SETS.protoOrdinal();
                            case "ACTIVE_HINT_CONSTRUCTION" -> STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal();
                            case "NEXT_HINT_CONSTRUCTION" -> STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal();
                            case "PREPROCESSING_VOTES" -> STATE_ID_PREPROCESSING_VOTES.protoOrdinal();
                            case "CRS_STATE" -> STATE_ID_CRS_STATE.protoOrdinal();
                            case "CRS_PUBLICATIONS" -> STATE_ID_CRS_PUBLICATIONS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HistoryService" ->
                        switch (stateKey) {
                            case "LEDGER_ID" -> STATE_ID_LEDGER_ID.protoOrdinal();
                            case "PROOF_KEY_SETS" -> STATE_ID_PROOF_KEY_SETS.protoOrdinal();
                            case "ACTIVE_PROOF_CONSTRUCTION" -> STATE_ID_ACTIVE_PROOF_CONSTRUCTION.protoOrdinal();
                            case "NEXT_PROOF_CONSTRUCTION" -> STATE_ID_NEXT_PROOF_CONSTRUCTION.protoOrdinal();
                            case "HISTORY_SIGNATURES" -> STATE_ID_HISTORY_SIGNATURES.protoOrdinal();
                            case "PROOF_VOTES" -> STATE_ID_PROOF_VOTES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    default -> UNKNOWN_STATE_ID;
                };
        if (stateId == UNKNOWN_STATE_ID) {
            throw new IllegalArgumentException("Unknown state '" + serviceName + "." + stateKey + "'");
        } else {
            return stateId;
        }
    }
}
