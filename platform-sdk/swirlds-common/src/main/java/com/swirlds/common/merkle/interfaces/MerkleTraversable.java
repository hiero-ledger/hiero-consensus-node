/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
