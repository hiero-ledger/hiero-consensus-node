// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

/**
 * Describes methods that are used to implement a queue of expected responses for the teacher.
 *
 * @param <T>
 * 		the type of the object used to represent a merkle node in the view
 */
public interface TeacherResponseQueue<T> {

    /**
     * <p>
     * Fetch a node that will be used for a query. The hash of this node will be sent to the learner. Calling
     * this method signifies that a response is eventually expected from the learner.
     * </p>
     *
     * <p>
     * Responses do not contain information which identifies which node the response is for. But the order
     * of responses is the same as the order of the queries, and so by knowing the order the associated node
     * can be determined.
     * </p>
     *
     * <p>
     * This method is expected to add the node to a thread safe queue (single writer, single reader).
     * {@link #getNodeForNextResponse()} removes elements from the front of the queue. Using this queue,
     * the reconnect algorithm determines which node a response is associated with.
     * </p>
     *
     * <p>
     * Will be called at most once for any particular child of a parent.
     * </p>
     *
     * @param parent
     * 		the parent
     * @param childIndex
     * 		the index of the child
     * @return the child
     */
    T getChildAndPrepareForQueryResponse(T parent, int childIndex);

    /**
     * Remove and return an element from the queue built by {@link #getChildAndPrepareForQueryResponse(Object, int)}.
     *
     * @return the node to associate the next response with
     * @throws java.util.NoSuchElementException
     * 		if {@link #isResponseExpected()} currently returns false
     */
    T getNodeForNextResponse();

    /**
     * Is a response to a query expected? Should return true if the queue implementing
     * {@link #getChildAndPrepareForQueryResponse(Object, int)} is not empty.
     *
     * @return true if at least one response is still expected
     */
    boolean isResponseExpected();
}
