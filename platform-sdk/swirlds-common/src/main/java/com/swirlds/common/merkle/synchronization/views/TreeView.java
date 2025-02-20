// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;

/**
 * Describes methods used by the reconnect algorithm to interact with various types of merkle trees.
 *
 * @param <T>
 * 		an object that represents a node in the tree
 */
public interface TreeView<T> extends AutoCloseable {

    /**
     * Check if a node is an internal node.
     *
     * @param node
     * 		the node in question
     * @param isOriginal
     * 		true if the node is from the original tree. This will always be {@code true}
     * 		when called for the teacher.
     * @return true if the node is internal
     */
    boolean isInternal(T node, boolean isOriginal);

    /**
     * Get the child count of an internal node.
     *
     * @param node
     * 		the node in question
     * @return the child count of the node
     * @throws MerkleSynchronizationException
     * 		if the node in question is a leaf node
     */
    int getNumberOfChildren(T node);

    /**
     * Get the class ID of a node.
     *
     * @param node
     * 		the node in question
     * @return the class ID of the node
     */
    long getClassId(T node);

    /**
     * Convert a root of a custom tree from abstract T form into a merkle node object.
     *
     * @param node
     * 		the node in question, guaranteed to only be called on the roots of trees that define custom reconnect views
     * @return the merkle node object
     */
    MerkleNode getMerkleRoot(T node);

    /**
     * Called when reconnect has been completed and this view is no longer required to exist.
     */
    @Override
    default void close() {
        // override this method to perform required cleanup after a reconnect
    }
}
