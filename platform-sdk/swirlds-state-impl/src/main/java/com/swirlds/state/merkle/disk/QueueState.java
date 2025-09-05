// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public record QueueState(long head, long tail) {

    private static final FieldDefinition FIELD_QUEUESTATE_HEAD =
            new FieldDefinition("head", FieldType.UINT64, false, true, false, 1);

    private static final FieldDefinition FIELD_QUEUESTATE_TAIL =
            new FieldDefinition("tail", FieldType.UINT64, false, true, false, 2);

    public QueueState {
        if (head < 0) {
            throw new IllegalArgumentException("Head < 0");
        }
        if (tail < 0) {
            throw new IllegalArgumentException("Tail < 0");
        }
        if (head > tail) {
            throw new IllegalArgumentException("Head > tail");
        }
    }

    public QueueState copy() {
        return new QueueState(head, tail);
    }

    public QueueState elementAdded() {
        return new QueueState(head, tail + 1);
    }

    public QueueState elementRemoved() {
        return new QueueState(head + 1, tail);
    }

    public static class QueueStateCodec implements Codec<QueueState> {

        public static final Codec<QueueState> INSTANCE = new QueueStateCodec();

        @Override
        public QueueState getDefaultInstance() {
            throw new UnsupportedOperationException("getDefaultInstance() should not be used");
        }

        @Override
        public int measureRecord(final QueueState value) {
            int size = 0;
            if (value.head() != 0) {
                size += ProtoWriterTools.sizeOfTag(FIELD_QUEUESTATE_HEAD);
                size += ProtoWriterTools.sizeOfVarInt64(value.head());
            }
            if (value.tail() != 0) {
                size += ProtoWriterTools.sizeOfTag(FIELD_QUEUESTATE_TAIL);
                size += ProtoWriterTools.sizeOfVarInt64(value.tail());
            }
            return size;
        }

        @Override
        public void write(@NonNull final QueueState value, @NonNull final WritableSequentialData out)
                throws IOException {
            final long pos = out.position();
            if (value.head() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_QUEUESTATE_HEAD);
                out.writeVarLong(value.head(), false);
            }
            if (value.tail() != 0) {
                ProtoWriterTools.writeTag(out, FIELD_QUEUESTATE_TAIL);
                out.writeVarLong(value.tail(), false);
            }
            assert out.position() == pos + measureRecord(value);
        }

        @NonNull
        @Override
        public QueueState parse(
                @NonNull final ReadableSequentialData in,
                final boolean strictMode,
                final boolean parseUnknownFields,
                final int maxDepth)
                throws ParseException {
            long head = 0;
            long tail = 0;

            while (in.hasRemaining()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> ProtoParserTools.TAG_FIELD_OFFSET;
                final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;
                if (fieldNum == FIELD_QUEUESTATE_HEAD.number()) {
                    if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                        throw new ParseException("Head wire type mismatch: expected "
                                + ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal() + ", actual=" + wireType);
                    }
                    head = in.readVarLong(false);
                } else if (fieldNum == FIELD_QUEUESTATE_TAIL.number()) {
                    if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                        throw new ParseException("Tail wire type mismatch: expected "
                                + ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal() + ", actual=" + wireType);
                    }
                    tail = in.readVarLong(false);
                } else {
                    throw new ParseException("Unknown field: " + tag);
                }
            }

            return new QueueState(head, tail);
        }

        @Override
        public boolean fastEquals(@NonNull final QueueState value, @NonNull final ReadableSequentialData in)
                throws ParseException {
            return value.equals(parse(in));
        }

        @Override
        public int measure(@NonNull final ReadableSequentialData in) throws ParseException {
            final long pos = in.position();
            parse(in);
            return (int) (in.position() - pos);
        }
    }
}
