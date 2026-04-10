// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.hedera.pbj.runtime.ProtoParserTools.readBytes;
import static com.hedera.pbj.runtime.ProtoParserTools.readInt32;
import static com.hedera.pbj.runtime.ProtoParserTools.readInt64;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeBytes;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeLong;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Used during the reconnect protocol to send data needed to reconstruct a single virtual node.
 *
 * <p>On the learner side, a request is created with a path and a hash in the old learner
 * tree (if exists), then sent to the teacher. On the teacher side, requests are deserialized
 * from the stream, and for every request a response is sent back to the learner.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message PullVirtualTreeRequest {
 *     int64 path = 1;
 *     bytes hash = 2;
 * }
 * </pre>
 */
public final class PullVirtualTreeRequest {

    static final FieldDefinition FIELD_PATH = new FieldDefinition("path", FieldType.INT64, false, 1);
    static final FieldDefinition FIELD_HASH = new FieldDefinition("hash", FieldType.BYTES, false, 2);

    // Virtual node path. If the path is Path.INVALID_PATH, it indicates that the learner will
    // not send any more node requests to the teacher
    private final long path;

    // Virtual node hash. If a node with the given path does not exist on the learner (path is
    // outside of range), NULL_HASH is used. If the path is Path.INVALID_PATH, the hash is null
    private final Hash hash;

    /**
     * This constructor is used by the learner to send requests to the teacher.
     *
     * @param path the virtual node path
     * @param hash the virtual node hash, or null for the terminating request
     */
    public PullVirtualTreeRequest(final long path, @Nullable final Hash hash) {
        // Null hash for the terminating requests, non-null otherwise
        assert path == Path.INVALID_PATH || (path >= 0 && hash != null);
        assert hash == null || hash.getDigestType() == DigestType.SHA_384;
        this.path = path;
        this.hash = hash;
    }

    /**
     * Returns the virtual node path.
     *
     * @return the path, or {@link Path#INVALID_PATH} for terminating requests
     */
    public long getPath() {
        return path;
    }

    /**
     * Returns the virtual node hash.
     *
     * @return the hash, or {@code null} for terminating requests
     */
    @Nullable
    public Hash getHash() {
        return hash;
    }

    /**
     * Computes the serialized size in bytes.
     *
     * @return the number of bytes this message will occupy when serialized
     */
    public int getSizeInBytes() {
        int size = 0;
        size += ProtoWriterTools.sizeOfTag(FIELD_PATH);
        size += ProtoWriterTools.sizeOfVarInt64(path);
        if (hash != null) {
            final int hashLen = DigestType.SHA_384.digestLength();
            size += ProtoWriterTools.sizeOfDelimited(FIELD_HASH, hashLen);
        }
        return size;
    }

    /**
     * Writes this request to the given sequential data.
     *
     * @param out the sequential data to write to
     */
    public void writeTo(@NonNull final WritableSequentialData out) {
        writeLong(out, FIELD_PATH, path, false);
        if (hash != null) {
            try {
                writeBytes(out, FIELD_HASH, hash.getBytes(), false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads a request from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the parsed request
     */
    @NonNull
    public static PullVirtualTreeRequest parseFrom(@NonNull final ReadableSequentialData in) {
        long path = 0;
        Bytes hashBytes = null;

        while (in.hasRemaining()) {
            final int field = readInt32(in);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_PATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK)
                        != ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for path field: " + field);
                }
                path = readInt64(in);
            } else if (tag == FIELD_HASH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for hash field: " + field);
                }
                hashBytes = readBytes(in);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        final Hash hash = (hashBytes != null) ? new Hash(hashBytes, DigestType.SHA_384) : null;
        return new PullVirtualTreeRequest(path, hash);
    }
}
