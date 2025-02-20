// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.interfaces;

import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import com.swirlds.common.merkle.route.MerkleRoute;

/**
 * An object with a merkle route.
 */
public interface HasMerkleRoute {

    /**
     * Returns the value specified by setRoute(), i.e. the route from the root of the tree down to this node.
     *
     * If setRoute() has not yet been called, this method should return an empty merkle route.
     */
    MerkleRoute getRoute();

    /**
     * <p>
     * Get the depth of this node, with the root of a tree having a depth of 0.
     * </p>
     *
     * <p>
     * Warning: this method may have high overhead depending on the {@link MerkleRoute} implementation, and should
     * be used with appropriate caution.
     * </p>
     *
     * @return the depth of this node within the tree
     */
    default int getDepth() {
        return getRoute().size();
    }

    /**
     * This method is used to store the route from the root to this node.
     *
     * It is expected that the value set by this method be stored and returned by getPath().
     *
     * This method should NEVER be called manually. Only merkle utility code in
     * {@link com.swirlds.common.merkle.impl.internal.AbstractMerkleInternal AbstractMerkleInternal}
     * should ever call this method.
     *
     * @throws MerkleRouteException
     * 		if this node has a reference count is not exactly 1. Routes may only be changed
     * 		when a node is first added as the child of another node or if there is a single parent
     * 		and the route of that parent changes.
     */
    void setRoute(final MerkleRoute route);
}
