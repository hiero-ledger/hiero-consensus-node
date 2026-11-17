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
 * <p>The request carries only the first {@link #HASH_PREFIX_LENGTH} bytes of the learner's hash. The
 * teacher uses the hash only to decide whether the node differs, and a prefix of this length is enough
 * for that. If a prefix ever matched for different hashes, the learner would keep a stale subtree, and
 * the reconnected state would fail validation against the teacher's signed root hash, so a wrong state is never accepted.
 *
 * <p>A request may also carry the full hash. The learner sends full hashes when an earlier reconnect from the same
 * state wasn't accepted, so a crafted prefix collision can make at most one reconnect attempt fail.
 *
 * <p>Protobuf schema:
 *
 * <pre>
 * message PullVirtualTreeRequest {
 *     fixed64 path = 1;
 *     bytes hash_prefix = 2;
 * }
 * </pre>
 *
 * @param path virtual node path
 * @param hashPrefix the first {@link #HASH_PREFIX_LENGTH} bytes of the virtual node hash on the learner, or the
 *     full hash. If a node with the given path does not exist on the learner (path is outside of range), the
 *     prefix of {@link Cryptography#NULL_HASH} is used
 */
public record PullVirtualTreeRequest(long path, @NonNull Bytes hashPrefix) {

    /**
     * The number of leading hash bytes sent in a request.
     */
    public static final int HASH_PREFIX_LENGTH = 16;

    /**
     * The length of a full hash.
     */
    public static final int FULL_HASH_LENGTH = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();

    static final FieldDefinition FIELD_PULLREQUEST_PATH = new FieldDefinition("path", FieldType.FIXED64, false, 1);
    static final FieldDefinition FIELD_PULLREQUEST_HASH_PREFIX =
            new FieldDefinition("hash_prefix", FieldType.BYTES, false, 2);

    /**
     * Creates a request with a hash prefix.
     *
     * @param path the virtual node path
     * @param hashPrefix the hash prefix, or the full hash
     */
    public PullVirtualTreeRequest {
        Objects.requireNonNull(hashPrefix, "hashPrefix");
        assert isValidLength(hashPrefix.length());
    }

    /**
     * Creates a request with the first {@link #HASH_PREFIX_LENGTH} bytes of the given hash.
     *
     * @param path the virtual node path
     * @param hash the virtual node hash on the learner
     */
    public PullVirtualTreeRequest(final long path, @NonNull final Hash hash) {
        this(path, hash, false);
    }

    /**
     * This constructor is used by the learner to send requests to the teacher.
     *
     * @param path the virtual node path
     * @param hash the virtual node hash on the learner
     * @param fullHash whether to send the full hash rather than its first {@link #HASH_PREFIX_LENGTH} bytes
     */
    public PullVirtualTreeRequest(final long path, @NonNull final Hash hash, final boolean fullHash) {
        this(path, fullHash ? hash.getBytes() : hash.getBytes().slice(0, HASH_PREFIX_LENGTH));
    }

    /**
     * Checks whether the hash prefix in this request matches the given hash.
     *
     * @param hash the hash to compare with, usually the teacher's hash of the node at this request's path
     * @return true if the hash prefix in this request is equal to the given hash's bytes of the same length
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
                if (!isValidLength(hashPrefix.length())) {
                    throw new IllegalArgumentException("Wrong hash prefix length: " + hashPrefix.length());
                }
            } else {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }

        if (hashPrefix == null) {
            throw new IllegalArgumentException("Missing hash prefix, path=" + path);
        }
        return new PullVirtualTreeRequest(path, hashPrefix);
    }

    private static boolean isValidLength(final long length) {
        return (length == HASH_PREFIX_LENGTH) || (length == FULL_HASH_LENGTH);
    }
}
