// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

/**
 * This interface contains methods that are used by the teacher during reconnect to track the responses provided by
 * the learner, and to build a working theory of the learner's knowledge.
 *
 * @param <T>
 * 		the type of the object used by the view to represent a merkle node
 */
public interface TeacherResponseTracker<T> {

    /**
     * Register the result of the learner's response to a query
     *
     * @param node
     * 		the node in question
     * @param learnerHasNode
     * 		true if the learner has the node, otherwise false
     */
    void registerResponseForNode(T node, boolean learnerHasNode);

    /**
     * Check if the learner has confirmed that it has a given node. Should return false if the learner has responded
     * with "no" or if the learner has not yet responded.
     *
     * @param node
     * 		the node in question
     * @return true if a message has been received showing that the learner already has the node
     */
    boolean hasLearnerConfirmedFor(T node);
}
