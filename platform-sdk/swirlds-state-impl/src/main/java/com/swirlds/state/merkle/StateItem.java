// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static java.lang.StrictMath.toIntExact;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * A record to store state items.
 *
 * <p>This class is very similar to a class with the same name
 * generated from HAPI sources, com.hedera.hapi.platform.state.StateItem. The
 * generated class is not used in the current module to avoid a compile-time
 * dependency on HAPI.
 *
 * <p>At the bytes level, these two classes must be bit to bit identical. It means,
 * bytes for a state value record serialized using {@link StateItem.StateItemCodec} must be
 * identical to bytes created using HAPI StateItem and its codec. These bytes are
 * a protobuf OneOf field with ID that corresponds to the state ID, field value is
 * the domain value message. See StateValue.value OneOf definition in
 * virtual_map_state.proto for details.
 *
 * <p>This record can be used to create state values with state IDs different from
 * what are used in Hedera. Value types may also be different, not just Hedera types,
 * which is very helpful for testing.
 *
 * @param key key bytes
 * @param value state value object wrapping a domain value object
 */
public record StateItem(@NonNull Bytes key, @NonNull Bytes value) {
    public static final Codec<StateItem> CODEC = new StateItemCodec();

    public StateItem {
        requireNonNull(key, "Null key");
        requireNonNull(value, "Null value");
    }

    /**
     * Protobuf Codec for StateItem model object. Generated based on protobuf schema.
     */
    public static final class StateItemCodec implements Codec<StateItem> {

        public static final int KEY_FIELD_ORDINAL = 2;
        public static final int VALUE_FIELD_ORDINAL = 3;

        /**
         * Parses a StateItem object from ProtoBuf bytes in a {@link ReadableSequentialData}. Throws if in strict mode ONLY.
         *
         * @param input              The data input to parse data from, it is assumed to be in a state ready to read with position at start
         *                           of data to read and limit set at the end of data to read. The data inputs limit will be changed by this
         *                           method. If there are no bytes remaining in the data input,
         *                           then the method also returns immediately.
         * @param strictMode         when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
         * @param parseUnknownFields when {@code true} and strictMode is {@code false}, the parser will collect unknown
         *                           fields in the unknownFields list in the model; otherwise they'll be simply skipped.
         * @param maxDepth           a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
         * @return Parsed StateItem model object or null if data input was null or empty
         * @throws ParseException If parsing fails
         */
        public @NonNull StateItem parse(
                @NonNull final ReadableSequentialData input,
                final boolean strictMode,
                final boolean parseUnknownFields,
                final int maxDepth,
                final int maxSize)
                throws ParseException {
            // read key tag
            final int keyTag = input.readVarInt(false);
            final int keyFieldNum = keyTag >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (keyFieldNum != KEY_FIELD_ORDINAL) {
                throw new ParseException(
                        "StateItem key field num mismatch: expected=" + KEY_FIELD_ORDINAL + ", actual=" + keyFieldNum);
            }
            final int wireType = keyTag & ProtoConstants.TAG_WIRE_TYPE_MASK;
            if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                throw new ParseException("StateItem key wire type mismatch: expected="
                        + ProtoConstants.WIRE_TYPE_DELIMITED.ordinal() + ", actual=" + wireType);
            }
            final int keySize = input.readVarInt(false);
            final Bytes keyBytes;
            if (keySize == 0) {
                keyBytes = Bytes.EMPTY;
            } else {
                keyBytes = input.readBytes(keySize);
            }

            final int valueTag = input.readVarInt(false);
            final int valueFieldNum = valueTag >> ProtoParserTools.TAG_FIELD_OFFSET;

            if (valueFieldNum != VALUE_FIELD_ORDINAL) {
                throw new ParseException("StateItem value field num mismatch: expected=" + VALUE_FIELD_ORDINAL
                        + ", actual=" + keyFieldNum);
            }
            final int valueSize = input.readVarInt(false);
            final Bytes valueBytes;
            if (valueSize == 0) {
                valueBytes = Bytes.EMPTY;
            } else {
                valueBytes = input.readBytes(valueSize);
            }

            return new StateItem(keyBytes, valueBytes);
        }

        /**
         * Write out a StateItem model to output stream in protobuf format.
         *
         * @param data The input model data to write
         * @param out  The output stream to write to
         * @throws IOException If there is a problem writing
         */
        public void write(@NonNull StateItem data, @NonNull final WritableSequentialData out) throws IOException {
            // [2] - key
            out.writeVarInt((KEY_FIELD_ORDINAL << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal(), false);
            // Write size
            out.writeVarInt(toIntExact(data.key.length()), false);
            // Write key
            out.writeBytes(data.key);

            // [3] - key
            out.writeVarInt((VALUE_FIELD_ORDINAL << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal(), false);
            // Write size
            out.writeVarInt(toIntExact(data.value.length()), false);
            // Write value
            out.writeBytes(data.value.toByteArray());
        }

        /**
         * {@inheritDoc}
         */
        public int measure(@NonNull final ReadableSequentialData input) throws ParseException {
            final var start = input.position();
            parse(input);
            final var end = input.position();
            return (int) (end - start);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int measureRecord(StateItem item) {
            int size = 0;
            // Key tag size
            size += ProtoWriterTools.sizeOfVarInt32((KEY_FIELD_ORDINAL << ProtoParserTools.TAG_FIELD_OFFSET)
                    | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal());
            // Key size
            size += toIntExact(item.key.length());

            // value tag size
            size += ProtoWriterTools.sizeOfVarInt32((VALUE_FIELD_ORDINAL << ProtoParserTools.TAG_FIELD_OFFSET)
                    | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal());

            size += toIntExact(item.value.length());

            return size;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean fastEquals(@NonNull StateItem item, @NonNull ReadableSequentialData input)
                throws ParseException {
            return item.equals(parse(input));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public StateItem getDefaultInstance() {
            return new StateItem(Bytes.EMPTY, Bytes.EMPTY);
        }
    }
}
