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
final class HashUtils {

    private static final String HASH_ALGORITHM = "SHA-384";
    static final int HASH_SIZE_BYTES = 48;

    /**
     * The legacy {@code MerkleLeaf} message (removed after migrating its content into {@code MerklePath}'s leaf-bytes
     * fields) encoded leaf content as a single {@code bytes} field in a oneof with these field numbers:
     * <ul>
     *   <li>timestamp leaf: field 1</li>
     *   <li>block item leaf: field 2</li>
     *   <li>state item leaf: field 3</li>
     * </ul>
     *
     * <p>Leaf hashes must remain compatible with historical Merkle hashing, so we continue to hash leaf bytes as if
     * they were serialized inside the legacy {@code MerkleLeaf} message using these field numbers.
     */
    private static final byte LEGACY_TIMESTAMP_LEAF_TAG = 0x0A; // (1 << 3) | 2
    private static final byte LEGACY_BLOCK_ITEM_LEAF_TAG = 0x12; // (2 << 3) | 2
    private static final byte LEGACY_STATE_ITEM_LEAF_TAG = 0x1A; // (3 << 3) | 2

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
     * Computes the hash of a timestamp leaf node with the leaf prefix (0x00).
     *
     * <p>Format: SHA-384(0x00 || protobuf(MerkleLeaf{block_consensus_timestamp = leafBytes}))
     *
     * @param digest the digest instance to use (will be reset)
     * @param leafBytes the timestamp leaf bytes
     * @return the computed leaf hash
     */
    static byte[] computeTimestampLeafHash(final MessageDigest digest, final Bytes leafBytes) {
        return computeLegacyMerkleLeafHash(digest, LEGACY_TIMESTAMP_LEAF_TAG, leafBytes);
    }

    /**
     * Computes the hash of a block-item leaf node with the leaf prefix (0x00).
     *
     * <p>Format: SHA-384(0x00 || protobuf(MerkleLeaf{block_item = leafBytes}))
     *
     * @param digest the digest instance to use (will be reset)
     * @param leafBytes the block item leaf bytes
     * @return the computed leaf hash
     */
    static byte[] computeBlockItemLeafHash(final MessageDigest digest, final Bytes leafBytes) {
        return computeLegacyMerkleLeafHash(digest, LEGACY_BLOCK_ITEM_LEAF_TAG, leafBytes);
    }

    /**
     * Computes the hash of a state-item leaf node with the leaf prefix (0x00).
     *
     * <p>Format: SHA-384(0x00 || protobuf(MerkleLeaf{state_item = leafBytes}))
     *
     * @param digest the digest instance to use (will be reset)
     * @param leafBytes the state item leaf bytes
     * @return the computed leaf hash
     */
    static byte[] computeStateItemLeafHash(final MessageDigest digest, final Bytes leafBytes) {
        return computeLegacyMerkleLeafHash(digest, LEGACY_STATE_ITEM_LEAF_TAG, leafBytes);
    }

    /**
     * Computes {@code SHA-384(0x00 || encodedLegacyMerkleLeaf)} where {@code encodedLegacyMerkleLeaf} is the protobuf
     * encoding of a hypothetical legacy {@code MerkleLeaf} message that sets exactly one bytes field corresponding
     * to the given tag.
     */
    private static byte[] computeLegacyMerkleLeafHash(final MessageDigest digest, final byte legacyFieldTag, final Bytes leafBytes) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(leafBytes, "leafBytes must not be null");
        digest.reset();
        digest.update((byte) 0x00);
        digest.update(legacyFieldTag);
        final long leafLength = leafBytes.length();
        if (leafLength < 0 || leafLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("leafBytes length out of range: " + leafLength);
        }
        updateVarint(digest, (int) leafLength);
        digest.update(leafBytes.toByteArray());
        return digest.digest();
    }

    /**
     * Updates the digest with a protobuf varint encoding of {@code value}.
     *
     * @param digest the digest to update
     * @param value a non-negative integer
     */
    private static void updateVarint(final MessageDigest digest, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint value must be non-negative");
        }
        while ((value & ~0x7F) != 0) {
            digest.update((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        digest.update((byte) value);
    }

    static byte[] computeSingleChildHash(final MessageDigest digest, final byte[] childHash) {
        requireNonNull(digest, "digest must not be null");
        requireNonNull(childHash, "childHash must not be null");
        digest.reset();
        digest.update((byte) 0x01);
        digest.update(childHash);
        return digest.digest();
    }

    static byte[] joinHashes(final MessageDigest digest, final byte[] left, final byte[] right) {
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
