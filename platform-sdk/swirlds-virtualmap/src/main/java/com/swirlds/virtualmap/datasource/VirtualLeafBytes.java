// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
 * message LeafRecord {
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
 *
 * @param path virtual record path
 * @param keyBytes virtual key bytes
 * @param keyHashCode virtual key hash code
 * @param valueBytes virtual value bytes
 */
public record VirtualLeafBytes(long path, @NonNull Bytes keyBytes, int keyHashCode, @Nullable Bytes valueBytes) {

    public static final FieldDefinition FIELD_LEAFRECORD_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, true, false, 1);
    public static final FieldDefinition FIELD_LEAFRECORD_KEY =
            new FieldDefinition("key", FieldType.BYTES, false, true, false, 2);
    public static final FieldDefinition FIELD_LEAFRECORD_VALUE =
            new FieldDefinition("value", FieldType.BYTES, false, true, false, 3);

    /**
     * Reads a virtual leaf bytes object from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the virtual leaf bytes object
     */
    public static VirtualLeafBytes parseFrom(final ReadableSequentialData in) {
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
                valueBytes = in.readBytes(len);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        Objects.requireNonNull(keyBytes, "Missing key bytes in the input");

        // Key hash code is not deserialized
        return new VirtualLeafBytes(path, keyBytes, 0, valueBytes);
    }

    public int getSizeInBytes() {
        int size = 0;
        // Path is FIXED64
        size += ProtoWriterTools.sizeOfTag(FIELD_LEAFRECORD_PATH);
        size += Long.BYTES;
        size += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_KEY, Math.toIntExact(keyBytes.length()));
        // Key hash code is not serialized
        if (valueBytes != null) {
            size += ProtoWriterTools.sizeOfDelimited(FIELD_LEAFRECORD_VALUE, Math.toIntExact(valueBytes.length()));
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
        // Key hash code is not serialized
        if (valueBytes != null) {
            ProtoWriterTools.writeDelimited(
                    out, FIELD_LEAFRECORD_VALUE, Math.toIntExact(valueBytes.length()), valueBytes::writeTo);
        }
        assert out.position() == pos + getSizeInBytes();
    }

    /**
     * Convert this bytes object to a virtual leaf record. The record will contain a key and
     * a value, which are parsed from this object's {@code keyBytes} and {@code valueBytes}
     * bytes using the provided key and value serializers.
     *
     * @param keySerializer the key serializer to parse keyBytes
     * @param valueSerializer the value serializer to parse valueBytes
     * @return the virtual lead record
     * @param <K> the virtual key type
     * @param <V> the virtual value type
     */
    public <K extends VirtualKey, V extends VirtualValue> VirtualLeafRecord<K, V> toRecord(
            final KeySerializer<K> keySerializer, final ValueSerializer<V> valueSerializer) {
        return new VirtualLeafRecord<>(
                path,
                keySerializer.deserialize(keyBytes.toReadableSequentialData()),
                valueBytes != null ? valueSerializer.deserialize(valueBytes.toReadableSequentialData()) : null);
    }

    @Override
    public boolean equals(final Object obj) {
        // Override the default implementation to exclude keyHashCode field
        if (!(obj instanceof VirtualLeafBytes that)) {
            return false;
        }
        return (path == that.path)
                && Objects.equals(keyBytes, that.keyBytes)
                && Objects.equals(valueBytes, that.valueBytes);
    }

    @Override
    public String toString() {
        // Override the default implementation to exclude keyHashCode field
        return new ToStringBuilder(this)
                .append("path", path)
                .append("keyBytes", keyBytes)
                .append("valueBytes", valueBytes)
                .toString();
    }
}
