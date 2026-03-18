// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;

/**
 * Describes methods used by the reconnect algorithm to interact with various types of merkle trees.
 *
 */
public interface TreeView extends AutoCloseable {

    /**
     * Check if a path is an internal path.
     *
     * @param path
     * 		the path in question
     * @param isOriginal
     * 		true if the path is from the original tree. This will always be {@code true}
     * 		when called for the teacher.
     * @return true if the path is internal
     */
    boolean isInternal(Long path, boolean isOriginal);

    /**
     * Get the child count of an internal node.
     *
     * @param path
     * 		the node in question
     * @return the child count of the node
     * @throws MerkleSynchronizationException
     * 		if the node in question is a leaf node
     */
    int getNumberOfChildren(Long path);

    /**
     * Called when reconnect has been completed and this view is no longer required to exist.
     */
    @Override
    default void close() {
        // override this method to perform required cleanup after a reconnect
    }
}
