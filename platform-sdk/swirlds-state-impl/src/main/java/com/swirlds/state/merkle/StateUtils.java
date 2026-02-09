// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;
import static com.swirlds.state.merkle.StateKeyUtils.kvKey;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.binary.QueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Utility class for working with states. */
public final class StateUtils {

    // This has to match virtual_map_state.proto
    public static final int STATE_VALUE_QUEUE_STATE = 8001;

    /** Cache for pre-computed virtual map keys for singleton states. */
    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];

    /** Prevent instantiation */
    private StateUtils() {}

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
        return kvKey(stateId, key, keyCodec);
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

    /**
     * Wrap raw value bytes into a StateValue oneof for the given stateId.
     */
    static Bytes wrapValue(final int stateId, @NonNull final Bytes rawValue) {
        // Build a protobuf StateValue message with a single length-delimited field number = stateId
        final int tag = (stateId << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
        final int valueLength = (int) rawValue.length();
        final int tagSize = sizeOfVarInt32(tag);
        final int valueSize = sizeOfVarInt32(valueLength);
        final int total = tagSize + valueSize + valueLength;
        final byte[] buffer = new byte[total];
        final BufferedData out = BufferedData.wrap(buffer);
        out.writeVarInt(tag, false);
        out.writeVarInt(valueLength, false);
        final int offset = (int) out.position();
        rawValue.writeTo(buffer, offset);
        return Bytes.wrap(buffer);
    }

    /**
     * Unwrap raw value bytes from state value bytes.
     *
     * @param stateValueBytes state value bytes
     * @return unwrapped raw value bytes
     */
    @NonNull
    public static Bytes unwrap(@NonNull final Bytes stateValueBytes) {
        ReadableSequentialData sequentialData = stateValueBytes.toReadableSequentialData();
        // skipping tag
        sequentialData.readVarInt(false);
        int valueSize = sequentialData.readVarInt(false);

        assert valueSize == sequentialData.remaining() : "Value size mismatch";

        try (InputStream is = sequentialData.asInputStream()) {
            return Bytes.wrap(is.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
