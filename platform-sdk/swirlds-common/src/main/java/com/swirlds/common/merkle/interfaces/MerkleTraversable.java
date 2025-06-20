// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.interfaces;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteFactory;
import java.util.function.Consumer;

/**
 * Describes ways of traversing a merkle tree.
 */
public interface MerkleTraversable extends MerkleType, HasMerkleRoute {

    /**
     * Get the node that is reached by starting at this node and traversing along a provided route.
     *
     * @param route
     * 		the route to follow
     * @return the node at the end of the route
     */
    MerkleNode getNodeAtRoute(final MerkleRoute route);

    /**
     * Get the node that is reached by starting at this node and traversing along a provided route.
     *
     * @param steps
     * 		the steps in the route
     * @return the node at the end of the route
     */
    default MerkleNode getNodeAtRoute(final int... steps) {
        return getNodeAtRoute(MerkleRouteFactory.buildRoute(steps));
    }

    /**
     * Create a pre-ordered depth first iterator for this tree (or subtree).
     *
     * @return a configurable iterator
     */
    <T extends MerkleNode> MerkleIterator<T> treeIterator();

    /**
     * Execute a function on each non-null node in the subtree rooted at this node (which includes this node).
     *
     * @param operation
     * 		The function to execute.
     */
    default void forEachNode(final Consumer<MerkleNode> operation) {
        treeIterator().forEachRemaining(operation);
    }
}
