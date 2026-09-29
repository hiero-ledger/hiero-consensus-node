// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Minimal protobuf wire-format writer and reader for the fixed CometBFT / ICS-23 message
 * shapes the Sei verifier needs. Hand-rolled (like {@code Rlp} is for the QBFT verifier) so
 * the consensus-critical encoding rules stay explicit and dependency-free.
 *
 * <p>The writer follows gogoproto proto3 emission rules, which CometBFT hashing and vote
 * sign-bytes depend on byte-for-byte:
 * <ul>
 *   <li>zero-valued scalars and empty {@code bytes}/{@code string} fields are omitted
 *       entirely ({@link #varintField}, {@link #bytesField}, {@link #sfixed64Field});</li>
 *   <li>embedded messages generated with {@code gogoproto.nullable = false} (vote
 *       timestamps, part-set headers) are written even when empty
 *       ({@link #messageField}).</li>
 * </ul>
 */
public final class SeiProto {

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LENGTH_DELIMITED = 2;
    static final int WIRE_FIXED32 = 5;

    private SeiProto() {}

    /** Encodes a value as an unsigned base-128 varint. */
    @NonNull
    public static byte[] varint(final long value) {
        final var out = new ByteArrayOutputStream(10);
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    /** Encodes a varint-typed field, omitted entirely when the value is zero (proto3). */
    @NonNull
    public static byte[] varintField(final int field, final long value) {
        if (value == 0) {
            return new byte[0];
        }
        return concat(tag(field, WIRE_VARINT), varint(value));
    }

    /** Encodes a bytes/string field, omitted entirely when empty (proto3). */
    @NonNull
    public static byte[] bytesField(final int field, @NonNull final byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0) {
            return new byte[0];
        }
        return concat(tag(field, WIRE_LENGTH_DELIMITED), varint(value.length), value);
    }

    /**
     * Encodes an embedded-message field that is written even when the message body is empty —
     * the gogoproto {@code nullable = false} convention CometBFT uses for vote timestamps and
     * part-set headers.
     */
    @NonNull
    public static byte[] messageField(final int field, @NonNull final byte[] message) {
        Objects.requireNonNull(message, "message");
        return concat(tag(field, WIRE_LENGTH_DELIMITED), varint(message.length), message);
    }

    /** Encodes an sfixed64 field (little-endian), omitted entirely when zero (proto3). */
    @NonNull
    public static byte[] sfixed64Field(final int field, final long value) {
        if (value == 0) {
            return new byte[0];
        }
        final byte[] le = new byte[8];
        for (int i = 0; i < 8; i++) {
            le[i] = (byte) (value >>> (8 * i));
        }
        return concat(tag(field, WIRE_FIXED64), le);
    }

    /** Prefixes a message with its varint length ({@code protoio.MarshalDelimited}). */
    @NonNull
    public static byte[] delimited(@NonNull final byte[] message) {
        Objects.requireNonNull(message, "message");
        return concat(varint(message.length), message);
    }

    @NonNull
    public static byte[] concat(@NonNull final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final byte[] out = new byte[length];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    @NonNull
    private static byte[] tag(final int field, final int wireType) {
        return varint(((long) field << 3) | wireType);
    }

    /**
     * Forward-only cursor over protobuf wire format, used to decode the ICS-23
     * {@code CommitmentProof} messages carried in Sei proofs.
     */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(@NonNull final byte[] data) {
            this.data = Objects.requireNonNull(data, "data");
        }

        public boolean hasMore() {
            return pos < data.length;
        }

        /** Reads a field tag; returns {@code (fieldNumber << 3) | wireType}. */
        public int readTag() {
            final long tag = readVarint();
            if (tag <= 0 || tag > Integer.MAX_VALUE) {
                throw ProofException.sei("invalid protobuf tag " + tag + " at offset " + pos);
            }
            return (int) tag;
        }

        public long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= data.length) {
                    throw ProofException.sei("truncated varint at offset " + pos);
                }
                final byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw ProofException.sei("varint longer than 10 bytes at offset " + pos);
        }

        /** Reads a length-delimited field body. */
        @NonNull
        public byte[] readBytes() {
            final long length = readVarint();
            if (length < 0 || length > data.length - pos) {
                throw ProofException.sei("truncated length-delimited field at offset " + pos);
            }
            final byte[] out = new byte[(int) length];
            System.arraycopy(data, pos, out, 0, (int) length);
            pos += (int) length;
            return out;
        }
    }
}
