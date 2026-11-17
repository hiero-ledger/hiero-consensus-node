// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import org.hiero.base.concurrent.ThreadLocalStorage;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * A set of utility methods to compute Merkle hashes for a virtual map.
 */
public final class MerkleHasher {

    private MerkleHasher() {}

    /**
     * Calculates the empty root hash for a Merkle tree. Hash digest type is {@link
     * Cryptography#DEFAULT_DIGEST_TYPE}.
     *
     * @return the empty root hash
     */
    @NonNull
    public static Hash emptyRootHash() {
        final MessageDigest md = Cryptography.DEFAULT_DIGEST_TYPE.buildDigest();
        md.update((byte) 0x00);
        return new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /**
     * Calculates hash bytes for an internal node from its left and right child hashes. Hash digest type
     * is {@link Cryptography#DEFAULT_DIGEST_TYPE}.
     *
     * <p>The left hash must always be provided. The right hash is typically provided, too.
     * However, this method may also be called with a null right hash to calculate a root hash for a tree with only one
     * leaf node.
     *
     * <p>A note about performance: if this method is called from performance-critical code, consider
     * obtaining a writable message digest and pass it to {@link #internalNodeHashBytes(byte[], byte[],
     * WritableMessageDigest)} instead of calling this method.
     *
     * @param left  the left child hash
     * @param right the right child hash, or null if there is no right child
     * @return the calculated internal node hash
     */
    @NonNull
    public static byte[] internalNodeHashBytes(@NonNull byte[] left, @Nullable byte[] right) {
        final WritableMessageDigest digestWriter =
                ThreadLocalStorage.getWritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
        return internalNodeHashBytes(left, right, digestWriter);
    }

    /**
     * Calculates hash bytes for an internal node from its left and right child hashes.
     *
     * <p>The left hash must always be provided. The right hash is typically provided, too.
     * However, this method may also be called with a null right hash to calculate a root hash for a tree with only one
     * leaf node.
     *
     * @param left  the left child hash
     * @param right the right child hash, or null if there is no right child
     * @param digestWriter the message digest used to hash child hash bytes
     * @return the calculated internal node hash
     */
    @NonNull
    public static byte[] internalNodeHashBytes(
            @NonNull byte[] left, @Nullable byte[] right, @NonNull final WritableMessageDigest digestWriter) {
        // Unique value to make sure internal node hashes are different from leaf hashes. This
        // value indicates the number of child nodes. All internal virtual nodes have 2 children
        // except a root node in a tree with just one element / leaf.
        digestWriter.writeByte(right == null ? (byte) 0x01 : (byte) 0x02);
        digestWriter.writeBytes(left);
        if (right != null) {
            digestWriter.writeBytes(right);
        }
        // Calling digest() resets the digest
        return digestWriter.digest();
    }

    /**
     * Calculates a hash for an internal node from its left and right child node hashes. This method may be called with
     * the null righ hash to calculate the root hash for a tree with only one leaf node.
     *
     * @param left  the left child hash
     * @param right the right child hash, or null if there is no right child
     * @return the calculated internal node hash
     */
    @NonNull
    public static Hash internalNodeHash(
            @NonNull final Hash left,
            @Nullable final Hash right,
            @NonNull final WritableMessageDigest digestWriter,
            @NonNull final DigestType digestType) {
        final byte[] leftBytes = left.copyToByteArray();
        final byte[] rightBytes = (right != null) ? right.copyToByteArray() : null;
        final byte[] hashBytes = internalNodeHashBytes(leftBytes, rightBytes, digestWriter);
        return new Hash(hashBytes, digestType);
    }

    /**
     * Calculates the hash of a leaf record. Hash digest type is {@link Cryptography#DEFAULT_DIGEST_TYPE}.
     *
     * <p>A note about performance: if this method is called from performance-critical code, consider
     * obtaining a writable message digest and pass it to {@link #leafNodeHashBytes(VirtualLeafBytes,
     * WritableMessageDigest)} instead of calling this method.
     *
     * @param leaf the leaf bytes to hash
     * @return the computed hash
     */
    @NonNull
    public static byte[] leafNodeHashBytes(@NonNull final VirtualLeafBytes<?> leaf) {
        final WritableMessageDigest digestWriter =
                ThreadLocalStorage.getWritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
        return leafNodeHashBytes(leaf, digestWriter);
    }

    /**
     * Calculates the hash of a leaf record.
     *
     * @param leaf the leaf bytes to hash
     * @param digestWriter the message digest used to hash leaf bytes
     * @return the computed hash
     */
    @NonNull
    public static byte[] leafNodeHashBytes(
            @NonNull final VirtualLeafBytes<?> leaf, @NonNull final WritableMessageDigest digestWriter) {
        leaf.writeToForHashing(digestWriter);
        // Calling digest() resets the digest
        return digestWriter.digest();
    }

    /**
     * Computes the hash of a leaf record. Hash digest type is {@link Cryptography#DEFAULT_DIGEST_TYPE}.
     *
     * <p>A note about performance: if this method is called from performance-critical code, consider
     * obtaining a writable message digest and the corresponding message digest type and pass them
     * to {@link #leafNodeHash(VirtualLeafBytes, WritableMessageDigest, DigestType)} instead of
     * calling this method.
     *
     * @param leaf the leaf bytes to hash
     * @return the computed hash
     */
    @NonNull
    public static Hash leafNodeHash(@NonNull final VirtualLeafBytes<?> leaf) {
        final WritableMessageDigest digestWriter =
                ThreadLocalStorage.getWritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
        return new Hash(leafNodeHashBytes(leaf, digestWriter), Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /**
     * Computes the hash of a leaf record.
     *
     * @param leaf the leaf bytes to hash
     * @param digestWriter the message digest to hash leaf bytes
     * @param digestType the message digest type
     * @return the computed hash
     */
    @NonNull
    public static Hash leafNodeHash(
            @NonNull final VirtualLeafBytes<?> leaf,
            @NonNull final WritableMessageDigest digestWriter,
            @NonNull final DigestType digestType) {
        return new Hash(leafNodeHashBytes(leaf, digestWriter), digestType);
    }
}
