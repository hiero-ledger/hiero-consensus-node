// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.common.merkle.iterators.MerkleIterationOrder.PRE_ORDERED_DEPTH_FIRST;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.utility.EmptyIterator;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.hiero.consensus.model.crypto.Hash;

/**
 * Utilities for comparing states.
 */
public final class SignedStateComparison {
    private SignedStateComparison() {}

    /**
     * Get the hash of a node. If the node is null then return the hash of null.
     */
    private static Hash getNodeHash(final MerkleNode node) {
        if (node != null) {
            final Hash hash = node.getHash();
            if (hash == null) {
                throw new IllegalStateException(
                        "Node " + node.getClass().getName() + " at position " + node.getRoute() + " is unhashed");
            }
            return hash;
        } else {
            return Cryptography.NULL_HASH;
        }
    }

    /**
     * Get a node at a given position within a tree. If there is no node in that position then return null.
     */
    private static MerkleNode getNodeAtRoute(final MerkleNode root, final MerkleRoute route) {
        try {
            return root.getNodeAtRoute(route);
        } catch (final MerkleRouteException e) {
            return null;
        }
    }

    /**
     * Create a filter for the iterator that compares states.
     */
    private static BiPredicate<MerkleNode, MerkleRoute> buildFilter(final MerkleNode rootB) {
        return (final MerkleNode nodeA, final MerkleRoute routeA) -> {
            final MerkleNode nodeB = getNodeAtRoute(rootB, routeA);
            return !getNodeHash(nodeA).equals(getNodeHash(nodeB));
        };
    }

    /**
     * Create a descendant filter for the iterator that compares states.
     */
    private static Predicate<MerkleInternal> buildDescendantFilter(final MerkleNode rootB, final boolean deep) {
        return (final MerkleInternal nodeA) -> {
            final MerkleNode nodeB = getNodeAtRoute(rootB, nodeA.getRoute());

            if (nodeB == null || nodeB.isLeaf()) {
                // There is no point in iterating over descendants if nodeB is incapable of having them
                return false;
            }

            return deep || !getNodeHash(nodeA).equals(getNodeHash(nodeB));
        };
    }

    /**
     * Create a lambda that will transform the comparison iterator into an iterator that returns pairs of nodes that
     * are different.
     */
    private static BiFunction<MerkleNode, MerkleRoute, MismatchedNodes> buildTransformer(final MerkleNode rootB) {
        return (final MerkleNode nodeA, final MerkleRoute routeA) -> {
            final MerkleNode nodeB = getNodeAtRoute(rootB, routeA);
            return new MismatchedNodes(nodeA, nodeB);
        };
    }

    /**
     * Builds an iterator that walks over the parts of the merkle trees that do not match each other.
     * Nodes are visited in pre-ordered depth first order.
     *
     * @param rootA
     * 		the root of tree A, must be hashed
     * @param rootB
     * 		the root of tree B, must be hashed
     * @param deep
     * 		if true then use deep comparison. This is only needed if there are internal node hashes that
     * 		are incorrect, causing the hash mismatch to be invisible from the root of the tree. This
     * 		may take significantly longer than a shallow comparison.
     * @return an iterator that contains differences between tree A and tree B
     */
    public static Iterator<MismatchedNodes> mismatchedNodeIterator(
            final MerkleNode rootA, final MerkleNode rootB, final boolean deep) {

        if (rootA == null && rootB == null) {
            return new EmptyIterator<>();
        }

        if (rootA == null) {
            return List.of(new MismatchedNodes(null, rootB)).iterator();
        }

        if (rootB == null) {
            return List.of(new MismatchedNodes(rootA, null)).iterator();
        }

        // This iterator could be made more efficient, but since the differences in a state are likely to be
        // small compared to the size of the state, such an optimization may not be worth the effort.
        return rootA.treeIterator()
                .setOrder(PRE_ORDERED_DEPTH_FIRST)
                .ignoreNull(false)
                .setFilter(buildFilter(rootB))
                .setDescendantFilter(buildDescendantFilter(rootB, deep))
                .transform(buildTransformer(rootB));
    }

    /**
     * Print to standard out all differences between two merkle trees.
     *
     * @param nodeIterator
     * 		an iterator that walks over mismatched nodes
     * @param limit
     * 		the maximum number of nodes to print
     */
    public static void printMismatchedNodes(final Iterator<MismatchedNodes> nodeIterator, final int limit) {

        if (nodeIterator.hasNext()) {
            System.out.println("States do not match.\n");
        } else {
            System.out.println("States are identical.");
            return;
        }

        final TextTable table =
                new TextTable().setBordersEnabled(false).setExtraPadding(3).addRow("", "", "State A", "State B");

        int count = 0;

        while (nodeIterator.hasNext()) {
            count++;
            if (count > limit) {
                break;
            }

            nodeIterator.next().appendNodeDescriptions(table);
        }

        System.out.println(table.render());

        if (nodeIterator.hasNext()) {
            int leftoverNodes = 0;
            while (nodeIterator.hasNext()) {
                nodeIterator.next();
                leftoverNodes++;
            }

            System.out.println("Maximum number of differences printed. " + leftoverNodes
                    + " difference" + (leftoverNodes == 1 ? "" : "s") + " remain" + (leftoverNodes == 1 ? "s" : "")
                    + ".");
        }
    }
}
