// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.hashOfAll;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384HashOfAll;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import org.hiero.base.crypto.DigestType;

/**
 * Utility methods for block implementation.
 */
public class BlockImplUtils {
    /** The size in bytes of a single SHA-384 block hash. */
    public static final int HASH_SIZE = DigestType.SHA_384.digestLength();

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
     * Given a concatenated sequence of 48-byte block hashes, where the rightmost hash was for the given last block
     * number, returns either the hash of the block at the given block number, or null if the block number is out of
     * range. This is block-format agnostic: it is used both for the legacy {@code BlockInfo.blockHashes} and for the
     * {@code BlockStreamInfo.trailingBlockHashes}.
     *
     * @param blockHashes the concatenated sequence of block hashes
     * @param lastBlockNo the block number of the rightmost hash in the sequence
     * @param blockNo the block number of the hash to return
     * @return the hash of the block at the given block number if available, null otherwise
     */
    public static @Nullable Bytes blockHashByBlockNumber(
            @NonNull final Bytes blockHashes, final long lastBlockNo, final long blockNo) {
        final var blocksAvailable = blockHashes.length() / HASH_SIZE;

        // Smart contracts (and other services) call this API. Should a smart contract call this, we don't really
        // want to throw an exception. So we will just return null, which is also valid. Basically, if the block
        // doesn't exist, you get null.
        if (blockNo < 0) {
            return null;
        }
        final var firstAvailableBlockNo = lastBlockNo - blocksAvailable + 1;
        // If blocksAvailable == 0, then firstAvailable == blockNo; and all numbers are
        // either less than or greater than or equal to blockNo, so we return unavailable
        if (blockNo < firstAvailableBlockNo || blockNo > lastBlockNo) {
            return null;
        } else {
            long offset = (blockNo - firstAvailableBlockNo) * HASH_SIZE;
            return blockHashes.slice(offset, HASH_SIZE);
        }
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

    public static Bytes hashLeaf(@NonNull final Bytes leafData) {
        return sha384HashOfAll(LEAF_PREFIX_BYTES, leafData);
    }

    public static Bytes hashLeaf(@NonNull final MessageDigest digest, @NonNull final Bytes leafData) {
        return hashOfAll(digest, LEAF_PREFIX_BYTES, leafData);
    }

    public static byte[] hashLeaf(@NonNull final MessageDigest digest, @NonNull final byte[] leafData) {
        return hashOfAll(digest, LEAF_PREFIX, leafData);
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
