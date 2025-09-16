// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.disk.QueueState;
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
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

/** Utility class for working with states. */
public final class StateUtils {

    // This has to match virtual_map_state.proto
    public static final int STATE_VALUE_QUEUE_STATE = 8001;

    /** Cache for pre-computed virtual map keys for singleton states. */
    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];

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
     * an on-disk state's value merkle type to be deserialized, answering with the generated class ID.
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
                            computeLabel(md.serviceName(), md.stateDefinition().stateKey()),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec(),
                            null)));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    QueueNode.class,
                    () -> new QueueNode<>(
                            computeLabel(md.serviceName(), md.stateDefinition().stateKey()),
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
     * Creates a state key for a singleton state and serializes into a {@link Bytes} object.
     * The result is cached to avoid repeated allocations.
     *
     * @param stateId the state ID
     * @return a state key for the singleton serialized into {@link Bytes} object
     */
    public static Bytes getStateKeyForSingleton(final int stateId) {
        Bytes key = VIRTUAL_MAP_KEY_CACHE[stateId];
        if (key == null) {
            key = StateKeyUtils.singletonKey(stateId);
            VIRTUAL_MAP_KEY_CACHE[stateId] = key;
        }
        return key;
    }

    /**
     * Creates a state key for a queue element and serializes into a {@link Bytes} object.
     *
     * @param stateId the state ID
     * @param index the queue element index
     * @return a state key for a queue element serialized into {@link Bytes} object
     */
    public static Bytes getStateKeyForQueue(final int stateId, final long index) {
        return StateKeyUtils.queueKey(stateId, index);
    }

    /**
     * Creates a state key for a k/v state and serializes into a {@link Bytes} object.
     *
     * @param <K> the type of the key
     * @param stateId the state ID
     * @param key the key object
     * @return a state key for a k/v state, serialized into {@link Bytes} object
     */
    public static <K> Bytes getStateKeyForKv(final int stateId, final K key, final Codec<K> keyCodec) {
        return StateKeyUtils.kvKey(stateId, key, keyCodec);
    }

    /**
     * For a singleton value object, creates an instance of {@link StateValue} that can be
     * stored in a virtual map for the corresponding {@link #getStateKeyForSingleton(int)} key.
     *
     * @param <V> singleton value type
     * @param stateId the singleton state ID
     * @param value the value object
     * @return the {@link StateValue} object
     */
    public static <V> StateValue<V> getStateValueForSingleton(final int stateId, final V value) {
        return new StateValue<>(stateId, value);
    }

    /**
     * For a queue value object, creates an instance of {@link StateValue} that can be
     * stored in a virtual map for the corresponding {@link #getStateKeyForQueue(int, long)} key.
     *
     * @param <V> queue value type
     * @param stateId the queue state ID
     * @param value the value object
     * @return the {@link StateValue} object
     */
    public static <V> StateValue<V> getStateValueForQueue(final int stateId, final V value) {
        return new StateValue<>(stateId, value);
    }

    /**
     * For a queue state object, creates an instance of {@link StateValue} that can be
     * stored in a virtual map. Queue states are stored as singletons.
     *
     * @param queueState the queue state
     * @return the {@link StateValue} object
     */
    public static StateValue<QueueState> getStateValueForQueueState(final QueueState queueState) {
        return new StateValue<>(STATE_VALUE_QUEUE_STATE, queueState);
    }

    /**
     * For a value object, creates an instance of {@link StateValue} that can be
     * stored in a virtual map for the corresponding {@link #getStateKeyForKv(int, Object, Codec)} key.
     *
     * @param <V> value type
     * @param stateId the key/value state ID
     * @param value the value object
     * @return the {@link StateValue} object
     */
    public static <V> StateValue<V> getStateValueForKv(final int stateId, final V value) {
        return new StateValue<>(stateId, value);
    }
}
