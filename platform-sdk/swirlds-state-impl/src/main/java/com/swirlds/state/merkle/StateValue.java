// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

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
import java.util.Objects;

public record StateValue<V>(int stateId, @NonNull V value) {

    public static int extractStateIdFromStateValue(@NonNull final Bytes stateValue) {
        Objects.requireNonNull(stateValue, "Null state value");
        return ProtoParserTools.readNextFieldNumber(stateValue.toReadableSequentialData());
    }

    public static final class StateValueCodec<V> implements Codec<StateValue<V>> {

        private final int stateId;

        private final Codec<V> valueCodec;

        public StateValueCodec(final int stateId, @NonNull Codec<V> valueCodec) {
            this.stateId = stateId;
            this.valueCodec = Objects.requireNonNull(valueCodec);
        }

        @Override
        public StateValue<V> getDefaultInstance() {
            // throw new UnsupportedOperationException("getDefaultInstance() must not be called");
            return new StateValue<>(stateId, valueCodec.getDefaultInstance());
        }

        @Override
        public int measureRecord(@NonNull final StateValue<V> value) {
            int size = 0;
            // Tag
            final int stateId = value.stateId();
            size += ProtoWriterTools.sizeOfVarInt32((stateId << ProtoParserTools.TAG_FIELD_OFFSET)
                    | ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal());
            // Size
            final int valueSize = valueCodec.measureRecord(value.value());
            size += ProtoWriterTools.sizeOfVarInt32(valueSize);
            if (valueSize > 0) {
                size += valueSize;
            }
            return size;
        }

        @Override
        public void write(@NonNull final StateValue<V> value, @NonNull final WritableSequentialData out)
                throws IOException {
            // Write tag
            final int stateId = value.stateId();
            out.writeVarInt(
                    (stateId << ProtoParserTools.TAG_FIELD_OFFSET) | ProtoConstants.WIRE_TYPE_DELIMITED.ordinal(),
                    false);
            // Write size
            final int valueSize = valueCodec.measureRecord(value.value());
            out.writeVarInt(valueSize, false);
            // Write value
            if (valueSize > 0) {
                valueCodec.write(value.value(), out);
            }
        }

        @NonNull
        @Override
        public StateValue<V> parse(
                @NonNull final ReadableSequentialData in,
                final boolean strictMode,
                final boolean parseUnknownFields,
                final int maxDepth)
                throws ParseException {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (fieldNum != stateId) {
                throw new ParseException("State ID num mismatch: expected=" + stateId + ", actual=" + fieldNum);
            }
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;
            if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                throw new ParseException("State ID wire type mismatch: expected="
                        + ProtoConstants.WIRE_TYPE_DELIMITED.ordinal() + ", actual=" + wireType);
            }
            final int size = in.readVarInt(false);
            final V value;
            if (size == 0) {
                value = valueCodec.getDefaultInstance();
            } else {
                final long limit = in.limit();
                in.limit(in.position() + size);
                value = valueCodec.parse(in, strictMode, parseUnknownFields, maxDepth);
                in.limit(limit);
            }
            return new StateValue<>(stateId, value);
        }

        @Override
        public boolean fastEquals(@NonNull StateValue<V> value, @NonNull ReadableSequentialData in)
                throws ParseException {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (fieldNum != stateId) {
                return false;
            }
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;
            if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                return false;
            }
            final int size = in.readVarInt(false);
            final boolean equals;
            if (size == 0) {
                equals = value.equals(valueCodec.getDefaultInstance());
            } else {
                final long limit = in.limit();
                in.limit(in.position() + size);
                equals = value.equals(valueCodec.parse(in));
                in.limit(limit);
            }
            return equals;
        }

        @Override
        public int measure(@NonNull final ReadableSequentialData in) throws ParseException {
            // This implementation can be optimized a bit to avoid V parsing
            final var start = in.position();
            parse(in);
            final var end = in.position();
            return (int) (end - start);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stateId, valueCodec);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof StateValueCodec<?> that)) {
                return false;
            }
            return Objects.equals(this.stateId, that.stateId) && Objects.equals(this.valueCodec, that.valueCodec);
        }
    }
}
