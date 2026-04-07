// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the learner.
 *
 */
public interface LearnerTreeView extends AutoCloseable {

    /**
     * For this tree view, start all required reconnect tasks in the given work group. Learning synchronizer
     * will then wait for all tasks in the work group to complete before proceeding to the next tree view. If
     * new custom tree views are encountered, they must be added to {@code rootsToReceive}, although it isn't
     * currently supported by virtual tree views, as nested virtual maps are not supported.
     *
     * @param workGroup the work group to run teaching task(s) in
     * @param in the input stream to read data from teacher
     * @param out the output stream to write data to teacher
     */
    void startLearnerTasks(final StandardWorkGroup workGroup, final AsyncInputStream in, final AsyncOutputStream out);

    /**
     * Get the hash of a node. If this view represents a tree that has null nodes within it, those nodes should cause
     * this method to return a {@link Cryptography#NULL_HASH null hash}.
     *
     * @param path
     * 		the node path
     * @return the hash of the node
     */
    Hash getNodeHash(Long path);
}
