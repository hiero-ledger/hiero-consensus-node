// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

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
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_FILE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_UPGRADE_FILE_HASH;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.IntFunction;
import org.hiero.consensus.model.crypto.DigestType;

/**
 * Utility methods for block implementation.
 */
public class BlockImplUtils {
    private static final int UNKNOWN_STATE_ID = -1;
    private static final IntFunction<String> UPGRADE_DATA_FILE_FORMAT =
            n -> String.format("UPGRADE_DATA\\[FileID\\[shardNum=\\d+, realmNum=\\d+, fileNum=%s]]", n);

    /**
     * Prevent instantiation
     */
    private BlockImplUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns the state id for the given service and state key.
     *
     * @param serviceName the service name
     * @param stateKey the state key
     * @return the state id
     */
    public static int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        final var stateId =
                switch (serviceName) {
                    case "AddressBookService" -> switch (stateKey) {
                        case "NODES" -> STATE_ID_NODES.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "BlockRecordService" -> switch (stateKey) {
                        case "BLOCKS" -> STATE_ID_BLOCK_INFO.protoOrdinal();
                        case "RUNNING_HASHES" -> STATE_ID_RUNNING_HASHES.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "BlockStreamService" -> switch (stateKey) {
                        case "BLOCK_STREAM_INFO" -> STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "CongestionThrottleService" -> switch (stateKey) {
                        case "CONGESTION_LEVEL_STARTS" -> STATE_ID_CONGESTION_STARTS.protoOrdinal();
                        case "THROTTLE_USAGE_SNAPSHOTS" -> STATE_ID_THROTTLE_USAGE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "ConsensusService" -> switch (stateKey) {
                        case "TOPICS" -> STATE_ID_TOPICS.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "ContractService" -> switch (stateKey) {
                        case "BYTECODE" -> STATE_ID_CONTRACT_BYTECODE.protoOrdinal();
                        case "STORAGE" -> STATE_ID_CONTRACT_STORAGE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "EntityIdService" -> switch (stateKey) {
                        case "ENTITY_ID" -> STATE_ID_ENTITY_ID.protoOrdinal();
                        case "ENTITY_COUNTS" -> STATE_ID_ENTITY_COUNTS.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "FeeService" -> switch (stateKey) {
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
                        } else if ("UPGRADE_FILE".equals(stateKey)) {
                            yield STATE_ID_UPGRADE_FILE.protoOrdinal();
                        } else {
                            yield UNKNOWN_STATE_ID;
                        }
                    }
                    case "FreezeService" -> switch (stateKey) {
                        case "FREEZE_TIME" -> STATE_ID_FREEZE_TIME.protoOrdinal();
                        case "UPGRADE_FILE_HASH" -> STATE_ID_UPGRADE_FILE_HASH.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "PlatformStateService" -> switch (stateKey) {
                        case "PLATFORM_STATE" -> STATE_ID_PLATFORM_STATE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "RecordCache" -> switch (stateKey) {
                        case "TransactionReceiptQueue" -> STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "RosterService" -> switch (stateKey) {
                        case "ROSTERS" -> STATE_ID_ROSTERS.protoOrdinal();
                        case "ROSTER_STATE" -> STATE_ID_ROSTER_STATE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "ScheduleService" -> switch (stateKey) {
                        case "SCHEDULES_BY_EQUALITY" -> STATE_ID_SCHEDULES_BY_EQUALITY.protoOrdinal();
                        case "SCHEDULES_BY_EXPIRY_SEC" -> STATE_ID_SCHEDULES_BY_EXPIRY.protoOrdinal();
                        case "SCHEDULES_BY_ID" -> STATE_ID_SCHEDULES_BY_ID.protoOrdinal();
                        case "SCHEDULE_ID_BY_EQUALITY" -> STATE_ID_SCHEDULE_ID_BY_EQUALITY.protoOrdinal();
                        case "SCHEDULED_COUNTS" -> STATE_ID_SCHEDULED_COUNTS.protoOrdinal();
                        case "SCHEDULED_ORDERS" -> STATE_ID_SCHEDULED_ORDERS.protoOrdinal();
                        case "SCHEDULED_USAGES" -> STATE_ID_SCHEDULED_USAGES.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "TokenService" -> switch (stateKey) {
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
                    case "TssBaseService" -> switch (stateKey) {
                        case "TSS_MESSAGES" -> STATE_ID_TSS_MESSAGES.protoOrdinal();
                        case "TSS_VOTES" -> STATE_ID_TSS_VOTES.protoOrdinal();
                        case "TSS_ENCRYPTION_KEYS" -> STATE_ID_TSS_ENCRYPTION_KEYS.protoOrdinal();
                        case "TSS_STATUS" -> STATE_ID_TSS_STATUS.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "HintsService" -> switch (stateKey) {
                        case "HINTS_KEY_SETS" -> STATE_ID_HINTS_KEY_SETS.protoOrdinal();
                        case "ACTIVE_HINT_CONSTRUCTION" -> STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal();
                        case "NEXT_HINT_CONSTRUCTION" -> STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal();
                        case "PREPROCESSING_VOTES" -> STATE_ID_PREPROCESSING_VOTES.protoOrdinal();
                        case "CRS_STATE" -> STATE_ID_CRS_STATE.protoOrdinal();
                        case "CRS_PUBLICATIONS" -> STATE_ID_CRS_PUBLICATIONS.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
                    case "HistoryService" -> switch (stateKey) {
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

    /**
     * Appends the given hash to the given hashes. If the number of hashes exceeds the given maximum, the oldest hash
     * is removed.
     * @param hash the hash to append
     * @param hashes the hashes
     * @param maxHashes the maximum number of hashes
     * @return the new hashes
     */
    public static Bytes appendHash(@NonNull final Bytes hash, @NonNull final Bytes hashes, final int maxHashes) {
        final var limit = HASH_SIZE * maxHashes;
        final byte[] bytes = hashes.toByteArray();
        final byte[] newBytes;
        if (bytes.length < limit) {
            newBytes = new byte[bytes.length + HASH_SIZE];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        } else {
            newBytes = bytes;
            System.arraycopy(newBytes, HASH_SIZE, newBytes, 0, newBytes.length - HASH_SIZE);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        }
        return Bytes.wrap(newBytes);
    }

    /**
     * Hashes the given left and right hashes.
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static Bytes combine(@NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        return Bytes.wrap(combine(leftHash.toByteArray(), rightHash.toByteArray()));
    }

    /**
     * Hashes the given left and right hashes.
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static byte[] combine(final byte[] leftHash, final byte[] rightHash) {
        try {
            final var digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            digest.update(leftHash);
            digest.update(rightHash);
            return digest.digest();
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }
}
