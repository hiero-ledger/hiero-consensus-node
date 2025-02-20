// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators;

/**
 * Defines different iteration orders for merkle trees.
 */
public enum MerkleIterationOrder {
    /**
     * A depth first traversal where parents are visited after their children (i.e. standard depth first).
     * This is the default iteration order for a {@link MerkleIterator}.
     */
    POST_ORDERED_DEPTH_FIRST,
    /**
     * Similar to {@link #POST_ORDERED_DEPTH_FIRST}, but with order between sibling subtrees reversed.
     */
    REVERSE_POST_ORDERED_DEPTH_FIRST,
    /**
     * Similar to {@link #POST_ORDERED_DEPTH_FIRST}, but with order between sibling subtrees randomized.
     */
    POST_ORDERED_DEPTH_FIRST_RANDOM,
    /**
     * A depth first traversal where parents are visited before their children.
     */
    PRE_ORDERED_DEPTH_FIRST,
    /**
     * A breadth first traversal.
     */
    BREADTH_FIRST,
}
