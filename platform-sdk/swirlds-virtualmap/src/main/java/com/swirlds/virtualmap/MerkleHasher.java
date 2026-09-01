// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.util.Objects;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

/**
 * Utility class to compute Merkle hashes for a virtual map.
 * <p>
 * This class <b>is not thread safe</b>, but it can be used in a thread-safe manner by using the {@link #threadSafeDefault()} method to get a thread-local instance.
 */
public final class MerkleHasher {

    /**
     * This thread-local gets a message digest that can be used for hashing on a per-thread basis.
     */
    private static final ThreadLocal<MerkleHasher> THREAD_LOCAL_DEFAULT =
            ThreadLocal.withInitial(() -> new MerkleHasher(Cryptography.DEFAULT_DIGEST_TYPE));

    private final DigestType digestType;
    private final WritableMessageDigest digestWriter;

    /**
     * Create hasher with default digest type - {@link Cryptography#DEFAULT_DIGEST_TYPE}
     */
    public MerkleHasher() {
        this(Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /**
     * Create hasher with provided digest type
     *
     * @param digestType digest type to build digest from and use for hashing
     */
    public MerkleHasher(@NonNull DigestType digestType) {
        this.digestType = Objects.requireNonNull(digestType, "digestType cannot be null");

        digestWriter = new WritableMessageDigest(digestType.buildDigest());
    }

    /**
     * @return thread-local instance using {@link Cryptography#DEFAULT_DIGEST_TYPE} - thread safe.
     */
    @NonNull
    public static MerkleHasher threadSafeDefault() {
        return THREAD_LOCAL_DEFAULT.get();
    }

    /**
     * @return digest type that is used for hashing
     */
    @NonNull
    public DigestType getDigestType() {
        return digestType;
    }

    /**
     * Calculates the empty root hash for a Merkle tree using the specified digest type.
     *
     * @param digestType the type of digest to use
     * @return the empty root hash
     */
    @NonNull
    public static Hash emptyRootHash(DigestType digestType) {
        final MessageDigest md = digestType.buildDigest();
        md.update((byte) 0x00);
        return new Hash(md.digest(), digestType);
    }

    /**
     * Calculates a hash for an internal node from its left and right child hashes.
     *
     * <p>The left hash must always be provided. The right hash is typically provided, too.
     * However, this method may also be called with a null right hash to calculate a root
     * hash for a tree with only one leaf node.
     *
     * @param left the left child hash
     * @param right the right child hash, or null if there is no right child
     * @return the calculated internal node hash
     */
    @NonNull
    public byte[] internalNodeHashBytes(@NonNull byte[] left, @Nullable byte[] right) {
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
     * Calculates a hash for an internal node and writes it into an existing byte array.
     *
     * <p>The output may alias either child hash because both child hashes are consumed before the
     * digest is written.
     *
     * @param left the left child hash
     * @param right the right child hash, or null if there is no right child
     * @param output the array into which the calculated hash is written
     * @param offset the offset in {@code output} at which the hash is written
     */
    public void internalNodeHashBytesInto(
            @NonNull final byte[] left, @Nullable final byte[] right, @NonNull final byte[] output, final int offset) {
        Objects.requireNonNull(left, "left cannot be null");
        Objects.checkFromIndexSize(
                offset, digestType.digestLength(), Objects.requireNonNull(output, "output cannot be null").length);

        digestWriter.writeByte(right == null ? (byte) 0x01 : (byte) 0x02);
        digestWriter.writeBytes(left, 0, left.length);
        if (right != null) {
            digestWriter.writeBytes(right, 0, right.length);
        }
        // Calling digestInto() resets the digest
        digestWriter.digestInto(output, offset);
    }

    /**
     * Calculates a hash for an internal node from its left and right child node hashes.
     * This method may be called with the null righ hash to calculate the root hash for a tree with only one leaf node.
     *
     * @param left the left child hash
     * @param right the right child hash, or null if there is no right child
     * @return the calculated internal node hash
     */
    @NonNull
    public Hash internalNodeHash(@NonNull final Hash left, @Nullable final Hash right) {
        final byte[] leftBytes = left.copyToByteArray();
        final byte[] rightBytes = (right != null) ? right.copyToByteArray() : null;
        final byte[] hashBytes = internalNodeHashBytes(leftBytes, rightBytes);
        return new Hash(hashBytes, digestType);
    }

    /**
     * Calculates the hash of a leaf record.
     *
     * @param leaf the leaf bytes to hash
     * @return the computed hash
     */
    @NonNull
    public byte[] leafNodeHashBytes(@NonNull final VirtualLeafBytes<?> leaf) {
        leaf.writeToForHashing(digestWriter);
        // Calling digest() resets the digest
        return digestWriter.digest();
    }

    /**
     * Computes the hash of a leaf record.
     *
     * @param leaf the leaf bytes to hash
     * @return the computed hash
     */
    @NonNull
    public Hash leafNodeHash(@NonNull final VirtualLeafBytes<?> leaf) {
        return new Hash(leafNodeHashBytes(leaf), digestType);
    }
}
