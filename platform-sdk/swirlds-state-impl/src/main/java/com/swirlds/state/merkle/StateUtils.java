// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.state.merkle.disk.QueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
