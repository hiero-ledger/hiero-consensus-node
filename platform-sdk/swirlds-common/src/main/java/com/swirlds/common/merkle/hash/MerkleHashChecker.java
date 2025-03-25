// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.hash;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LoggingUtils.plural;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;

public final class MerkleHashChecker {

    private static final Logger logger = LogManager.getLogger(MerkleHashChecker.class);
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private MerkleHashChecker() {}

    /**
     * Traverses the merkle tree and checks if there are any hashes that are not valid. Recalculates all hashes
     * that have been calculated externally and check them against the getHash value.
     *
     * @param root
     * 		the root of the merkle tree
     * @param mismatchCallback
     * 		the method to call if a mismatch is found. May be called many times.
     */
    public static void findInvalidHashes(
            final MerkleCryptography merkleCryptography,
            final MerkleNode root,
            final Consumer<MerkleNode> mismatchCallback) {
        if (root == null) {
            return;
        }

        root.forEachNode(node -> findInvalidHash(merkleCryptography, node, mismatchCallback));
    }

    private static void findInvalidHash(
            final MerkleCryptography merkleCryptography,
            final MerkleNode node,
            final Consumer<MerkleNode> mismatchCallback) {
        // some nodes calculate their own hash, we could potentially check these if we serialize and deserialize
        // them and then check their hash
        if (node == null || node.isSelfHashing()) {
            return;
        }

        final Hash old = node.getHash();
        if (old == null) {
            mismatchCallback.accept(node);
            return;
        }

        final Hash recalculated;
        if (node.isLeaf()) {
            recalculated = CRYPTOGRAPHY.digestSync((SerializableHashable) node, false);
        } else {
            final MerkleInternal internal = node.asInternal();
            for (int childIndex = 0; childIndex < internal.getNumberOfChildren(); childIndex++) {
                final MerkleNode child = internal.getChild(childIndex);
                if (child != null && child.getHash() == null) {
                    // It is impossible to compute the hash of a parent if the child has a null hash
                    return;
                }
            }
            recalculated = merkleCryptography.digestSync(internal, false);
        }

        if (!old.equals(recalculated)) {
            mismatchCallback.accept(node);
        }
    }

    /**
     * Get a list of all nodes in a tree that have an invalid hash.
     * Returns an empty list if the entire tree has valid hashes.
     *
     * @param merkleCryptography
     *      the cryptography provider to use for hashing
     * @param root
     * 		the root of the tree in question
     * @return a list of nodes with invalid hashes (if there are any)
     */
    public static List<MerkleNode> getNodesWithInvalidHashes(
            final MerkleCryptography merkleCryptography, final MerkleNode root) {
        final List<MerkleNode> nodesWithInvalidHashes = new LinkedList<>();
        findInvalidHashes(merkleCryptography, root, nodesWithInvalidHashes::add);
        return nodesWithInvalidHashes;
    }

    /**
     * Check if all of the hashes within a tree are valid.
     * Write a detailed message to the log if invalid hashes are detected in the tree.
     *
     * @param root
     * 		the root of the tree to check
     * @param context
     * 		the context that the check is being done in. This is written to the log if a problem is detected.
     * @param limit
     * 		the maximum number of invalid nodes to log. If the entire tree is invalid then the log could be massively
     * 		spammed. A sane limit reduces the amount logged in that situation.
     * @return true if the tree is valid, false if it is not valid
     */
    public static boolean checkHashAndLog(
            final MerkleCryptography merkleCryptography, final MerkleNode root, final String context, final int limit) {

        final List<MerkleNode> nodesWithInvalidHashes = getNodesWithInvalidHashes(merkleCryptography, root);

        if (nodesWithInvalidHashes.isEmpty()) {
            return true;
        }

        final StringBuilder sb = new StringBuilder();

        sb.append("Invalid merkle hashes detected in tree. Context = ")
                .append(context)
                .append("\n");

        int nodesWithNullHash = 0;
        int nodesWithInvalidHash = 0;

        for (final MerkleNode node : nodesWithInvalidHashes) {
            if (node.getHash() == null) {
                nodesWithNullHash++;
            } else {
                nodesWithInvalidHash++;
            }
        }

        sb.append(nodesWithNullHash)
                .append(" ")
                .append(plural(nodesWithNullHash, "node"))
                .append(" ")
                .append(plural(nodesWithNullHash, "has a null hash", "have null hashes"))
                .append(". ");

        sb.append(nodesWithInvalidHash)
                .append(" ")
                .append(plural(nodesWithInvalidHash, "node"))
                .append(" ")
                .append(plural(nodesWithInvalidHash, "has an invalid hash", "have invalid hashes"))
                .append(".\n");

        if (nodesWithInvalidHashes.size() > limit) {
            sb.append("Number of nodes exceeds maximum limit, only logging the first ")
                    .append(limit)
                    .append(" ")
                    .append(plural(limit, "node"))
                    .append(".\n");
        }

        int count = 0;
        for (final MerkleNode node : nodesWithInvalidHashes) {
            count++;
            if (count > limit) {
                break;
            }

            sb.append("   - ")
                    .append(node.getClass().getSimpleName())
                    .append(" @ ")
                    .append(node.getRoute())
                    .append(" ");
            if (node.getHash() == null) {
                sb.append("has a null hash");
            } else {
                sb.append("has an invalid hash");
            }
            sb.append("\n");
        }

        logger.error(EXCEPTION.getMarker(), sb);
        return false;
    }
}
