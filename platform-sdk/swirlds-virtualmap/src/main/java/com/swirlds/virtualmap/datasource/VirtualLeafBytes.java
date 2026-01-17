// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Objects;

/**
 * Virtual leaf record bytes.
 *
 * <p>Key hash code is used only when a virtual leaf is stored to data source, to properly map
 * the key to HDHM bucket. When a leaf is loaded back from data source to virtual map,
 * hash code is always set to 0. It can be restored from the key, once the key is deserialized
 * from key bytes, but there should be actually no need to restore the hash code.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message StateItem {
 *
 *     // Virtual node path
 *     optional fixed64 path = 1;
 *
 *     // Virtual key
 *     bytes key = 2;
 *
 *     // Virtual value
 *     bytes value = 3;
 * }
 * </pre>
 */
public class VirtualLeafBytes<V> {

    public static final FieldDefinition FIELD_MERKLELEAF_STATEITEM =
            new FieldDefinition("state_item", FieldType.MESSAGE, false, false, true, 3);
    public static final FieldDefinition FIELD_LEAFRECORD_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, true, false, 1);
    public static final FieldDefinition FIELD_LEAFRECORD_KEY =
            new FieldDefinition("key", FieldType.BYTES, false, true, false, 2);
    public static final FieldDefinition FIELD_LEAFRECORD_VALUE =
            new FieldDefinition("value", FieldType.BYTES, false, true, false, 3);

    private final long path;

    private final Bytes keyBytes;

    private V value;
    private Codec<V> valueCodec;
    private Bytes valueBytes;

    public VirtualLeafBytes(
            final long path, @NonNull final Bytes keyBytes, @Nullable final V value, @Nullable Codec<V> valueCodec) {
        this(path, keyBytes, value, valueCodec, null);
    }

    public VirtualLeafBytes(final long path, @NonNull final Bytes keyBytes, @Nullable Bytes valueBytes) {
        this(path, keyBytes, null, null, valueBytes);
    }

    VirtualLeafBytes(
            final long path,
            @NonNull final Bytes keyBytes,
            @Nullable final V value,
            @Nullable final Codec<V> valueCodec,
            @Nullable final Bytes valueBytes) {
        this.path = path;
        this.keyBytes = Objects.requireNonNull(keyBytes);
        this.value = value;
        this.valueCodec = valueCodec;
        this.valueBytes = valueBytes;
        if ((value != null) && (valueCodec == null)) {
            throw new IllegalArgumentException("Null codec for non-null value");
        }
    }

    public long path() {
        return path;
    }

    public Bytes keyBytes() {
        return keyBytes;
    }

    public V value(final Codec<V> valueCodec) {
        if (value == null) {
            // No synchronization here. In the worst case, value will be initialized multiple
            // times, but always to the same object
            if (valueBytes != null) {
                assert this.valueCodec == null || this.valueCodec.equals(valueCodec);
                this.valueCodec = valueCodec;
                try {
                    value = valueCodec.parse(valueBytes);
                } catch (final ParseException e) {
                    throw new RuntimeException("Failed to deserialize a value from bytes", e);
                }
            } else {
                // valueBytes is null, so the value should be null, too. Does it make sense to
                // do anything to the codec here? Perhaps not
            }
        } else {
            // The value is provided or already parsed from bytes. Check the codec
            assert valueCodec != null;
            if (!this.valueCodec.equals(valueCodec)) {
                throw new IllegalStateException("Value codec mismatch");
            }
        }
        return value;
    }

    public Bytes valueBytes() {
        if (valueBytes == null) {
            // No synchronization here. In the worst case, valueBytes will be initialized multiple
            // times, but always to the same value
            if (value != null) {
                assert (valueCodec != null);
                final byte[] vb = new byte[valueCodec.measureRecord(value)];
                try {
                    valueCodec.write(value, BufferedData.wrap(vb));
                    valueBytes = Bytes.wrap(vb);
                } catch (final IOException e) {
                    throw new RuntimeException("Failed to serialize a value to bytes", e);
                }
            }
        }
        return valueBytes;
    }

    public VirtualLeafBytes<V> withPath(final long newPath) {
        return new VirtualLeafBytes<>(newPath, keyBytes, value, valueCodec, valueBytes);
    }

    public VirtualLeafBytes<V> withValue(final V newValue, final Codec<V> newValueCodec) {
        return new VirtualLeafBytes<>(path, keyBytes, newValue, newValueCodec);
    }

    public VirtualLeafBytes<V> withValueBytes(final Bytes newValueBytes) {
        return new VirtualLeafBytes<>(path, keyBytes, newValueBytes);
    }

    /**
     * Reads a virtual leaf bytes object from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the virtual leaf bytes object
     */
    public static VirtualLeafBytes<?> parseFrom(final ReadableSequentialData in) {
        if (in == null) {
            return null;
        }

        long path = 0;
        Bytes keyBytes = null;
        Bytes valueBytes = null;

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_LEAFRECORD_PATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                path = in.readLong();
            } else if (tag == FIELD_LEAFRECORD_KEY.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int len = in.readVarInt(false);
                keyBytes = in.readBytes(len);
            } else if (tag == FIELD_LEAFRECORD_VALUE.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong field type: " + field);
                }
                final int len = in.readVarInt(false);
                valueBytes = len == 0 ? Bytes.EMPTY : in.readBytes(len);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        Objects.requireNonNull(keyBytes, "Missing key bytes in the input");

        // Key hash code is not deserialized
        return new VirtualLeafBytes<>(path, keyBytes, valueBytes);
    }

    public int getSizeInBytes() {
        int size = 0;
        // Path is FIXED64
        size += ProtoWriterTools.sizeOfTag(FIELD_LEAFRECORD_PATH);
        size += Long.BYTES;
        size += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_KEY, Math.toIntExact(keyBytes.length()));
        final int valueBytesLen;
        // Don't call valueBytes() as it may trigger value serialization to Bytes
        if (valueBytes != null) {
            valueBytesLen = Math.toIntExact(valueBytes.length());
        } else if (value != null) {
            valueBytesLen = valueCodec.measureRecord(value);
        } else {
            // Null value
            valueBytesLen = -1;
        }
        if (valueBytesLen >= 0) {
            size += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_VALUE, valueBytesLen);
        }
        return size;
    }

    /**
     * Writes this virtual leaf bytes object to the given sequential data.
     *
     * @param out the sequential data to write to
     */
    public void writeTo(final WritableSequentialData out) {
        final long pos = out.position();
        ProtoWriterTools.writeTag(out, FIELD_LEAFRECORD_PATH);
        out.writeLong(path);
        ProtoWriterTools.writeDelimited(
                out, FIELD_LEAFRECORD_KEY, Math.toIntExact(keyBytes.length()), keyBytes::writeTo);
        final Bytes localValueBytes = valueBytes();
        if (localValueBytes != null) {
            ProtoWriterTools.writeDelimited(
                    out, FIELD_LEAFRECORD_VALUE, Math.toIntExact(localValueBytes.length()), localValueBytes::writeTo);
        }
        assert out.position() == pos + getSizeInBytes()
                : "pos=" + pos + ", out.position()=" + out.position() + ", size=" + getSizeInBytes();
    }

    // Output size must be at least getSizeInBytesForHashing()
    public void writeToForHashing(final WritableSequentialData out) {
        // The 0x00 prefix byte is added to all leaf hashes in the Hiero Merkle tree,
        // so that there is a clear guaranteed domain separation of hash space between leaves and internal nodes.
        out.writeByte((byte) 0x00);

        final Bytes kb = keyBytes();
        final int keyLen = Math.toIntExact(kb.length());
        int innerLen = ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_KEY, keyLen);

        final Bytes vb = valueBytes();
        if (vb != null) {
            final int valueLen = Math.toIntExact(vb.length());
            innerLen += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_VALUE, valueLen);
        }

        ProtoWriterTools.writeTag(out, FIELD_MERKLELEAF_STATEITEM);
        out.writeVarInt(innerLen, false);
        ProtoWriterTools.writeTag(out, FIELD_LEAFRECORD_KEY);
        out.writeVarInt(keyLen, false);
        kb.writeTo(out);
        if (vb != null) {
            ProtoWriterTools.writeTag(out, FIELD_LEAFRECORD_VALUE);
            out.writeVarInt(Math.toIntExact(vb.length()), false);
            vb.writeTo(out);
        }
    }

    @Override
    public int hashCode() {
        // VirtualLeafBytes is not expected to be used in collections, its hashCode()
        // doesn't have to be fast, so it's based on value bytes
        return Objects.hash(path, keyBytes, valueBytes());
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof VirtualLeafBytes<?> other)) {
            return false;
        }
        // VirtualLeafBytes is not expected to be used in collections, its equals()
        // doesn't have to be fast, so it's based on calculated value bytes
        return (path == other.path)
                && Objects.equals(keyBytes, other.keyBytes)
                && Objects.equals(valueBytes(), other.valueBytes());
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("path", path)
                .append("keyBytes", keyBytes)
                .append("valueBytes", valueBytes())
                .toString();
    }
}
