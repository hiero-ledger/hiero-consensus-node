// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.hedera.pbj.runtime.ProtoParserTools.readBool;
import static com.hedera.pbj.runtime.ProtoParserTools.readBytes;
import static com.hedera.pbj.runtime.ProtoParserTools.readInt64;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfDelimited;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfTag;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt64;
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
 * Used during the reconnect protocol to send data needed to reconstruct a single virtual node.
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
 *     int64 path = 1;
 *     bool isClean = 2;
 *     optional int64 firstLeafPath = 3;
 *     optional int64 lastLeafPath = 4;
 *     bytes keyBytes = 5;
 *     bytes valueBytes = 6;
 * }
 * </pre>
 */
public final class PullVirtualTreeResponse {

    static final FieldDefinition FIELD_PATH = new FieldDefinition("path", FieldType.INT64, false, 1);
    static final FieldDefinition FIELD_IS_CLEAN =
            new FieldDefinition("isClean", FieldType.BOOL, false, false, false, 2);
    static final FieldDefinition FIELD_FIRST_LEAF_PATH =
            new FieldDefinition("firstLeafPath", FieldType.INT64, false, false, false, 3);
    static final FieldDefinition FIELD_LAST_LEAF_PATH =
            new FieldDefinition("lastLeafPath", FieldType.INT64, false, false, false, 4);
    static final FieldDefinition FIELD_KEY_BYTES = new FieldDefinition("keyBytes", FieldType.BYTES, false, 5);
    static final FieldDefinition FIELD_VALUE_BYTES = new FieldDefinition("valueBytes", FieldType.BYTES, false, 6);

    // Virtual node path
    private final long path;

    private final boolean isClean;

    private final long firstLeafPath;
    private final long lastLeafPath;

    // If the response is not clean (learner hash != teacher hash), then leafData contains
    // the leaf data on the teacher side
    private final VirtualLeafBytes<?> leafData;

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
    }

    /**
     * Returns the virtual node path.
     *
     * @return the path
     */
    public long getPath() {
        return path;
    }

    /**
     * Returns whether the node hash on the teacher matches the hash sent by the learner.
     *
     * @return {@code true} if hashes match (node is clean)
     */
    public boolean isClean() {
        return isClean;
    }

    /**
     * Returns the first leaf path in the teacher's tree. Only meaningful for root responses.
     *
     * @return the first leaf path, or {@link Path#INVALID_PATH} if not set
     */
    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    /**
     * Returns the last leaf path in the teacher's tree. Only meaningful for root responses.
     *
     * @return the last leaf path, or {@link Path#INVALID_PATH} if not set
     */
    public long getLastLeafPath() {
        return lastLeafPath;
    }

    /**
     * Returns the leaf data for dirty leaf nodes.
     *
     * @return the leaf data, or {@code null} if the node is clean or not a leaf
     */
    @Nullable
    public VirtualLeafBytes<?> getLeafData() {
        return leafData;
    }

    /**
     * Computes the serialized size in bytes.
     *
     * @return the number of bytes this message will occupy when serialized
     */
    public int getSizeInBytes() {
        int size = 0;
        // Path (INT64) - always written
        size += sizeOfTag(FIELD_PATH);
        size += sizeOfVarInt64(path);
        // isClean (BOOL) - always written
        size += sizeOfTag(FIELD_IS_CLEAN);
        size += 1;
        // firstLeafPath / lastLeafPath - only for root
        if (path == Path.ROOT_PATH) {
            size += sizeOfTag(FIELD_FIRST_LEAF_PATH);
            size += sizeOfVarInt64(firstLeafPath);
            size += sizeOfTag(FIELD_LAST_LEAF_PATH);
            size += sizeOfVarInt64(lastLeafPath);
        }
        // Leaf data - only for dirty leaves
        if (leafData != null) {
            final Bytes keyBytes = leafData.keyBytes();
            size += sizeOfDelimited(FIELD_KEY_BYTES, Math.toIntExact(keyBytes.length()));
            final Bytes valueBytes = leafData.valueBytes();
            if (valueBytes != null) {
                size += sizeOfDelimited(FIELD_VALUE_BYTES, Math.toIntExact(valueBytes.length()));
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
        writeLong(out, FIELD_PATH, path, false);
        writeBoolean(out, FIELD_IS_CLEAN, isClean, false);
        // First/last leaf paths - only for root
        if (path == Path.ROOT_PATH) {
            writeLong(out, FIELD_FIRST_LEAF_PATH, firstLeafPath, false);
            writeLong(out, FIELD_LAST_LEAF_PATH, lastLeafPath, false);
        }
        // Leaf data - only for dirty leaves
        if (leafData != null) {
            try {
                final Bytes keyBytes = leafData.keyBytes();
                writeBytes(out, FIELD_KEY_BYTES, keyBytes, false);
                final Bytes valueBytes = leafData.valueBytes();
                if (valueBytes != null) {
                    writeBytes(out, FIELD_VALUE_BYTES, valueBytes, false);
                }
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
        long firstLeafPath = -1;
        long lastLeafPath = -1;
        Bytes keyBytes = null;
        Bytes valueBytes = null;

        while (in.hasRemaining()) {
            final int field = in.readVarInt(false);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            final int wireType = field & ProtoConstants.TAG_WIRE_TYPE_MASK;

            if (tag == FIELD_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for path field: " + field);
                }
                path = readInt64(in);
            } else if (tag == FIELD_IS_CLEAN.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for isClean field: " + field);
                }
                try {
                    isClean = readBool(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (tag == FIELD_FIRST_LEAF_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for firstLeafPath field: " + field);
                }
                firstLeafPath = readInt64(in);
            } else if (tag == FIELD_LAST_LEAF_PATH.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for lastLeafPath field: " + field);
                }
                lastLeafPath = readInt64(in);
            } else if (tag == FIELD_KEY_BYTES.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for keyBytes field: " + field);
                }
                keyBytes = readBytes(in);
            } else if (tag == FIELD_VALUE_BYTES.number()) {
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
