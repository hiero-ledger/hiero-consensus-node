// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static java.lang.StrictMath.toIntExact;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * A set of utility methods to work with state keys. These methods are used by readable
 * and writable state classes to create keys to read from or write to the underlying store
 * (virtual map).
 *
 * <p>There are two reasons for this class to exist. First, to avoid HAPI dependency in
 * the swirlds-state-impl module. In particular, there should be no references to
 * com.hedera.hapi.platform.state.StateKey class. However, at the bytes level, all keys
 * created with methods in this class must be fully bit to bit compatible with Hedera
 * state keys. In particular, every state key must be a domain key wrapped into a OneOf.
 * This corresponds to StateKey.key OneOf defined in virtual_map_state.proto.
 *
 * <p>The second reason is to let create state keys with state IDs, which aren't
 * necessarily state IDs used in Hedera. Key types may also be different from Hedera
 * types. For example, state ID 2 in Hedera is for accounts, key type is AccountID, and
 * value type is Account. Using {@link #kvKey(int, Object, Codec)} method it is possible
 * to create a K/V key for state ID 2 with a different key type, all is needed is a key
 * object and the corresponding key codec. Another example is state ID 11111, it doesn't
 * correspond to any real Hedera state. If an instance of com.hedera.hapi.platform.state.StateKey
 * is created for state ID 11111, an exception will be thrown. However, it's possible to
 * create keys with this state ID using methods in this class.
 */
public class StateKeyUtils {

    // StateKey.key OneOf field number for singletons
    private static final int FIELD_NUM_SINGLETON = 1;

    private StateKeyUtils() {}

    // Singleton key: OneOf field number is FIELD_NUM_SINGLETON (1), field value is varint,
    // the value is singleton state ID
    public static Bytes singletonKey(final int stateId) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            // Write tag: field number == FIELD_NUM_SINGLETON (1), wire type == VARINT
            out.writeVarInt(
                    (FIELD_NUM_SINGLETON << ProtoParserTools.TAG_FIELD_OFFSET)
                            | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal(),
                    false);
            // Write varint value, singleton state ID
            out.writeVarInt(stateId, false);
            return Bytes.wrap(bout.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Queue key: OneOf field number is queue state ID, field value is varint, the value
    // is a long index in the queue
    public static Bytes queueKey(final int stateId, final long index) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            // Write tag: field number == state ID, wire type == VARINT
            out.writeVarInt(
                    (stateId << ProtoParserTools.TAG_FIELD_OFFSET)
                            | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal(),
                    false);
            // Write index, varlong
            out.writeVarLong(index, false);
            return Bytes.wrap(bout.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Queue state key: same as a singleton with the corresponding state ID
    public static Bytes queueStateKey(final int stateId) {
        return singletonKey(stateId);
    }

    // K/V key: OneOf field number is K/V state ID, field value is the key
    public static <K> Bytes kvKey(final int stateId, final K key, final Codec<K> keyCodec) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            // Write tag: field number == state ID, wire type == DELIMITED
            out.writeVarInt(
                    (stateId << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal(),
                    false);
            // Write length, varint
            out.writeVarInt(keyCodec.measureRecord(key), false);
            // Write key
            keyCodec.write(key, out);
            return Bytes.wrap(bout.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // K/V key: OneOf field number is K/V state ID, field value is the key
    public static Bytes kvKey(final int stateId, final Bytes key) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            // Write tag: field number == state ID, wire type == DELIMITED
            out.writeVarInt(
                    (stateId << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal(),
                    false);
            // Write length, varint
            out.writeVarInt(toIntExact(key.length()), false);
            // Write key
            out.writeBytes(key);
            return Bytes.wrap(bout.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Given state key bytes, extract state ID from them. Key bytes must be in
     * com.hedera.hapi.platform.state.StateKey format, that it a domain key wrapped into a
     * OneOf field.
     */
    public static int extractStateIdFromStateKeyOneOf(@NonNull final Bytes stateKey) {
        Objects.requireNonNull(stateKey, "Null state key");
        // Assumption is the key bytes are a OneOf
        return ProtoParserTools.readNextFieldNumber(stateKey.toReadableSequentialData());
    }

    /**
     * Given state key bytes, extract a key from them. Key bytes must be in
     * com.hedera.hapi.platform.state.StateKey format, that it a domain key wrapped into a
     * OneOf field.
     *
     * @throws ParseException if domain key bytes cannot be parsed using the provided codec
     */
    public static <K> K extractKeyFromStateKeyOneOf(@NonNull final Bytes stateKey, @NonNull final Codec<K> keyCodec)
            throws ParseException {
        Objects.requireNonNull(stateKey, "Null state key");
        Objects.requireNonNull(keyCodec, "Null key codec");
        final ReadableSequentialData in = stateKey.toReadableSequentialData();
        final int tag = in.readVarInt(false);
        assert tag >> ProtoParserTools.TAG_FIELD_OFFSET == extractStateIdFromStateKeyOneOf(stateKey);
        assert tag >> ProtoParserTools.TAG_FIELD_OFFSET != FIELD_NUM_SINGLETON; // must not be a singleton key
        final int size = in.readVarInt(false);
        assert in.position() + size == stateKey.length();
        return keyCodec.parse(in);
    }
}
