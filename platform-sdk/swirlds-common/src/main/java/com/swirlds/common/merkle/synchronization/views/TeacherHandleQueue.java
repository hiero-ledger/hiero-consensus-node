// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

/**
 * Describes methods that implement a queue of nodes to be handled by the teacher during reconnect. Nodes are placed
 * into this queue when they are sent in a query, and handled when it is time to send the lesson for the node.
 *
 * @param <T>
 * 		a type that represents a merkle node in the view
 */
public interface TeacherHandleQueue<T> {

    /**
     * <p>
     * Add the node to a queue so that it can be later handled. Nodes added to this queue should be
     * returned by {@link #getNextNodeToHandle()}. Queue does not need to be thread safe.
     * </p>
     *
     * <p>
     * May not be called on all nodes. For the nodes it is called on, the order will be consistent with
     * a breadth first ordering.
     * </p>
     *
     * @param node
     * 		the node to add to the queue
     */
    void addToHandleQueue(T node);

    /**
     * Remove and return the next node from the queue built by {@link #addToHandleQueue(Object)}.
     *
     * @return the next node to handle
     */
    T getNextNodeToHandle();

    /**
     * Check if there is anything in the queue built by {@link #addToHandleQueue(Object)}.
     *
     * @return true if there are nodes in the queue
     */
    boolean areThereNodesToHandle();
}
