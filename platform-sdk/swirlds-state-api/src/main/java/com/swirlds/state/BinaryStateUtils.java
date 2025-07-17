// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt64;
import static java.lang.Math.toIntExact;

import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Utility class for handling binary state operations, including state ID retrieval, label computation,
 * and virtual map key generation.
 */
public class BinaryStateUtils {

    public static final IntFunction<String> UPGRADE_DATA_FILE_FORMAT =
            n -> String.format("UPGRADE_DATA\\[FileID\\[shardNum=\\d+, realmNum=\\d+, fileNum=%s]]", n);
    private static final int UNKNOWN_STATE_ID = -1;
    /** Cache for pre-computed virtual map keys for singleton states. */
    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];
    /** Cache to store and retrieve pre-computed labels for specific service states. */
    private static final Map<String, String> LABEL_CACHE = new ConcurrentHashMap<>();

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
                    case "AddressBookService" ->
                        switch (stateKey) {
                            case "NODES" -> 20; // STATE_ID_NODES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockRecordService" ->
                        switch (stateKey) {
                            case "BLOCKS" -> 19; // STATE_ID_BLOCK_INFO.protoOrdinal();
                            case "RUNNING_HASHES" -> 18; // STATE_ID_RUNNING_HASHES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockStreamService" ->
                        switch (stateKey) {
                            case "BLOCK_STREAM_INFO" -> 24; // STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "CongestionThrottleService" ->
                        switch (stateKey) {
                            case "CONGESTION_LEVEL_STARTS" -> 13; // STATE_ID_CONGESTION_STARTS.protoOrdinal();
                            case "THROTTLE_USAGE_SNAPSHOTS" -> 12; // STATE_ID_THROTTLE_USAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ConsensusService" ->
                        switch (stateKey) {
                            case "TOPICS" -> 21; // STATE_ID_TOPICS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ContractService" ->
                        switch (stateKey) {
                            case "BYTECODE" -> 5; // STATE_ID_CONTRACT_BYTECODE.protoOrdinal();
                            case "STORAGE" -> 4; // STATE_ID_CONTRACT_STORAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "EntityIdService" ->
                        switch (stateKey) {
                            case "ENTITY_ID" -> 1; // STATE_ID_ENTITY_ID.protoOrdinal();
                            case "ENTITY_COUNTS" -> 41; // STATE_ID_ENTITY_COUNTS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FeeService" ->
                        switch (stateKey) {
                            case "MIDNIGHT_RATES" -> 17; // STATE_ID_MIDNIGHT_RATES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FileService" -> {
                        if ("FILES".equals(stateKey)) {
                            yield 6; // STATE_ID_FILES.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(150))) {
                            yield 10001; // STATE_ID_UPGRADE_DATA_150.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(151))) {
                            yield 10002; // STATE_ID_UPGRADE_DATA_151.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(152))) {
                            yield 10003; // STATE_ID_UPGRADE_DATA_152.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(153))) {
                            yield 10004; // STATE_ID_UPGRADE_DATA_153.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(154))) {
                            yield 10005; // STATE_ID_UPGRADE_DATA_154.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(155))) {
                            yield 10006; // STATE_ID_UPGRADE_DATA_155.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(156))) {
                            yield 10007; // STATE_ID_UPGRADE_DATA_156.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(157))) {
                            yield 10008; // STATE_ID_UPGRADE_DATA_157.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(158))) {
                            yield 10009; // STATE_ID_UPGRADE_DATA_158.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(159))) {
                            yield 10010; // STATE_ID_UPGRADE_DATA_159.protoOrdinal();
                        } else {
                            yield UNKNOWN_STATE_ID;
                        }
                    }
                    case "FreezeService" ->
                        switch (stateKey) {
                            case "FREEZE_TIME" -> 23; // STATE_ID_FREEZE_TIME.protoOrdinal();
                            case "UPGRADE_FILE_HASH" -> 22; // STATE_ID_UPGRADE_FILE_HASH.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "PlatformStateService" ->
                        switch (stateKey) {
                            case "PLATFORM_STATE" -> 26; // STATE_ID_PLATFORM_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RecordCache" ->
                        switch (stateKey) {
                            case "TransactionReceiptQueue" ->
                                126; // STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            // There is no such queue, but this needed for V0540RecordCacheSchema schema migration
                            case "TransactionRecordQueue" -> 126; // STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RosterService" ->
                        switch (stateKey) {
                            case "ROSTERS" -> 28; // STATE_ID_ROSTERS.protoOrdinal();
                            case "ROSTER_STATE" -> 27; // STATE_ID_ROSTER_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ScheduleService" ->
                        switch (stateKey) {
                            case "SCHEDULES_BY_EQUALITY" -> 16; // STATE_ID_SCHEDULES_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULES_BY_EXPIRY_SEC" -> 15; // STATE_ID_SCHEDULES_BY_EXPIRY.protoOrdinal();
                            case "SCHEDULES_BY_ID" -> 14; // STATE_ID_SCHEDULES_BY_ID.protoOrdinal();
                            case "SCHEDULE_ID_BY_EQUALITY" -> 30; // STATE_ID_SCHEDULE_ID_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULED_COUNTS" -> 29; // STATE_ID_SCHEDULED_COUNTS.protoOrdinal();
                            case "SCHEDULED_ORDERS" -> 33; // STATE_ID_SCHEDULED_ORDERS.protoOrdinal();
                            case "SCHEDULED_USAGES" -> 34; // STATE_ID_SCHEDULED_USAGES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TokenService" ->
                        switch (stateKey) {
                            case "ACCOUNTS" -> 2; // STATE_ID_ACCOUNTS.protoOrdinal();
                            case "ALIASES" -> 3; // STATE_ID_ALIASES.protoOrdinal();
                            case "NFTS" -> 8; // STATE_ID_NFTS.protoOrdinal();
                            case "PENDING_AIRDROPS" -> 25; // STATE_ID_PENDING_AIRDROPS.protoOrdinal();
                            case "STAKING_INFOS" -> 10; // STATE_ID_STAKING_INFO.protoOrdinal();
                            case "STAKING_NETWORK_REWARDS" -> 11; // STATE_ID_NETWORK_REWARDS.protoOrdinal();
                            case "TOKEN_RELS" -> 9; // STATE_ID_TOKEN_RELATIONS.protoOrdinal();
                            case "TOKENS" -> 7; // STATE_ID_TOKENS.protoOrdinal();
                            case "NODE_REWARDS" -> 50; // STATE_ID_NODE_REWARDS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TssBaseService" ->
                        switch (stateKey) {
                            case "TSS_MESSAGES" -> 31; // STATE_ID_TSS_MESSAGES.protoOrdinal();
                            case "TSS_VOTES" -> 32; // STATE_ID_TSS_VOTES.protoOrdinal();
                            case "TSS_ENCRYPTION_KEYS" -> 35; // STATE_ID_TSS_ENCRYPTION_KEYS.protoOrdinal();
                            case "TSS_STATUS" -> 36; // STATE_ID_TSS_STATUS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HintsService" ->
                        switch (stateKey) {
                            case "HINTS_KEY_SETS" -> 37; // STATE_ID_HINTS_KEY_SETS.protoOrdinal();
                            case "ACTIVE_HINT_CONSTRUCTION" -> 38; // STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal();
                            case "NEXT_HINT_CONSTRUCTION" -> 39; // STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal();
                            case "PREPROCESSING_VOTES" -> 40; // STATE_ID_PREPROCESSING_VOTES.protoOrdinal();
                            case "CRS_STATE" -> 48; // STATE_ID_CRS_STATE.protoOrdinal();
                            case "CRS_PUBLICATIONS" -> 49; // STATE_ID_CRS_PUBLICATIONS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HistoryService" ->
                        switch (stateKey) {
                            case "LEDGER_ID" -> 42; // STATE_ID_LEDGER_ID.protoOrdinal();
                            case "PROOF_KEY_SETS" -> 43; // STATE_ID_PROOF_KEY_SETS.protoOrdinal();
                            case "ACTIVE_PROOF_CONSTRUCTION" ->
                                44; // STATE_ID_ACTIVE_PROOF_CONSTRUCTION.protoOrdinal();
                            case "NEXT_PROOF_CONSTRUCTION" -> 45; // STATE_ID_NEXT_PROOF_CONSTRUCTION.protoOrdinal();
                            case "HISTORY_SIGNATURES" -> 46; // STATE_ID_HISTORY_SIGNATURES.protoOrdinal();
                            case "PROOF_VOTES" -> 47; // STATE_ID_PROOF_VOTES.protoOrdinal();
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
     * Validates that the state ID for the given service name and state key is within valid range.
     *
     * @param serviceName the service name
     * @param stateKey the state key
     * @return the validated state ID (between 0 and 65535 inclusive)
     * @throws IllegalArgumentException if the state ID is outside the valid range
     */
    public static int getValidatedStateId(@NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = stateIdFor(serviceName, stateKey);
        if (stateId < 0 || stateId > 65535) {
            throw new IllegalArgumentException("State ID " + stateId + " must fit in [0..65535]");
        }
        return stateId;
    }

    /**
     * Computes the label for a Merkle node given the service name and state key.
     * <p>
     * The label is computed as "serviceName.stateKey". The result is cached so that repeated calls
     * with the same parameters return the same string without redoing the concatenation.
     * </p>
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return the computed label
     */
    public static String computeLabel(@NonNull final String serviceName, @NonNull final String stateKey) {
        final String key = Objects.requireNonNull(serviceName) + "." + Objects.requireNonNull(stateKey);
        return LABEL_CACHE.computeIfAbsent(key, k -> k);
    }

    /**
     * Decomposes a computed label into its service name and state key components.
     * <p>
     * This method performs the inverse operation of {@link #computeLabel(String, String)}.
     * It assumes the label is in the format "serviceName.stateKey".
     * </p>
     *
     * @param label the computed label
     * @return a {@link Pair} where the left element is the service name and the right element is the state key
     * @throws IllegalArgumentException if the label does not contain a period ('.') as expected
     * @throws NullPointerException     if the label is {@code null}
     */
    public static Pair<String, String> decomposeLabel(final String label) {
        Objects.requireNonNull(label, "Label must not be null");

        int delimiterIndex = label.indexOf('.');
        if (delimiterIndex < 0) {
            throw new IllegalArgumentException("Label must be in the format 'serviceName.stateKey'");
        }

        final String serviceName = label.substring(0, delimiterIndex);
        final String stateKey = label.substring(delimiterIndex + 1);
        return Pair.of(serviceName, stateKey);
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a singleton state, serializes into a {@link Bytes} object
     * and returns it.
     * The result is cached to avoid repeated allocations.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return a {@link VirtualMapKey} for the singleton serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForSingleton(
            @NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        return getVirtualMapKeyForSingleton(stateId);
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a singleton state, serializes into a {@link Bytes} object
     * and returns it.
     * The result is cached to avoid repeated allocations.
     *
     * @param stateId the state id
     * @return a {@link VirtualMapKey} for the singleton serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForSingleton(int stateId) {
        Bytes key = VIRTUAL_MAP_KEY_CACHE[stateId];
        if (key == null) {
            int singletonTag = 1 << TAG_FIELD_OFFSET;

            ByteBuffer byteBuffer = ByteBuffer.allocate(sizeOfVarInt32(singletonTag) + sizeOfVarInt32(stateId));
            BufferedData bufferedData = BufferedData.wrap(byteBuffer);
            bufferedData.writeVarInt(singletonTag, false);
            bufferedData.writeVarInt(stateId, false);
            key = Bytes.wrap(byteBuffer.array());
            VIRTUAL_MAP_KEY_CACHE[stateId] = key;
        }
        return key;
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param index       the queue element index
     * @return a {@link VirtualMapKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForQueue(
            @NonNull final String serviceName, @NonNull final String stateKey, final long index) {
        int validatedStateId = getValidatedStateId(serviceName, stateKey);
        return getVirtualMapKeyForQueue(validatedStateId, index);
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param stateId the state id
     * @param index       the queue element index
     * @return a {@link VirtualMapKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForQueue(final int stateId, final long index) {
        return VirtualMapKey.PROTOBUF.toBytes(
                new VirtualMapKey(new OneOf<>(VirtualMapKey.KeyOneOfType.fromProtobufOrdinal(stateId), index)));
    }

    /**
     * Creates Protocol Buffer encoded byte array for a VirtualMapKey field.
     * Follows protobuf encoding format: tag (field number + wire type), length, and value.
     *
     * @param serviceName       the service name
     * @param stateKey          the state key
     * @param keyObjectBytes    the serialized key object
     * @return Properly encoded Protocol Buffer byte array
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes createVirtualMapKeyBytesForKv(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull Bytes keyObjectBytes) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        return createVirtualMapKeyBytesForKv(stateId, keyObjectBytes);
    }

    /**
     * Creates Protocol Buffer encoded byte array for a VirtualMapKey field.
     * Follows protobuf encoding format: tag (field number + wire type), length, and value.
     *
     * @param stateId          the state id
     * @param keyObjectBytes   the serialized key object
     * @return Properly encoded Protocol Buffer byte array
     * @throws IllegalArgumentException if the stateId is not within the range [0..65535]
     */
    public static Bytes createVirtualMapKeyBytesForKv(final int stateId, @NonNull Bytes keyObjectBytes) {
        // This matches the Protocol Buffer tag format: (field_number << TAG_TYPE_BITS) | wire_type
        final int tag = (stateId << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();

        ByteBuffer byteBuffer = ByteBuffer.allocate(sizeOfVarInt32(tag)
                + sizeOfVarInt32(toIntExact(keyObjectBytes.length())) /* length */
                + toIntExact(keyObjectBytes.length()) /* key bytes */);
        BufferedData bufferedData = BufferedData.wrap(byteBuffer);

        bufferedData.writeVarInt(tag, false);
        bufferedData.writeVarInt(toIntExact(keyObjectBytes.length()), false);
        bufferedData.writeBytes(keyObjectBytes);

        return Bytes.wrap(byteBuffer.array());
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param index       the queue element index
     * @return a {@link VirtualMapKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes createVirtualMapKeyBytesForQueue(
            @NonNull final String serviceName, @NonNull final String stateKey, final long index) {
        int validatedStateId = getValidatedStateId(serviceName, stateKey);
        return createVirtualMapKeyBytesForQueue(validatedStateId, index);
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param stateId the state id
     * @param index       the queue element index
     * @return a {@link VirtualMapKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes createVirtualMapKeyBytesForQueue(int stateId, long index) {
        int tag = stateId << TAG_FIELD_OFFSET;

        ByteBuffer byteBuffer = ByteBuffer.allocate(sizeOfVarInt32(tag) + sizeOfVarInt64(index) /* queue index */);
        BufferedData bufferedData = BufferedData.wrap(byteBuffer);

        bufferedData.writeVarInt(tag, false);
        bufferedData.writeVarLong(index, false);

        return Bytes.wrap(byteBuffer.array());
    }

    private BinaryStateUtils() {
        // Utility class, no instantiation allowed
    }
}
