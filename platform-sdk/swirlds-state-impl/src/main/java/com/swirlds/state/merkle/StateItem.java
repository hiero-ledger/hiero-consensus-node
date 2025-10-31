// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfTag;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeDelimited;
import static java.lang.StrictMath.toIntExact;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
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
 * identical to bytes created using HAPI StateItem and its codec. See StateValue definition in
 * virtual_map_state.proto for details.
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

        static final FieldDefinition FIELD_KEY = new FieldDefinition("keyBytes", FieldType.BYTES, false, 2);
        static final FieldDefinition FIELD_VALUE = new FieldDefinition("keyBytes", FieldType.BYTES, false, 3);

        /**
         * Parses a StateItem object from ProtoBuf bytes in a {@link ReadableSequentialData}. Throws if in strict mode ONLY.
         *
         * @param input              The data input to parse data from, it is assumed to be in a state ready to read with position at start
         *                           of data to read and limit set at the end of data to read. The data inputs limit will be changed by this
         *                           method. If there are no bytes remaining in the data input,
         *                           then the method also returns immediately.
         * @param strictMode         This parameter has no effect as {@code StateItem} has bytes only as fields
         * @param parseUnknownFields This parameter has no effect as {@code StateItem} has bytes only as fields
         * @param maxDepth           This parameter has no effect as {@code StateItem} has no nested fields
         * @return Parsed StateItem model object
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
            final int firstFieldNum = extractFieldNum(input);
            Bytes keyBytes = null;
            Bytes valueBytes = null;
            if (firstFieldNum == FIELD_KEY.number()) {
                keyBytes = readBytes(input, FIELD_KEY);
            } else if (firstFieldNum == FIELD_VALUE.number()) {
                valueBytes = readBytes(input, FIELD_VALUE);
            } else {
                throw new ParseException("StateItem unknown field num: " + firstFieldNum);
            }

            final int secondFieldNum = extractFieldNum(input);
            if (secondFieldNum == FIELD_KEY.number()) {
                keyBytes = readBytes(input, FIELD_KEY);
            } else if (secondFieldNum == FIELD_VALUE.number()) {
                valueBytes = readBytes(input, FIELD_VALUE);
            } else {
                throw new ParseException("StateItem unknown field num: " + secondFieldNum);
            }

            assert keyBytes != null;
            assert valueBytes != null;

            return new StateItem(keyBytes, valueBytes);
        }

        private static int extractFieldNum(ReadableSequentialData input) throws ParseException {
            final int tag = input.readVarInt(false);
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;
            if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                throw new ParseException("StateItem key wire type mismatch: expected="
                        + ProtoConstants.WIRE_TYPE_DELIMITED.ordinal() + ", actual=" + wireType);
            }
            return tag >> ProtoParserTools.TAG_FIELD_OFFSET;
        }

        private static Bytes readBytes(ReadableSequentialData input, FieldDefinition fieldDefinition)
                throws ParseException {
            final ProtoConstants wireType = ProtoWriterTools.wireType(fieldDefinition);
            if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED) {
                throw new ParseException("StateItem key wire type mismatch: expected="
                        + ProtoConstants.WIRE_TYPE_DELIMITED.ordinal() + ", actual=" + wireType);
            }

            Bytes keyBytes;
            final int keySize = input.readVarInt(false);
            if (keySize == 0) {
                keyBytes = Bytes.EMPTY;
            } else {
                keyBytes = input.readBytes(keySize);
            }
            return keyBytes;
        }

        /**
         * Write out a StateItem model to output stream in protobuf format.
         *
         * @param data The input model data to write
         * @param out  The output stream to write to
         * @throws IOException If there is a problem writing
         */
        public void write(@NonNull StateItem data, @NonNull final WritableSequentialData out) throws IOException {
            writeDelimited(out, FIELD_KEY, toIntExact(data.key.length()), v -> v.writeBytes(data.key));
            writeDelimited(out, FIELD_VALUE, toIntExact(data.value.length()), v -> v.writeBytes(data.value));
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

            size += sizeOfTag(FIELD_KEY);
            // key size counter size
            size += sizeOfVarInt32(toIntExact(item.key.length()));
            // Key size
            size += toIntExact(item.key.length());

            size += sizeOfTag(FIELD_VALUE);
            // value size counter size
            size += sizeOfVarInt32(toIntExact(item.value().length()));
            // value size
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
