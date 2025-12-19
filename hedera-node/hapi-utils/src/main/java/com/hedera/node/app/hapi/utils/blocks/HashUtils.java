// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockstream.MerkleLeaf;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared hashing helpers for Merkle path construction and verification.
 *
 * <p>This utility class provides methods for computing cryptographic hashes used in Merkle trees,
 * with domain separation prefixes to prevent collision attacks between different node types:
 * <ul>
 *   <li>Leaf nodes: prefixed with 0x00</li>
 *   <li>Single-child (internal) nodes: prefixed with 0x01</li>
 *   <li>Two-child (internal) nodes: prefixed with 0x02</li>
 * </ul>
 *
 * <p>All hashing uses SHA-384 for security and consistency with the broader Hedera ecosystem.
 */
public final class HashUtils {

    private static final String HASH_ALGORITHM = "SHA-384";
    static final int HASH_SIZE_BYTES = 48;

    private HashUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    static MessageDigest newMessageDigest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(HASH_ALGORITHM + " algorithm not found", e);
        }
    }

    public static byte[] computeLeafHash(final MessageDigest digest, final MerkleLeaf leaf) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(leaf, "leaf must not be null");
        digest.reset();
        digest.update((byte) 0x00);
        digest.update(MerkleLeaf.PROTOBUF.toBytes(leaf).toByteArray());
        return digest.digest();
    }

    public static byte[] computeSingleChildHash(final MessageDigest digest, final byte[] childHash) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(childHash, "childHash must not be null");
        digest.reset();
        digest.update((byte) 0x01);
        digest.update(childHash);
        return digest.digest();
    }

    public static byte[] joinHashes(final MessageDigest digest, final byte[] left, final byte[] right) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(left, "left must not be null");
        requireNonNull(right, "right must not be null");
        digest.reset();
        digest.update((byte) 0x02);
        digest.update(left);
        digest.update(right);
        return digest.digest();
    }
}
