// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.hashOfAll;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384HashOfAll;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;

/**
 * Utility methods for block implementation.
 */
public class BlockImplUtils {
    public static final byte[] LEAF_PREFIX = {0x0};
    public static final Bytes LEAF_PREFIX_BYTES = Bytes.wrap(LEAF_PREFIX);
    public static final byte[] SINGLE_CHILD_INTERNAL_NODE_PREFIX = {0x1};
    public static final byte[] INTERNAL_NODE_PREFIX = {0x2};
    public static final Bytes INTERNAL_NODE_PREFIX_BYTES = Bytes.wrap(INTERNAL_NODE_PREFIX);

    /**
     * Prevent instantiation
     */
    private BlockImplUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Appends the given hash to the given hashes. If the number of hashes exceeds the given maximum, the oldest hash
     * is removed.
     * @param hash the hash to append
     * @param hashes the hashes
     * @param maxHashes the maximum number of hashes
     * @return the new hashes
     */
    public static Bytes appendHash(@NonNull final Bytes hash, @NonNull final Bytes hashes, final int maxHashes) {
        final var limit = HASH_SIZE * maxHashes;
        final byte[] bytes = hashes.toByteArray();
        final byte[] newBytes;
        if (bytes.length < limit) {
            newBytes = new byte[bytes.length + HASH_SIZE];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        } else {
            newBytes = bytes;
            System.arraycopy(newBytes, HASH_SIZE, newBytes, 0, newBytes.length - HASH_SIZE);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        }
        return Bytes.wrap(newBytes);
    }

    /**
     * Hashes the given left and right hashes. Note: this method does <b>not</b> add any byte prefixes
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static Bytes combine(@NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        return Bytes.wrap(combine(leftHash.toByteArray(), rightHash.toByteArray()));
    }

    /**
     * Hashes the given left and right hashes. Note: this method does <b>not</b> add any byte prefixes
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static byte[] combine(@NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return sha384HashOfAll(leftHash, rightHash).toByteArray();
    }

    public static byte[] hashLeaf(@NonNull final byte[] leafData) {
        return sha384HashOf(LEAF_PREFIX, leafData);
    }

    // This method _may_ be temporary! State items currently require a wonky legacy prefix to work,
    // which may be modified by the state team
    // (FUTURE) Either remove or write tests for this
    public static Bytes hashBlockItemLeaf(@NonNull final Bytes leafData) {
        return hashBlockItemLeaf(leafData.toByteArray());
    }

    // This method _may_ be temporary! State items currently require a wonky legacy prefix to work,
    // which may be modified by the state team
    // (FUTURE) Either remove or write tests for this
    public static Bytes hashBlockItemLeaf(@NonNull final byte[] leafData) {
        final MessageDigest digest = sha384DigestOrThrow();
        return doLegacyLeafHash(digest, (byte) 0x12, leafData);
    }

    // This method _may_ be temporary! State items currently require a wonky legacy prefix to work,
    // which may be modified by the state team
    // (FUTURE) Either remove or write tests for this
    public static Bytes hashTimestampLeaf(@NonNull final Bytes leafData) {
        final MessageDigest digest = sha384DigestOrThrow();
        return doLegacyLeafHash(digest, (byte) 0x0A, leafData.toByteArray());
    }

    private static Bytes doLegacyLeafHash(
            @NonNull final MessageDigest digest, final byte legacyPrefix, @NonNull final byte[] leafData) {
        digest.update(LEAF_PREFIX_BYTES.toByteArray());
        digest.update(legacyPrefix);
        long leafLength = leafData.length;
        if (leafLength < 0 || leafLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("leafBytes length out of range: " + leafLength);
        }
        updateVarint(digest, (int) leafLength);
        digest.update(leafData);

        return Bytes.wrap(digest.digest());
    }

    static Bytes hashLeaf(@NonNull final Bytes leafData) {
        return sha384HashOfAll(LEAF_PREFIX_BYTES, leafData);
    }

    static Bytes hashLeaf(@NonNull final MessageDigest digest, @NonNull final Bytes leafData) {
        return hashOfAll(digest, LEAF_PREFIX_BYTES, leafData);
    }

    static byte[] hashLeaf(@NonNull final MessageDigest digest, @NonNull final byte[] leafData) {
        return hashOfAll(digest, LEAF_PREFIX, leafData);
    }

    private static void updateVarint(final MessageDigest digest, int value) {
        // See `VirtualLeafBytes.writeToForHashing(...)`'s implementation, which essentially encodes the former
        // `MerkleLeaf` class into the hash of state item leaves
        while ((value & ~0x7F) != 0) {
            digest.update((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        digest.update((byte) value);
    }

    public static Bytes hashInternalNodeSingleChild(@NonNull final Bytes hash) {
        return sha384HashOfAll(SINGLE_CHILD_INTERNAL_NODE_PREFIX, hash.toByteArray());
    }

    public static Bytes hashInternalNode(@NonNull final Bytes leftHash, @NonNull final byte[] rightHash) {
        return sha384HashOf(INTERNAL_NODE_PREFIX_BYTES, leftHash, rightHash);
    }

    public static Bytes hashInternalNode(@NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        return sha384HashOfAll(INTERNAL_NODE_PREFIX_BYTES, leftHash, rightHash);
    }

    public static byte[] hashInternalNode(@NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return sha384HashOfAll(INTERNAL_NODE_PREFIX, leftHash, rightHash).toByteArray();
    }

    public static byte[] hashInternalNode(
            @NonNull final MessageDigest digest, @NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        return hashOfAll(digest, INTERNAL_NODE_PREFIX, leftHash, rightHash);
    }
}
