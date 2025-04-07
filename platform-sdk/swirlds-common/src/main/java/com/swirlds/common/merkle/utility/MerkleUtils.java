// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.exceptions.FailedRehashException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;

public final class MerkleUtils {

    private static final Logger logger = LogManager.getLogger(MerkleUtils.class);

    private MerkleUtils() {}

    /**
     * Invalidate the hashes of an entire tree (or subtree) rooted at the given node. Does not perform invalidation
     * for parts of the merkle tree that are self hashing.
     *
     * @param root
     * 		the root of the tree (or subtree)
     */
    public static void invalidateTree(final MerkleNode root) {
        if (root == null) {
            return;
        }
        root.treeIterator()
                .setFilter(node -> !node.isSelfHashing())
                .setDescendantFilter(node -> !node.isSelfHashing())
                .forEachRemaining(MerkleNode::invalidateHash);
    }

    /**
     * Rehash the entire tree, discarding any hashes that are already computed.
     * Does not rehash virtual parts of the tree that are self hashing.
     *
     * @param root
     * 		the root of the tree to hash
     * @return the computed hash of the {@code root} parameter or null if the parameter was null
     */
    public static Hash rehashTree(final MerkleCryptography merkleCryptography, final MerkleNode root) {
        if (root != null) {
            invalidateTree(root);
            final Future<Hash> future = merkleCryptography.digestTreeAsync(root);
            try {
                return future.get();
            } catch (InterruptedException e) {
                logger.error(EXCEPTION.getMarker(), "Interrupted while waiting for tree hashing to complete", e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logger.error(EXCEPTION.getMarker(), "Async tree hashing failed", e);
                throw new FailedRehashException(e);
            }
        }

        return null;
    }

    private static void buildMerkleString(
            final StringBuilder sb,
            final MerkleNode node,
            final int depth,
            final int indexInParent,
            final int parentNumberOfChildren,
            String indentation,
            final int maxDepth,
            final boolean printNodeDescription) {

        // Add indention
        if (parentNumberOfChildren > 0) {
            sb.append(indentation).append("  |");
            if (indexInParent < parentNumberOfChildren - 1) {
                indentation += "  |";
            } else {
                indentation += "   ";
            }
        }

        if (parentNumberOfChildren == 0) {
            sb.append("-(root) ");
        } else {
            sb.append("-(").append(indexInParent).append(") ");
        }

        if (node == null) {
            sb.append("null\n");
        } else {
            String[] classElements = node.getClass().toString().split("\\.");
            sb.append(classElements[classElements.length - 1])
                    .append(": " + (printNodeDescription ? node.toString() : ""));

            if (node.isLeaf()) {
                sb.append(node.toString()).append("\n");
            } else {
                final MerkleInternal internal = node.cast();
                if (maxDepth > 0 && depth + 1 > maxDepth) {
                    sb.append("...\n");
                } else {
                    sb.append("\n");
                    for (int childIndex = 0; childIndex < internal.getNumberOfChildren(); childIndex++) {
                        buildMerkleString(
                                sb,
                                internal.getChild(childIndex),
                                depth + 1,
                                childIndex,
                                internal.getNumberOfChildren(),
                                indentation,
                                maxDepth,
                                printNodeDescription);
                    }
                }
            }
        }
    }

    /**
     * Get a string representing a merkle tree.
     *
     * @param root
     * 		root of the merkle tree
     * @return a string which represents this merkle tree
     */
    public static String getMerkleString(final MerkleNode root) {
        return getMerkleString(root, false);
    }

    public static String getMerkleString(final MerkleNode root, final boolean printNodeDescription) {
        return getMerkleString(root, -1, printNodeDescription);
    }

    /**
     * Get a string representing a merkle tree. Do not include nodes below a given depth.
     *
     * @param root
     * 		root of the merke tree
     * @param maxDepth
     * 		max depth of nodes to be included in this string
     * @param printNodeDescription
     * 		whether print node description or not
     * @return a string which represents this merkle tree
     */
    public static String getMerkleString(
            final MerkleNode root, final int maxDepth, final boolean printNodeDescription) {
        if (root == null) {
            return "-(root) null";
        }
        final StringBuilder sb = new StringBuilder();
        buildMerkleString(sb, root, 1, 0, 0, "", maxDepth, printNodeDescription);
        return sb.toString();
    }

    /**
     * Return a string containing debug information about a node.
     */
    public static String merkleDebugString(final MerkleNode node) {
        final StringBuilder sb = new StringBuilder();

        sb.append("(");

        if (node == null) {
            sb.append("null");
        } else {
            sb.append(node.getClass().getName());
            sb.append(": route = ").append(node.getRoute());
            sb.append(", destroyed = ").append(node.isDestroyed());
            sb.append(", reservation count = ").append(node.getReservationCount());
            sb.append(", immutable = ").append(node.isImmutable());
            if (!node.isLeaf()) {
                sb.append(", child count = ").append(node.asInternal().getNumberOfChildren());
            }
        }

        sb.append(")");

        return sb.toString();
    }

    /**
     * Find the index of a given child within its parent. O(n) time in the number of children of the parent.
     * Sufficiently fast for tiny nodes (i.e. binary nodes), too slow for large nary nodes.
     *
     * @param parent
     * 		the parent in question
     * @param child
     * 		the child in question
     * @return the index of the child within the parent
     */
    public static int findChildPositionInParent(final MerkleInternal parent, final MerkleNode child) {
        for (int childIndex = 0; childIndex < parent.getNumberOfChildren(); childIndex++) {
            if (child == parent.getChild(childIndex)) {
                return childIndex;
            }
        }
        throw new IllegalStateException("node is not a child of the given parent");
    }
}
