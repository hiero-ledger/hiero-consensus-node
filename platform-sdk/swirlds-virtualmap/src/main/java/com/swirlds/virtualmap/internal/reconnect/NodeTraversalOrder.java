// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.swirlds.virtualmap.internal.Path;

public interface NodeTraversalOrder {

    /**
     * This method is called when the first node, which is always the root node, is received from
     * the teacher along with information about virtual tree leaf path range.
     *
     * @param firstLeafPath the first leaf path in teacher's virtual tree
     * @param lastLeafPath the last leaf path in teacher's virtual tree
     */
    void start(final long firstLeafPath, final long lastLeafPath);

    /**
     * Returns the next internal path to send to the teacher. This method may be called in
     * parallel on multiple threads.
     *
     * <p>This method may return {@link Path#INVALID_PATH}, to indicate that there is no
     * internal node to send at the moment. The learner may decide to proceed with sending
     * leaves and / or call this method again later.
     *
     * @return The next internal path to send to the teacher, or {@link Path#INVALID_PATH} if
     *          no internal path to send is known at the moment
     */
    long getNextInternalPathToSend();

    /**
     * Returns the next leaf path to send to the teacher. This method may be called on
     * multiple threads, but not simultaneously.
     *
     * <p>Once this method returns {@link Path#INVALID_PATH}, it indicates no more requests
     * will be sent to the teacher. All requests currently in flught will be processed by the
     * receiving threads, and reconnects will complete.
     *
     * @return The next leaf path to send to the teacher, or {@link Path#INVALID_PATH} to indicate
     *          there are no more nodes to synchronize from the teacher
     */
    long getNextLeafPathToSend();

    /**
     * Notifies this object that a node response is received from the teacher. This method may be called
     * concurrently from multiple threads, except for root response (path == 0), which is always received
     * first.
     *
     * @param path the received node path
     * @param isClean indicates if the node at the given path matches the corresponding node on the teacher
     */
    void nodeReceived(final long path, final boolean isClean);
}
