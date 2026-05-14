// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.hedera.pbj.runtime.ProtoParserTools.readBool;
import static com.hedera.pbj.runtime.ProtoParserTools.readBytes;
import static com.hedera.pbj.runtime.ProtoParserTools.readFixed64;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfDelimited;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfTag;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeBoolean;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeBytes;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeLong;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Used during the synchronization protocol to send data needed to reconstruct a single virtual node.
 *
 * <p>The teacher sends one response for every {@link PullVirtualTreeRequest} received from the
 * learner. Every response includes a path followed by a boolean flag that indicates if the node
 * is clean (node hash on the teacher is the same as sent by the learner), or not. If the path
 * is the root path, the response also includes first and last leaf paths. If the path corresponds
 * to a dirty leaf node, a {@link VirtualLeafBytes} for the node is included.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message PullVirtualTreeResponse {
 *     fixed64 path = 1;
 *     bool isClean = 2;
 *     optional fixed64 firstLeafPath = 3;
 *     optional fixed64 lastLeafPath = 4;
 *     optional bytes keyBytes = 5;
 *     optional bytes valueBytes = 6;
 * }
 * </pre>
 *
 */
public record PullVirtualTreeResponse(
        long path, boolean isClean, long firstLeafPath, long lastLeafPath, VirtualLeafBytes<?> leafData) {

    static final FieldDefinition FIELD_PULLRESPONSE_PATH = new FieldDefinition("path", FieldType.FIXED64, false, 1);
    static final FieldDefinition FIELD_PULLRESPONSE_IS_CLEAN =
            new FieldDefinition("isClean", FieldType.BOOL, false, false, false, 2);
    static final FieldDefinition FIELD_PULLRESPONSE_FIRST_LEAF_PATH =
            new FieldDefinition("firstLeafPath", FieldType.FIXED64, false, false, false, 3);
    static final FieldDefinition FIELD_PULLRESPONSE_LAST_LEAF_PATH =
            new FieldDefinition("lastLeafPath", FieldType.FIXED64, false, false, false, 4);
    static final FieldDefinition FIELD_PULLRESPONSE_KEY_BYTES =
            new FieldDefinition("keyBytes", FieldType.BYTES, false, 5);
    static final FieldDefinition FIELD_PULLRESPONSE_VALUE_BYTES =
            new FieldDefinition("valueBytes", FieldType.BYTES, false, 6);

    /**
     * Constructs a response with all fields.
     *
     * @param path the virtual node path
     * @param isClean whether the node hash matches on teacher and learner
     * @param firstLeafPath the first leaf path (only meaningful for root responses)
     * @param lastLeafPath the last leaf path (only meaningful for root responses)
     * @param leafData the leaf data for dirty leaf nodes, or null
     */
    public PullVirtualTreeResponse(
            final long path,
            final boolean isClean,
            final long firstLeafPath,
            final long lastLeafPath,
            @Nullable final VirtualLeafBytes<?> leafData) {
        this.path = path;
        this.isClean = isClean;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.leafData = leafData;
        assert (leafData == null) || (leafData.path() == path) : "leafData.path() != path";
    }

    /**
     * Computes the serialized size in bytes.
     *
     * @return the number of bytes this message will occupy when serialized
     */
    public int getSizeInBytes() {
        int size = 0;
        if (path != 0) {
            size += sizeOfTag(FIELD_PULLRESPONSE_PATH) + Long.BYTES;
        }
        if (isClean) { // false is default, only count when true
            size += sizeOfTag(FIELD_PULLRESPONSE_IS_CLEAN) + 1;
        }
        if (path == Path.ROOT_PATH) {
            if (firstLeafPath != 0) {
                size += sizeOfTag(FIELD_PULLRESPONSE_FIRST_LEAF_PATH) + Long.BYTES;
            }
            if (lastLeafPath != 0) {
                size += sizeOfTag(FIELD_PULLRESPONSE_LAST_LEAF_PATH) + Long.BYTES;
            }
        }
        // Leaf data - only for dirty leaves
        if (leafData != null) {
            final Bytes keyBytes = leafData.keyBytes();
            size += sizeOfDelimited(FIELD_PULLRESPONSE_KEY_BYTES, Math.toIntExact(keyBytes.length()));
            final Bytes valueBytes = leafData.valueBytes();
            if (valueBytes != null) {
                size += sizeOfDelimited(FIELD_PULLRESPONSE_VALUE_BYTES, Math.toIntExact(valueBytes.length()));
            }
        }
        return size;
    }

    /**
     * Writes this response to the given sequential data.
     *
     * @param out the sequential data to write to
     */
    public void writeTo(@NonNull final WritableSequentialData out) {
        if (path != 0) {
            writeLong(out, FIELD_PULLRESPONSE_PATH, path);
        }
        writeBoolean(out, FIELD_PULLRESPONSE_IS_CLEAN, isClean);
        // First/last leaf paths - only for root
        if (path == Path.ROOT_PATH) {
            writeLong(out, FIELD_PULLRESPONSE_FIRST_LEAF_PATH, firstLeafPath);
            writeLong(out, FIELD_PULLRESPONSE_LAST_LEAF_PATH, lastLeafPath);
        }
        // Leaf data - only for dirty leaves
        if (leafData != null) {
            try {
                final Bytes keyBytes = leafData.keyBytes();
                writeBytes(out, FIELD_PULLRESPONSE_KEY_BYTES, keyBytes, false);
                final Bytes valueBytes = leafData.valueBytes();
                writeBytes(out, FIELD_PULLRESPONSE_VALUE_BYTES, valueBytes, false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads a response from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the parsed response
     */
    @NonNull
    public static PullVirtualTreeResponse parseFrom(@NonNull final ReadableSequentialData in) {
        long path = 0;
        boolean isClean = false;
        long firstLeafPath = 0;
        long lastLeafPath = 0;
        Bytes keyBytes = null;
        Bytes valueBytes = null;

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            final int wireType = field & ProtoConstants.TAG_WIRE_TYPE_MASK;

            if (tag == FIELD_PULLRESPONSE_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for path field: " + field);
                }
                path = readFixed64(in);
            } else if (tag == FIELD_PULLRESPONSE_IS_CLEAN.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for isClean field: " + field);
                }
                try {
                    isClean = readBool(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (tag == FIELD_PULLRESPONSE_FIRST_LEAF_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for firstLeafPath field: " + field);
                }
                firstLeafPath = readFixed64(in);
            } else if (tag == FIELD_PULLRESPONSE_LAST_LEAF_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for lastLeafPath field: " + field);
                }
                lastLeafPath = readFixed64(in);
            } else if (tag == FIELD_PULLRESPONSE_KEY_BYTES.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for keyBytes field: " + field);
                }
                keyBytes = readBytes(in);
            } else if (tag == FIELD_PULLRESPONSE_VALUE_BYTES.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for valueBytes field: " + field);
                }
                valueBytes = readBytes(in);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        final VirtualLeafBytes<?> leafData;
        if (keyBytes != null) {
            leafData = new VirtualLeafBytes<>(path, keyBytes, valueBytes);
        } else {
            leafData = null;
        }

        return new PullVirtualTreeResponse(path, isClean, firstLeafPath, lastLeafPath, leafData);
    }
}
