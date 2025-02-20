// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.copy;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;

/**
 * A utility class used by copy methods. Keeps track of a node that needs to be copied.
 */
class NodeToCopy {

    private final MerkleInternal newParent;
    private final int indexInParent;
    private final MerkleNode nodeToCopy;
    private final MerkleNode originalNodeInSamePosition;

    /**
     * @param newParent
     * 		the parent into which the node should be inserted when copied
     * @param indexInParent
     * 		the index at which the copy should be inserted
     * @param nodeToCopy
     * 		the node that should be copied
     * @param originalNodeInSamePosition
     * 		The node that was previously replaced by new parent, may be
     * 		null if there is no node originally in that position.
     */
    public NodeToCopy(
            final MerkleInternal newParent,
            final int indexInParent,
            final MerkleNode nodeToCopy,
            final MerkleNode originalNodeInSamePosition) {
        this.newParent = newParent;
        this.indexInParent = indexInParent;
        this.nodeToCopy = nodeToCopy;
        this.originalNodeInSamePosition = originalNodeInSamePosition;
    }

    /**
     * Get the route where the node is being placed if it is known. Otherwise return null.
     * Route is recycled from node that originally occupied this location in the tree.
     */
    public MerkleRoute getRouteForNode() {
        if (originalNodeInSamePosition == null) {
            return null;
        } else {
            return originalNodeInSamePosition.getRoute();
        }
    }

    /**
     * For a given child index within nodeToCopy, get the node that was originally in the same position as that child
     * in the original tree. Useful for recycling routes.
     */
    public MerkleNode getOriginalNodeInSamePositionOfChild(final int childIndex) {
        if (originalNodeInSamePosition == null
                || originalNodeInSamePosition.isLeaf()
                || originalNodeInSamePosition.asInternal().getNumberOfChildren() <= childIndex) {
            return null;
        }

        return originalNodeInSamePosition.asInternal().getChild(childIndex);
    }

    /**
     * The parent that will hold the node after it has been copied.
     */
    public MerkleInternal getNewParent() {
        return newParent;
    }

    /**
     * The index within the parent where the copied node will sit.
     */
    public int getIndexInParent() {
        return indexInParent;
    }

    /**
     * The node that needs to be copied.
     */
    public MerkleNode getNodeToCopy() {
        return nodeToCopy;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("parent", newParent)
                .append("index", indexInParent)
                .append("node to copy", nodeToCopy)
                .toString();
    }
}
