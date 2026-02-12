// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

public interface NodeTraversalOrder {

    /**
     * This method is called when the first node, which is always virtual root node, is received from
     * teacher along with information about virtual tree leaves range.
     *
     * @param firstLeafPath the first leaf path in teacher's virtual tree
     * @param lastLeafPath the last leaf path in teacher's virtual tree
     */
    void start(final long firstLeafPath, final long lastLeafPath);

    long getNextInternalPathToSend();

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
