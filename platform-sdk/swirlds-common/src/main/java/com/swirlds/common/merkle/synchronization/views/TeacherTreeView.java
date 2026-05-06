// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A "view" into a merkle tree (or subtree) used to perform a reconnect operation. This view is used to access
 * the tree by the teacher.
 */
public interface TeacherTreeView extends AutoCloseable {

    /**
     * Perform a synchronous root-node (path 0) request/response handshake, then start all
     * required parallel reconnect tasks in the given work group. The root exchange must complete
     * before any worker tasks are forked, so the teacher processes the root request and sends the
     * root response (including the teacher's first/last leaf path range) before the learner begins
     * sending non-root node requests. The teaching synchronizer will wait for all tasks in the
     * work group to complete before proceeding.
     *
     * @param time the wall clock time
     * @param workGroup the work group to run teaching task(s) in
     * @param in the input stream to read data from learner
     * @param out the output stream to write data to learner
     */
    void startTeacherTasks(
            final Time time, final StandardWorkGroup workGroup, final AsyncInputStream in, final AsyncOutputStream out);
}
