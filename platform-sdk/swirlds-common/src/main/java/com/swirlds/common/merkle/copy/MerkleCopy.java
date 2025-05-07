// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.copy;

import static com.swirlds.common.merkle.copy.MerkleInitialize.initializeTreeAfterCopy;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.MerkleCopyException;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.util.LinkedList;
import java.util.Queue;
import org.hiero.base.constructable.ConstructableRegistry;

/**
 * A collection of utility methods for performing copies of a merkle tree.
 */
public final class MerkleCopy {

    private MerkleCopy() {}

    /**
     * Makes a fast copy if the provided node is a leaf, otherwise constructs a pseudo fast copy using
     * the constructable registry. This method will no longer be necessary once internal merkle nodes
     * become fast copyable.
     *
     * This method does not initialize the copy of the node, external callers are responsible for this process.
     */
    public static MerkleNode copyAnyNodeType(final MerkleNode node) {
        if (node == null) {
            return null;
        } else if (node.isLeaf()) {
            return node.copy();
        } else {
            final MerkleNode copy = ConstructableRegistry.getInstance().createObject(node.getClassId());
            if (copy == null) {
                throw new MerkleCopyException(String.format(
                        "Unable to construct object with class ID %d(0x%08X): %s",
                        node.getClassId(), node.getClassId(), node.getClass().getName()));
            }
            return copy;
        }
    }

    /**
     * Helper function for copyTreeToLocation. Adds all children of a merkle node to the queue.
     * If the parent is actually null or a leaf then take no action.
     *
     * @param oldParent
     * 		the children of this node (if it has children) are added to the queue
     * @param copiedParent
     * 		this is a copy of oldParent. All children will eventually be copied and added as children to this node.
     * @param queue
     * 		the queue that holds nodes that still need to be copied
     */
    private static void addChildrenToQueue(
            final NodeToCopy parentNodeCopyInfo,
            final MerkleNode oldParent,
            final MerkleNode copiedParent,
            final Queue<NodeToCopy> queue) {
        if (oldParent == null || oldParent.isLeaf()) {
            return;
        }
        final MerkleInternal internal = oldParent.cast();
        for (int childIndex = 0; childIndex < internal.getNumberOfChildren(); childIndex++) {
            queue.add(new NodeToCopy(
                    copiedParent.asInternal(),
                    childIndex,
                    internal.getChild(childIndex),
                    parentNodeCopyInfo.getOriginalNodeInSamePositionOfChild(childIndex)));
        }
    }

    /**
     * <p>
     * Copy each node in a subtree to a given location.
     * </p>
     *
     * <p>
     * This algorithm does not work on data structures that do not support being copied node-by node
     * (e.g. virtual data structures).
     * </p>
     *
     * <p>
     * Warning: This method may leave behind a partially immutable tree in the original location. It is the
     * responsibility of the caller to appropriately clean up the original tree.
     * </p>
     *
     * @param parent
     * 		the destination parent where that will hold the subtree
     * @param index
     * 		the destination index where the subtree will sit
     * @param child
     * 		the subtree being moved
     * @return the root of the copied tree
     */
    @SuppressWarnings("unchecked")
    public static <T extends MerkleNode> T copyTreeToLocation(
            final MerkleInternal parent, final int index, final T child) {

        // Hold reference to tree being copied. This prevents it from being released if we are copying to
        // an ancestor that causes the original subtree to be de-referenced.
        if (child != null) {
            child.reserve();
        }

        final Queue<NodeToCopy> nodesToCopy = new LinkedList<>();
        T rootOfSubtree = null;

        // Add the root to the queue
        MerkleNode originalNodeInSubtreeRootPosition = null;
        if (parent.getNumberOfChildren() > index) {
            originalNodeInSubtreeRootPosition = parent.getChild(index);
        }
        nodesToCopy.add(new NodeToCopy(parent, index, child, originalNodeInSubtreeRootPosition));

        while (!nodesToCopy.isEmpty()) {
            final NodeToCopy nodeToCopy = nodesToCopy.remove();
            final MerkleNode original = nodeToCopy.getNodeToCopy();
            final MerkleNode copy = copyAnyNodeType(original);
            addChildrenToQueue(nodeToCopy, original, copy, nodesToCopy);
            if (rootOfSubtree == null) {
                rootOfSubtree = (T) copy;
            }
            nodeToCopy
                    .getNewParent()
                    .setChild(nodeToCopy.getIndexInParent(), copy, nodeToCopy.getRouteForNode(), false);
        }

        // Since we have obtained new instances of internal nodes without calling copy on them we must initialize them
        if (rootOfSubtree != null) {
            initializeTreeAfterCopy(rootOfSubtree);
        }

        // Release hold on original tree.
        if (child != null) {
            child.release();
        }

        return rootOfSubtree;
    }

    /**
     * <p>
     * Take the children from an internal node and make them also the children of another. Does not fast copy anything,
     * each child will have an additional direct parent after this method returns. Assumes that two nodes are in the
     * exact same position within the tree.
     * </p>
     *
     * <p>
     * This method is safe to use on both mutable and immutable children.
     * </p>
     *
     * @param originalParent
     * 		the node that is providing children
     * @param newParent
     * 		the node that is getting the children
     */
    public static void adoptChildren(final MerkleInternal originalParent, final MerkleInternal newParent) {
        for (int childIndex = 0; childIndex < originalParent.getNumberOfChildren(); childIndex++) {
            final MerkleNode child = originalParent.getChild(childIndex);
            MerkleRoute childRoute = null;
            if (child != null) {
                childRoute = child.getRoute();
            }
            newParent.setChild(childIndex, child, childRoute, true);
        }
    }
}
