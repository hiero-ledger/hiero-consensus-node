/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.merkle;

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
import static com.swirlds.common.utility.CommonUtils.getNormalisedStringBytes;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.utility.NonCryptographicHashing;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.merkle.memory.InMemoryWritableKVState;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility class for working with states. */
public final class StateUtils {

    private static final Logger logger = LogManager.getLogger(StateUtils.class);
    private static final int UNKNOWN_STATE_ID = -1;

    /** Prevent instantiation */
    private StateUtils() {}

    /**
     * Write the {@code object} to the {@link OutputStream} using the given {@link Codec}.
     *
     * @param out The object to write out
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @param object The object to write
     * @return The number of bytes written to the stream.
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the output stream throws it.
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    public static <T> int writeToStream(
            @NonNull final OutputStream out, @NonNull final Codec<T> codec, @Nullable final T object)
            throws IOException {
        final var stream = new WritableStreamingData(out);

        final var byteStream = new ByteArrayOutputStream();
        codec.write(object, new WritableStreamingData(byteStream));

        stream.writeInt(byteStream.size());
        stream.writeBytes(byteStream.toByteArray());
        return byteStream.size();
    }

    /**
     * Read an object from the {@link InputStream} using the given {@link Codec}.
     *
     * @param in The input stream to read from
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @return The object read from the stream
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the input stream throws it or parsing fails
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    @Nullable
    public static <T> T readFromStream(@NonNull final InputStream in, @NonNull final Codec<T> codec)
            throws IOException {
        final var stream = new ReadableStreamingData(in);
        final var size = stream.readInt();

        stream.limit((long) size + Integer.BYTES); // +4 for the size
        try {
            return codec.parse(stream);
        } catch (final ParseException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Verifies the service name meets all the validation requirements.
     *
     * @param serviceName The service name
     * @return The service name provided as the argument
     * @throws NullPointerException if the service name is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    public static String validateServiceName(@NonNull final String serviceName) {
        if (Objects.requireNonNull(serviceName).isEmpty()) {
            throw new IllegalArgumentException("The service name must have characters");
        }

        return validateIdentifier(serviceName);
    }

    /**
     * Verifies the state key meets all the validation requirements.
     *
     * @param stateKey The state key
     * @return The state key provided as the argument
     * @throws NullPointerException if the state key is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    public static String validateStateKey(@NonNull final String stateKey) {
        if (Objects.requireNonNull(stateKey).isEmpty()) {
            throw new IllegalArgumentException("The state key must have characters");
        }

        return validateIdentifier(stateKey);
    }

    /**
     * Verifies the identifier meets all the validation requirements.
     *
     * @param stateKey The identifier
     * @return The identifier provided as the argument
     * @throws NullPointerException if the identifier is null
     * @throws IllegalArgumentException if any other validation criteria fails
     */
    @NonNull
    static String validateIdentifier(@NonNull final String stateKey) {
        if (Objects.requireNonNull(stateKey).isEmpty()) {
            throw new IllegalArgumentException("The identifier must have characters");
        }

        for (int i = 0; i < stateKey.length(); i++) {
            final var c = stateKey.charAt(i);
            if (!isAsciiUnderscoreOrDash(c) && !isAsciiLetter(c) && !isAsciiNumber(c)) {
                throw new IllegalArgumentException("Illegal character '" + c + "' at position " + i);
            }
        }

        return stateKey;
    }

    /**
     * Given the inputs, compute the corresponding class ID.
     *
     * @param extra An extra string to bake into the class id
     * @return the class id
     */
    public static long computeClassId(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final SemanticVersion version,
            @NonNull final String extra) {
        // NOTE: Once this is live on any network, the formula used to generate this key can NEVER
        // BE CHANGED or you won't ever be able to deserialize an exising state! If we get away from
        // this formula, we will need to hardcode known classId that had been previously generated.
        final var ver = "v" + version.major() + "." + version.minor() + "." + version.patch();
        return hashString(serviceName + ":" + stateKey + ":" + ver + ":" + extra);
    }

    // Will be moved to `NonCryptographicHashing` with
    // https://github.com/swirlds/swirlds-platform/issues/6421
    static long hashString(@NonNull final String s) {
        // Normalize the string so things are deterministic (different JVMs might be using different
        // default internal representation for strings, and we need to normalize that)
        final var data = getNormalisedStringBytes(s);
        long l = hashBytes(data);
        logger.debug(LogMarker.STARTUP.getMarker(), "Hashed string {} to {}", s, l);
        return l;
    }

    // Will be moved to `NonCryptographicHashing` with
    // https://github.com/swirlds/swirlds-platform/issues/6421
    private static long hashBytes(@NonNull final byte[] bytes) {
        // Go through the bytes. Conceptually, we get 8 bytes at a time, joined as a long,
        // and then xor it with our running hash. Then pass that into the hash64 function.
        // First, we divide the byte array into 8-byte blocks, and process them.
        final int numBlocks = bytes.length / 8;
        long hash = 0;
        for (int i = 0; i < (numBlocks * 8); i += 8) {
            hash ^= ((long) bytes[i] << 56);
            hash ^= ((long) bytes[i + 1] << 48);
            hash ^= ((long) bytes[i + 2] << 40);
            hash ^= ((long) bytes[i + 3] << 32);
            hash ^= (bytes[i + 4] << 24);
            hash ^= (bytes[i + 5] << 16);
            hash ^= (bytes[i + 6] << 8);
            hash ^= (bytes[i + 7]);
            hash = NonCryptographicHashing.hash64(hash);
        }

        // There may be 0 <= N <= 7 remaining bytes. Process these bytes
        final int numRemainingBytes = bytes.length - (numBlocks * 8);
        if (numRemainingBytes > 0) {
            for (int shift = numRemainingBytes * 8, i = (numBlocks * 8); i < bytes.length; i++, shift -= 8) {
                hash ^= ((long) bytes[i] << shift);
            }
            hash = NonCryptographicHashing.hash64(hash);
        }

        // We're done!
        return hash;
    }

    /**
     * Simple utility for checking whether the character is an underscore or dash on the ASCII
     * table.
     *
     * @param ch The character to check
     * @return True if the character is the underscore or dash character on the ascii table
     */
    private static boolean isAsciiUnderscoreOrDash(char ch) {
        return ch == '-' || ch == '_';
    }

    /**
     * Simple utility for checking whether the given character is A-Z or a-z on the ascii table.
     *
     * @param ch The character to check
     * @return True if the character is A-Z or a-z
     */
    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    /**
     * Simple utility to check whether the given character is a numeric digit between 0-9
     *
     * @param ch The character to check
     * @return True if the character is between ascii 0-9.
     */
    private static boolean isAsciiNumber(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     * Registers with the {@link ConstructableRegistry} system a class ID and a class. While this
     * will only be used for in-memory states, it is safe to register for on-disk ones as well.
     *
     * <p>The implementation will take the service name and the state key and compute a hash for it.
     * It will then convert the hash to a long, and use that as the class ID. It will then register
     * an {@link InMemoryWritableKVState}'s value merkle type to be deserialized, answering with the
     * generated class ID.
     *
     * @param md The state metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerWithSystem(
            @NonNull final StateMetadata md, @NonNull ConstructableRegistry constructableRegistry) {
        // Register with the system the uniqueId as the "classId" of an InMemoryValue. There can be
        // multiple id's associated with InMemoryValue. The secret is that the supplier captures the
        // various delegate writers and parsers, and so can parse/write different types of data
        // based on the id.
        try {
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    InMemoryValue.class,
                    () -> new InMemoryValue(
                            md.inMemoryValueClassId(),
                            md.stateDefinition().keyCodec(),
                            md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskKey registration, once there are no objects of this clas
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKey.class,
                    () -> new OnDiskKey<>(
                            md.onDiskKeyClassId(), md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskKeySerilalizer registration, once there are no objects of this clas
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKeySerializer.class,
                    () -> new OnDiskKeySerializer<>(
                            md.onDiskKeySerializerClassId(),
                            md.onDiskKeyClassId(),
                            md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskValue registration, once there are no objects of this clas
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValue.class,
                    () -> new OnDiskValue<>(
                            md.onDiskValueClassId(), md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskValueSerializer registration, once there are no objects of this clas
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValueSerializer.class,
                    () -> new OnDiskValueSerializer<>(
                            md.onDiskValueSerializerClassId(),
                            md.onDiskValueClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    SingletonNode.class,
                    () -> new SingletonNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec(),
                            null)));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    QueueNode.class,
                    () -> new QueueNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.queueNodeClassId(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    ValueLeaf.class,
                    () -> new ValueLeaf<>(
                            md.singletonClassId(), md.stateDefinition().valueCodec())));
        } catch (ConstructableRegistryException e) {
            // This is a fatal error.
            throw new IllegalStateException(
                    "Failed to register with the system '"
                            + md.serviceName()
                            + ":"
                            + md.stateDefinition().stateKey()
                            + "'",
                    e);
        }
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
                    case "FileService" -> switch (stateKey) {
                        case "FILES" -> STATE_ID_FILES.protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=150]]" -> STATE_ID_UPGRADE_DATA_150
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=151]]" -> STATE_ID_UPGRADE_DATA_151
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=152]]" -> STATE_ID_UPGRADE_DATA_152
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=153]]" -> STATE_ID_UPGRADE_DATA_153
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=154]]" -> STATE_ID_UPGRADE_DATA_154
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=155]]" -> STATE_ID_UPGRADE_DATA_155
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=156]]" -> STATE_ID_UPGRADE_DATA_156
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=157]]" -> STATE_ID_UPGRADE_DATA_157
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=158]]" -> STATE_ID_UPGRADE_DATA_158
                                .protoOrdinal();
                        case "UPGRADE_DATA[FileID[shardNum=0, realmNum=0, fileNum=159]]" -> STATE_ID_UPGRADE_DATA_159
                                .protoOrdinal();
                        case "UPGRADE_FILE" -> STATE_ID_UPGRADE_FILE.protoOrdinal();
                        default -> UNKNOWN_STATE_ID;
                    };
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
                        case "ACTIVE_HINTS_CONSTRUCTION" -> STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal();
                        case "NEXT_HINTS_CONSTRUCTION" -> STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal();
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
     * A static cache to store and retrieve pre-computed labels for specific service states.
     */
    private static final Map<String, String> LABEL_CACHE = new HashMap<>();

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

    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];

    // TODO: add tests for methods below

    /**
     * Generates a 2 bytes key with the state ID (big‑endian) for a Virtual Map.
     * The result is cached to avoid repeated allocations.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return a {@link Bytes} object containing the 2‑byte state ID in big‑endian order
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKey(@NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = stateIdFor(serviceName, stateKey);
        if (stateId < 0 || stateId > 65535) {
            throw new IllegalArgumentException("State ID " + stateId + " must fit in [0..65535]");
        }
        Bytes key = VIRTUAL_MAP_KEY_CACHE[stateId];
        if (key == null) {
            final byte[] bytes = new byte[Short.BYTES];
            BufferedData writer = BufferedData.wrap(bytes);
            writeUnsignedShort(writer, stateId);
            key = Bytes.wrap(bytes);
            VIRTUAL_MAP_KEY_CACHE[stateId] = key;
        }
        return key;
    }

    /**
     * Generates a 10 bytes key for a Virtual Map. Used for queue states.
     * <p>
     * The key structure is:
     * <ul>
     *   <li>2 bytes: the state ID (big‑endian)</li>
     *   <li>8 bytes: the index (big‑endian)</li>
     * </ul>
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param index       the queue element index
     * @return a {@link Bytes} object containing exactly 10 bytes in big‑endian order
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKey(
            @NonNull final String serviceName, @NonNull final String stateKey, final long index) {
        final int stateId = stateIdFor(serviceName, stateKey);
        if (stateId < 0 || stateId > 65535) {
            throw new IllegalArgumentException("State ID " + stateId + " must fit in [0..65535]");
        }
        final byte[] bytes = new byte[Short.BYTES + Long.BYTES];
        BufferedData writer = BufferedData.wrap(bytes);
        writeUnsignedShort(writer, stateId);
        writer.writeLong(index);
        return Bytes.wrap(bytes);
    }

    /**
     * Generates a key for a Virtual Map.
     * <p>
     * The key structure is:
     * <ul>
     *   <li>2 bytes: the state ID (big‑endian)</li>
     *   <li>N bytes: the key bytes (serialized form of the provided key)</li>
     * </ul>
     * If an {@link IOException} occurs during serialization, it is wrapped in a {@link RuntimeException}.
     *
     * @param <K>         the type of the key
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param key         the key to be serialized and appended
     * @param keyCodec    the codec used to serialize the key
     * @return a {@link Bytes} object consisting of the 2‑byte state ID (big‑endian) followed by the serialized key bytes
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     * @throws RuntimeException         if an {@link IOException} occurs during key serialization
     */
    public static <K> Bytes getVirtualMapKey(
            @NonNull final String serviceName, @NonNull final String stateKey, final K key, final Codec<K> keyCodec) {
        final int stateId = stateIdFor(serviceName, stateKey);
        if (stateId < 0 || stateId > 65535) {
            throw new IllegalArgumentException("State ID " + stateId + " must fit in [0..65535]");
        }
        final byte[] bytes = new byte[Short.BYTES + keyCodec.measureRecord(key)];
        BufferedData writer = BufferedData.wrap(bytes);
        writeUnsignedShort(writer, stateId);
        try {
            keyCodec.write(key, writer);
        } catch (IOException e) { // TODO: double check exception handling
            throw new RuntimeException(e);
        }
        return Bytes.wrap(bytes);
    }

    /**
     * Writes an unsigned 2‑byte value (a short) in big‑endian order into the given BufferedData.
     *
     * @param writer the BufferedData to write into
     * @param value  the unsigned 2‑byte value to write (must be in [0..65535])
     */
    private static void writeUnsignedShort(@NonNull final BufferedData writer, final int value) {
        writer.writeByte((byte) (value >>> 8));
        writer.writeByte((byte) value);
    }
}
