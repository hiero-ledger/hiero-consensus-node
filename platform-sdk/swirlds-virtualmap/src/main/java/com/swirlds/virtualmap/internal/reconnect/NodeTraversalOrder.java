// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;

public interface NodeTraversalOrder {

    /**
     * This method is called when the first node, which is always virtual root node, is received from
     * teacher along with information about virtual tree leaves range.
     *
     * @param firstLeafPath the first leaf path in teacher's virtual tree
     * @param lastLeafPath the last leaf path in teacher's virtual tree
     * @param nodeCount object to report node stats
     */
    void start(final long firstLeafPath, final long lastLeafPath, final ReconnectNodeCount nodeCount);

    /**
     * Called by the learner's sending thread to send the next path to teacher. If this method returns
     * {@link com.swirlds.virtualmap.internal.Path#INVALID_PATH}, it indicates there are no more paths
     * to send.
     *
     * @return the next virtual path to send to the teacher
     * @throws InterruptedException if the current thread is interrupted while backpressure waiting
     */
    long getNextPathToSend() throws InterruptedException;

    /**
     * Notifies this object that a node response is received from the teacher.
     *
     * @param path the received node path
     * @param isClean indicates if the node at the given path matches the corresponding node on the teacher
     */
    void nodeReceived(final long path, final boolean isClean);
}
