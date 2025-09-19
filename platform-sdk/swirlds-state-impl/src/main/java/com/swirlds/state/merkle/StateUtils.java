// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.ProtoParserTools.readNextFieldNumber;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;

import com.hedera.hapi.platform.state.QueueState;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.lifecycle.HapiUtils;
import com.swirlds.state.lifecycle.StateMetadata;
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
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

/** Utility class for working with states. */
public final class StateUtils {

    /** Cache for pre-computed virtual map keys for singleton states. */
    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];

    /** Cache to store and retrieve pre-computed labels for specific service states. */
    private static final Map<String, String> LABEL_CACHE = new ConcurrentHashMap<>();

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
     * Registers with the {@link ConstructableRegistry} system a class ID and a class. While this
     * will only be used for in-memory states, it is safe to register for on-disk ones as well.
     *
     * <p>The implementation will take the service name and the state key and compute a hash for it.
     * It will then convert the hash to a long, and use that as the class ID. It will then register
     * an {@link InMemoryWritableKVState}'s value merkle type to be deserialized, answering with the
     * generated class ID.
     *
     * @deprecated Registrations should be removed when there are no longer any objects of the relevant class.
     * Once all registrations have been removed, this method itself should be deleted.
     * See <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/19416">GitHub issue</a>.
     *
     * @param md The state metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Deprecated
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
            // FUTURE WORK: remove OnDiskKey registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKey.class,
                    () -> new OnDiskKey<>(
                            md.onDiskKeyClassId(), md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskKeySerializer registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKeySerializer.class,
                    () -> new OnDiskKeySerializer<>(
                            md.onDiskKeySerializerClassId(),
                            md.onDiskKeyClassId(),
                            md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskValue registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValue.class,
                    () -> new OnDiskValue<>(
                            md.onDiskValueClassId(), md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskValueSerializer registration, once there are no objects of this class
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
     * Validates that the state ID for the given service name and state key is within valid range.
     *
     * @param serviceName the service name
     * @param stateKey the state key
     * @return the validated state ID (between 0 and 65535 inclusive)
     * @throws IllegalArgumentException if the state ID is outside the valid range
     */
    private static int getValidatedStateId(@NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = HapiUtils.stateIdFor(serviceName, stateKey);
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
     * Creates an instance of {@link StateKey} for a singleton state, serializes into a {@link Bytes} object
     * and returns it.
     * The result is cached to avoid repeated allocations.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return a {@link StateKey} for the singleton serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getStateKeyForSingleton(@NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        Bytes key = VIRTUAL_MAP_KEY_CACHE[stateId];
        if (key == null) {
            key = StateKey.PROTOBUF.toBytes(new StateKey(
                    new OneOf<>(StateKey.KeyOneOfType.SINGLETON, SingletonType.fromProtobufOrdinal(stateId))));
            VIRTUAL_MAP_KEY_CACHE[stateId] = key;
        }
        return key;
    }

    /**
     * Creates an instance of {@link StateKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param index       the queue element index
     * @return a {@link StateKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getStateKeyForQueue(
            @NonNull final String serviceName, @NonNull final String stateKey, final long index) {
        return StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(
                StateKey.KeyOneOfType.fromProtobufOrdinal(getValidatedStateId(serviceName, stateKey)), index)));
    }

    /**
     * Creates an instance of {@link StateKey} for a k/v state, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param <K>         the type of the key
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param key         the key object
     * @return a {@link StateKey} for a k/v state, serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static <K> Bytes getStateKeyForKv(
            @NonNull final String serviceName, @NonNull final String stateKey, final K key) {
        return StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(
                StateKey.KeyOneOfType.fromProtobufOrdinal(getValidatedStateId(serviceName, stateKey)), key)));
    }

    /**
     * Creates an instance of {@link StateValue} which is stored in a state.
     *
     * @param <V>         the type of the value
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param value       the value object
     * @return a {@link StateValue} for a {@link com.swirlds.virtualmap.VirtualMap}
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static <V> StateValue getStateValue(
            @NonNull final String serviceName, @NonNull final String stateKey, final V value) {
        return new StateValue(new OneOf<>(
                StateValue.ValueOneOfType.fromProtobufOrdinal(getValidatedStateId(serviceName, stateKey)), value));
    }

    /**
     * Creates an instance of {@link StateValue} for a {@link com.hedera.hapi.platform.state.QueueState} which is stored in a state.
     *
     * @param queueState the value object
     * @return a {@link StateValue} for {@link com.hedera.hapi.platform.state.QueueState} in a {@link com.swirlds.virtualmap.VirtualMap}
     */
    public static StateValue getQueueStateValue(@NonNull final QueueState queueState) {
        return new StateValue(new OneOf<>(StateValue.ValueOneOfType.QUEUE_STATE, queueState));
    }

    /**
     * Creates Protocol Buffer encoded byte array for either a {@link StateKey} or a {@link StateValue} field.
     * Follows protobuf encoding format: tag (field number + wire type), length, and value.
     *
     * @param serviceName       the service name
     * @param stateKey          the state key
     * @param objectBytes       the serialized key or value object
     * @return Properly encoded Protocol Buffer byte array
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getStateKeyValueBytes(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final Bytes objectBytes) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        // This matches the Protocol Buffer tag format: (field_number << TAG_TYPE_BITS) | wire_type
        int tag = (stateId << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
        int length = Math.toIntExact(objectBytes.length());

        ByteBuffer byteBuffer =
                ByteBuffer.allocate(sizeOfVarInt32(tag) + sizeOfVarInt32(length) /* length */ + length /* key bytes */);
        BufferedData bufferedData = BufferedData.wrap(byteBuffer);

        bufferedData.writeVarInt(tag, false);
        bufferedData.writeVarInt(length, false);
        bufferedData.writeBytes(objectBytes);

        return Bytes.wrap(byteBuffer.array());
    }

    /**
     * Extracts the state ID from a serialized {@link StateKey} or {@link StateValue}.
     * <p>
     * This method reads the next protobuf field number (the one-of field number) from the key's or value's
     * sequential data, which corresponds to the embedded state ID.
     * </p>
     *
     * @param objectBytes the serialized {@link StateKey} or {@link StateValue} bytes
     * @return the extracted state ID
     * @throws NullPointerException if {@code objectBytes} is null
     */
    public static int extractStateKeyValueStateId(@NonNull final Bytes objectBytes) {
        Objects.requireNonNull(objectBytes, "objectBytes must not be null");
        // Rely on the fact that StateKey and StateValue has a single OneOf field,
        // so the next field number is the state ID.
        return readNextFieldNumber(objectBytes.toReadableSequentialData());
    }
}
