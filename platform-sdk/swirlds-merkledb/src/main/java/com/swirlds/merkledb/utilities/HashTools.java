// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.utilities;

import java.nio.ByteBuffer;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Some helpers for dealing with hashes.
 */
public final class HashTools {
    private static final int CURRENT_SERIALIZATION_VERSION = 1;

    public static final DigestType DEFAULT_DIGEST = DigestType.SHA_384;
    public static final int HASH_SIZE_BYTES = DEFAULT_DIGEST.digestLength();

    /**
     * Gets the version for all hashes written. May represent both the storage format and the digest.
     *
     * @return the serialization version being used
     */
    public static int getSerializationVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    /**
     * Creates a new {@link ByteBuffer} whose contents are the digest of the given hash.
     *
     * @param hash
     * 		the hash with the digest to put in a byte buffer
     * @return the byte buffer with the digest of the hash
     */
    public static ByteBuffer hashToByteBuffer(final Hash hash) {
        final ByteBuffer buf = ByteBuffer.allocate(HASH_SIZE_BYTES);
        hash.getBytes().writeTo(buf);
        return buf.flip();
    }

    /**
     * Copies the digest of the given hash into the given {@link ByteBuffer}.
     *
     * @param hash
     * 		the hash with the digest to copy
     * @param buf
     * 		the byte buffer to receive the digest of the hash
     */
    public static void hashToByteBuffer(final Hash hash, final ByteBuffer buf) {
        hash.getBytes().writeTo(buf);
    }

    /**
     * Returns a SHA-384 hash whose digest is the contents of the given {@link Hash}.
     *
     * @param buffer
     * 		the byte buffer whose contents are the desired digest
     * @param serializationVersion
     * 		the version of serialization used to create the byte buffer
     * @return a SHA-384 hash whose digest is the contents of the given buffer
     */
    public static Hash byteBufferToHash(final ByteBuffer buffer, final int serializationVersion) {
        if (serializationVersion != CURRENT_SERIALIZATION_VERSION) {
            throw new IllegalArgumentException(
                    "Current version is " + CURRENT_SERIALIZATION_VERSION + ", got " + serializationVersion);
        }
        final byte[] bytes = new byte[DEFAULT_DIGEST.digestLength()];
        buffer.get(bytes);
        return new Hash(bytes, DEFAULT_DIGEST);
    }

    private HashTools() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
