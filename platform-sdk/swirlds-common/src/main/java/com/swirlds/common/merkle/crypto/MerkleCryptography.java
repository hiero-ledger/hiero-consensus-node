// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.crypto;

import static org.hiero.base.crypto.Cryptography.DEFAULT_DIGEST_TYPE;
import static org.hiero.base.crypto.Cryptography.DEFAULT_SET_HASH;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import java.util.List;
import java.util.concurrent.Future;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Hashable;

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
     * Computes a cryptographic hash for the {@link MerkleInternal} instance. The hash is passed to the object by
     * calling {@link Hashable#setHash(Hash)} if setHash is true.
     *
     * @param node    the MerkleInternal to hash
     * @param setHash should be set to true if the calculated should be assigned to the node
     * @return the cryptographic hash for the {@link MerkleInternal} object
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    Hash digestSync(final MerkleInternal node, boolean setHash);

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
     * @param leaf the {@link MerkleLeaf} to hash
     * @throws CryptographyException if an unrecoverable error occurs while computing the digest
     */
    Hash digestSync(MerkleLeaf leaf);

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
            return digestSync(node.asLeaf());
        }

        final MerkleInternal node1 = node.asInternal();
        return digestSync(node1, DEFAULT_SET_HASH);
    }

    /**
     * Compute the hash of the merkle tree synchronously on the caller's thread.
     *
     * @param root the root of the tree to hash
     * @return The hash of the tree.
     */
    Hash digestTreeSync(final MerkleNode root);

    /**
     * Compute the hash of the merkle tree on multiple worker threads.
     *
     * @param root the root of the tree to hash
     * @return the {@link com.swirlds.common.merkle.hash.FutureMerkleHash} for the {@link MerkleNode} object
     */
    Future<Hash> digestTreeAsync(final MerkleNode root);
}
