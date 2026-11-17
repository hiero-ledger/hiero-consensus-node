// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.hedera.pbj.runtime.ProtoParserTools.readBytes;
import static com.hedera.pbj.runtime.ProtoParserTools.readFixed64;
import static com.hedera.pbj.runtime.ProtoParserTools.readInt32;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfDelimited;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfTag;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeBytes;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeLong;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * Used during the synchronization protocol to send data needed to reconstruct a single virtual node.
 *
 * <p>On the learner side, a request is created with a path and the hash of the node at that path in the
 * old learner tree (if exists), then sent to the teacher. On the teacher side, requests are deserialized
 * from the stream, and for every request a response is sent back to the learner.
 *
 * <p>The request carries a prefix of the learner's hash. The teacher uses the hash only to decide whether
 * the node differs, and compares the prefix with the beginning of its own hash, whatever the prefix length
 * is. The learner chooses the length: a configured prefix length normally, and full hashes when an earlier
 * reconnect from the same state wasn't accepted. If a prefix ever matched for different hashes, the learner
 * would keep a stale subtree, and the reconnected state would fail validation against the teacher's signed
 * root hash, so a wrong state is never accepted.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message PullVirtualTreeRequest {
 *     fixed64 path = 1;
 *     bytes hashPrefix = 2;
 * }
 * </pre>
 *
 * @param path virtual node path
 * @param hashPrefix a prefix of the virtual node hash on the learner, or the full hash. If a node with the given
 *     path does not exist on the learner (path is outside of range), a prefix of {@link Cryptography#NULL_HASH}
 *     is used
 */
public record PullVirtualTreeRequest(long path, @NonNull Bytes hashPrefix) {

    static final FieldDefinition FIELD_PULLREQUEST_PATH = new FieldDefinition("path", FieldType.FIXED64, false, 1);
    static final FieldDefinition FIELD_PULLREQUEST_HASH_PREFIX =
            new FieldDefinition("hashPrefix", FieldType.BYTES, false, 2);

    /**
     * Creates a request with a hash prefix.
     *
     * @param path the virtual node path
     * @param hashPrefix the hash prefix, or the full hash
     */
    public PullVirtualTreeRequest {
        Objects.requireNonNull(hashPrefix, "hashPrefix");
    }

    /**
     * This constructor is used by the learner to send requests to the teacher.
     *
     * @param path the virtual node path
     * @param hash the virtual node hash on the learner
     * @param hashPrefixLength the number of leading hash bytes to send. If it isn't less than the hash length,
     *     the full hash is sent
     */
    public PullVirtualTreeRequest(final long path, @NonNull final Hash hash, final int hashPrefixLength) {
        this(
                path,
                hash.getBytes()
                        .slice(0, Math.min(hashPrefixLength, hash.getBytes().length())));
    }

    /**
     * Checks whether the hash prefix in this request matches the given hash.
     *
     * @param hash the hash to compare with, usually the teacher's hash of the node at this request's path
     * @return true if the hash prefix in this request is equal to the given hash's bytes of the same length. A
     *     prefix longer than the hash never matches
     */
    public boolean matches(@NonNull final Hash hash) {
        return hash.getBytes().matchesPrefix(hashPrefix);
    }

    /**
     * Computes the serialized size in bytes.
     *
     * @return the number of bytes this message will occupy when serialized
     */
    public int getSizeInBytes() {
        int size = 0;
        if (path != 0) {
            size += sizeOfTag(FIELD_PULLREQUEST_PATH) + Long.BYTES;
        }
        size += sizeOfDelimited(FIELD_PULLREQUEST_HASH_PREFIX, (int) hashPrefix.length());
        return size;
    }

    /**
     * Writes this request to the given sequential data.
     *
     * @param out the sequential data to write to
     */
    public void writeTo(@NonNull final WritableSequentialData out) {
        if (path != 0) {
            writeLong(out, FIELD_PULLREQUEST_PATH, path);
        }
        try {
            writeBytes(out, FIELD_PULLREQUEST_HASH_PREFIX, hashPrefix, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a request from the given sequential data.
     *
     * @param in sequential data to read from
     * @return the parsed request
     * @throws IllegalArgumentException if the data isn't a valid request
     */
    @NonNull
    public static PullVirtualTreeRequest parseFrom(@NonNull final ReadableSequentialData in) {
        long path = 0;
        Bytes hashPrefix = null;

        while (in.hasRemaining()) {
            final int field = readInt32(in);
            final int tag = field >> ProtoParserTools.TAG_FIELD_OFFSET;
            if (tag == FIELD_PULLREQUEST_PATH.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for path field: " + field);
                }
                path = readFixed64(in);
            } else if (tag == FIELD_PULLREQUEST_HASH_PREFIX.number()) {
                if ((field & ProtoConstants.TAG_WIRE_TYPE_MASK) != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    throw new IllegalArgumentException("Wrong wire type for hash prefix field: " + field);
                }
                hashPrefix = readBytes(in);
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        if (hashPrefix == null) {
            throw new IllegalArgumentException("Missing hash prefix, path=" + path);
        }
        return new PullVirtualTreeRequest(path, hashPrefix);
    }
}
