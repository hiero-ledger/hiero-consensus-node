// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    /**
     * Computes the raw block-tree leaf hash: {@code SHA-384(0x00 || bytes)}.
     *
     * <p>Use this for block-tree leaves that are NOT represented as standalone {@link com.hedera.hapi.block.stream.MerklePath}
     * leaf fields — for example, the block timestamp used as a sibling in state-proof extension paths.
     * This does NOT wrap bytes in a legacy {@code MerkleLeaf} proto tag, matching the
     * computation in {@code BlockImplUtils.hashLeaf()}.
     *
     * @param digest the digest instance to use (will be reset)
     * @param bytes the raw bytes to hash
     * @return the computed leaf hash
     */
    public static byte[] computeRawLeafHash(final MessageDigest digest, final Bytes bytes) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(bytes, "bytes must not be null");
        digest.reset();
        digest.update((byte) 0x00);
        digest.update(bytes.toByteArray());
        return digest.digest();
    }

    /**
     * Computes the hash of a VirtualMap state-item leaf: {@code SHA-384(0x00 || stateItemBytes)}.
     *
     * <p>Matches {@code VirtualLeafBytes.writeToForHashing()} on the current platform, which writes
     * the leaf-prefix byte followed directly by the serialised {@code StateItem} bytes (field 2 = key,
     * field 3 = value) without any enclosing {@code MerkleLeaf} wrapper tag.
     *
     * <p>Use this function whenever the sibling hashes accompanying the leaf were produced by
     * {@code VirtualMapStateImpl.getMerkleProof()}.
     *
     * @param digest the digest instance to use (will be reset)
     * @param stateItemBytes the serialised {@code StateItem} bytes (key field 2 + value field 3)
     * @return the computed leaf hash
     */
    public static byte[] computeVirtualMapStateLeafHash(final MessageDigest digest, final Bytes stateItemBytes) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(stateItemBytes, "stateItemBytes must not be null");
        digest.reset();
        digest.update((byte) 0x00);
        digest.update(stateItemBytes.toByteArray());
        return digest.digest();
    }

    /**
     * Computes SHA-384(0x01 || childHash) — the single-child internal-node hash format.
     *
     * @param digest    a fresh or reset SHA-384 {@link MessageDigest}
     * @param childHash the child node's hash bytes
     * @return the resulting hash bytes
     */
    public static byte[] computeSingleChildHash(final MessageDigest digest, final byte[] childHash) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(childHash, "childHash must not be null");
        digest.reset();
        digest.update((byte) 0x01);
        digest.update(childHash);
        return digest.digest();
    }

    /**
     * Computes SHA-384(0x02 || left || right) — the two-child internal-node hash format.
     *
     * @param digest a fresh or reset SHA-384 {@link MessageDigest}
     * @param left   the left child's hash bytes
     * @param right  the right child's hash bytes
     * @return the resulting hash bytes
     */
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
