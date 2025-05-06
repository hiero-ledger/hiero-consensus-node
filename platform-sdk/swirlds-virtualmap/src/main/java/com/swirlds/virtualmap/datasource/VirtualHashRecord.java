// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A record that contains a path and a hash. It serves for both node types, internal and leaf.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message HashRecord {
 *
 *     // Virtual node path
 *     optional fixed64 path = 1;
 *
 *     // Hash. Always DigestType.SHA_384 for now
 *     bytes hash = 2;
 * }
 * </pre>
 *
 * @param path the path of the node
 * @param hash the hash of the node
 */
public record VirtualHashRecord(long path, Hash hash) {

    public static final FieldDefinition FIELD_HASHRECORD_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, true, false, 1);
    public static final FieldDefinition FIELD_HASHRECORD_HASH =
            new FieldDefinition("hash", FieldType.BYTES, false, true, false, 2);

    public VirtualHashRecord(long path) {
        this(path, null);
    }

    /**
     * Reads a virtual hash record from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the virtual hash record
     */
    public static VirtualHashRecord parseFrom(final ReadableSequentialData in) {
        if (in == null) {
            return null;
        }

        long path = 0;
        byte[] hashBytes = null;

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_HASHRECORD_PATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                path = in.readLong();
            } else if (tag == FIELD_HASHRECORD_HASH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int len = in.readVarInt(false);
                hashBytes = new byte[len];
                if (in.readBytes(hashBytes) != len) {
                    throw new IllegalArgumentException("Failed to read hash bytes");
                }
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        if (hashBytes == null) {
            throw new IllegalArgumentException("Null hash bytes");
        }
        return new VirtualHashRecord(path, new Hash(hashBytes));
    }

    public int getSizeInBytes() {
        int size = 0;
        size += ProtoWriterTools.sizeOfTag(FIELD_HASHRECORD_PATH);
        size += Long.BYTES;
        if (hash != null) {
            final Bytes hashBytes = hash.getBytes();
            size += ProtoWriterTools.sizeOfDelimited(FIELD_HASHRECORD_HASH, Math.toIntExact(hashBytes.length()));
        }
        return size;
    }

    /**
     * Writes this virtual hash bytes object to the given sequential data.
     *
     * @param out the sequential data to write to
     */
    public void writeTo(final WritableSequentialData out) {
        ProtoWriterTools.writeTag(out, FIELD_HASHRECORD_PATH);
        out.writeLong(path);
        if (hash != null) {
            final Bytes hashBytes = hash.getBytes();
            ProtoWriterTools.writeDelimited(
                    out, FIELD_HASHRECORD_HASH, Math.toIntExact(hashBytes.length()), hashBytes::writeTo);
        }
    }

    public static void extractAndWriteHashBytes(final ReadableSequentialData in, final SerializableDataOutputStream out)
            throws IOException {
        // Hash.serialize() format is: digest ID (4 bytes) + size (4 bytes) + hash (48 bytes)
        out.writeInt(DigestType.SHA_384.id());
        // hashBytes is in protobuf format
        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            if (fieldNum == FIELD_HASHRECORD_PATH.number()) {
                in.skip(Long.BYTES);
            } else if (fieldNum == FIELD_HASHRECORD_HASH.number()) {
                final int hashSize = in.readVarInt(false);
                // It would be helpful to have BufferedData.readBytes(OutputStream) method, similar to
                // readBytes(ByteBuffer) or readBytes(BufferedData). Since there is no such method,
                // use a workaround to allocate a byte array
                final byte[] arr = new byte[hashSize];
                in.readBytes(arr);
                out.writeInt(hashSize);
                out.write(arr);
                break;
            } else {
                throw new IllegalArgumentException("Unknown virtual hash record field: " + fieldNum);
            }
        }
    }
}
