// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto;

import static com.swirlds.common.crypto.Cryptography.DEFAULT_DIGEST_TYPE;
import static com.swirlds.common.crypto.Cryptography.DEFAULT_SET_HASH;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.Hashable;
import com.swirlds.common.crypto.SerializableHashable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Cryptography service that provides specific functions for merkle classes.
 */
public interface MerkleCryptography {

    /**
     * Computes a cryptographic hash for the {@link MerkleNode} instance. The hash is passed to the object by
     * calling {@link Hashable#setHash(Hash)}. Convenience method that defaults to {@link DigestType#SHA_384} message
     * digests.
     *
     * @param node the MerkleInternal to hash
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    default Hash digestSync(final MerkleNode node) {
        return digestSync(node, DEFAULT_DIGEST_TYPE);
    }

    /**
     * Same as {@link Cryptography#digestSync(SerializableHashable, DigestType, boolean)} with setHash set to true
     */
    default Hash digestSync(final MerkleInternal node, final DigestType digestType) {
        return digestSync(node, digestType, DEFAULT_SET_HASH);
    }

    /**
     * Computes a cryptographic hash for the {@link MerkleInternal} instance. The hash is passed to the object by
     * calling {@link Hashable#setHash(Hash)} if setHash is true.
     *
     * @param node       the MerkleInternal to hash
     * @param digestType the type of digest used to compute the hash
     * @param setHash    should be set to true if the calculated should be assigned to the node
     * @return the cryptographic hash for the {@link MerkleInternal} object
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    Hash digestSync(final MerkleInternal node, final DigestType digestType, boolean setHash);

    /**
     * Same as {@link Cryptography#digestSync(SerializableHashable, DigestType, boolean)} with setHash set to true
     */
    default Hash digestSync(final MerkleInternal node, final List<Hash> childHashes) {
        return digestSync(node, childHashes, DEFAULT_SET_HASH);
    }

    /**
     * Computes a cryptographic hash for the {@link MerkleInternal} instance. Requires a list of child hashes, as it is
     * possible that the MerkleInternal has not yet been given its children. The hash is passed to the object by calling
     * {@link Hashable#setHash(Hash)} if setHash is true.
     *
     * @param node        the MerkleInternal to hash
     * @param childHashes a list of the hashes of this node's children
     * @param setHash     should be set to true if the calculated should be assigned to the node
     * @return the cryptographic hash for the {@link MerkleInternal} object
     */
    Hash digestSync(final MerkleInternal node, final List<Hash> childHashes, boolean setHash);

    /**
     * Computes a cryptographic hash for the {@link MerkleLeaf} instance. The hash is passed to the object by calling
     * {@link Hashable#setHash(Hash)}.
     *
     * @param leaf       the {@link MerkleLeaf} to hash
     * @param digestType the type of digest used to compute the hash
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    Hash digestSync(MerkleLeaf leaf, DigestType digestType);

    /**
     * Computes a cryptographic hash for the {@link MerkleNode} instance. The hash is passed to the object by calling
     * {@link Hashable#setHash(Hash)}.
     *
     * @param node       the {@link MerkleNode} to hash
     * @param digestType the type of digest used to compute the hash
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    default Hash digestSync(MerkleNode node, DigestType digestType) {
        if (node.isLeaf()) {
            return digestSync(node.asLeaf(), digestType);
        }

        return digestSync(node.asInternal(), digestType);
    }

    /**
     * Compute the hash of the merkle tree synchronously on the caller's thread.
     *
     * @param root       the root of the tree to hash
     * @param digestType the type of digest used to compute the hash
     * @return The hash of the tree.
     */
    Hash digestTreeSync(final MerkleNode root, final DigestType digestType);

    /**
     * Same as {@link #digestTreeSync(MerkleNode, DigestType)}  with DigestType set to SHA_384
     *
     * @param root the root of the tree to hash
     * @return the cryptographic hash for the {@link MerkleNode} object
     */
    default Hash digestTreeSync(final MerkleNode root) {
        return digestTreeSync(root, DEFAULT_DIGEST_TYPE);
    }

    /**
     * Compute the hash of the merkle tree on multiple worker threads.
     *
     * @param root       the root of the tree to hash
     * @param digestType the type of digest used to compute the hash
     * @return the {@link com.swirlds.common.merkle.hash.FutureMerkleHash} for the {@link MerkleNode} object
     */
    Future<Hash> digestTreeAsync(final MerkleNode root, final DigestType digestType);

    /**
     * Same as {@link #digestTreeAsync(MerkleNode, DigestType)}  with DigestType set to SHA_384
     *
     * @param root the root of the tree to hash
     * @return the {@link com.swirlds.common.merkle.hash.FutureMerkleHash} for the {@link MerkleNode} object
     */
    default Future<Hash> digestTreeAsync(final MerkleNode root) {
        return digestTreeAsync(root, DEFAULT_DIGEST_TYPE);
    }
}
