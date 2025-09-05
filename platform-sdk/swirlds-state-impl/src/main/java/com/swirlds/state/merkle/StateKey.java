// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

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

public class StateKey {

    // StateKey.key OneOf field number for singletons
    private static final int FIELD_NUM_SINGLETON = 1;

    private StateKey() {}

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

    public static int extractStateIdFromStateKey(@NonNull final Bytes stateKey) {
        Objects.requireNonNull(stateKey, "Null state key");
        return ProtoParserTools.readNextFieldNumber(stateKey.toReadableSequentialData());
    }

    public static <K> K parseKeyFromStateKey(@NonNull final Bytes stateKey, @NonNull final Codec<K> keyCodec)
            throws ParseException {
        Objects.requireNonNull(stateKey, "Null state key");
        Objects.requireNonNull(keyCodec, "Null key codec");
        final ReadableSequentialData in = stateKey.toReadableSequentialData();
        final int tag = in.readVarInt(false);
        assert tag >> ProtoParserTools.TAG_FIELD_OFFSET == extractStateIdFromStateKey(stateKey);
        assert tag >> ProtoParserTools.TAG_FIELD_OFFSET != FIELD_NUM_SINGLETON; // must not be a singleton key
        final int size = in.readVarInt(false);
        assert in.position() + size == stateKey.length();
        return keyCodec.parse(in);
    }
}
