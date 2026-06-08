// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import com.swirlds.virtualmap.sync.streams.AsyncInputStream;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the learner.
 */
public interface LearnerTreeView {

    /**
     * Perform a synchronous root-node (path 0) request/response handshake, then start all
     * required parallel reconnect tasks in the given work group. The root exchange must complete
     * before any worker tasks are forked, because the root response provides the teacher's virtual
     * path range that all subsequent requests depend on. The learning synchronizer will wait for
     * all tasks in the work group to complete before proceeding.
     *
     * @param workGroup the work group to run learner task(s) in
     * @param in the input stream to read data from teacher
     * @param out the output stream to write data to teacher
     */
    void startLearnerTasks(final StandardWorkGroup workGroup, final AsyncInputStream in, final AsyncOutputStream out);

    /**
     * Called when all tasks for this tree view have completed successfully. This is used to trigger any necessary
     * finalization steps, such as committing a new state or updating the tree view to reflect changes that were made during the reconnect process.
     */
    void onSuccessfulComplete();
}
