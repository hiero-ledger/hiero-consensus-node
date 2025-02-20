// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.common.merkle.synchronization.task.ExpectedLesson;

/**
 * Used by the learner during reconnect. These methods implement a queue like interface for expected lessons.
 *
 * @param <T>
 * 		the type of the object used to represent a merkle node within this view type
 */
public interface LearnerExpectedLessonQueue<T> {

    /**
     * <p>
     * When the teacher sends a hash (i.e. a query), it will always eventually follow up with a lesson.
     * </p>
     *
     * <p>
     * A lesson does not contain identifying information that associates it with a given node. Instead, the order
     * of the lessons matches the order of the queries, and so by using that order the correct association can be made.
     * </p>
     *
     * <p>
     * This method should add data to a queue which {@link #getNextExpectedLesson()} removes and returns from.
     * </p>
     *
     * <p>
     * If the learner already has the node and the teacher receives the response in time then the lesson will
     * just be a placeholder. This is to maintain the required ordering.
     * </p>
     *
     * <p>
     * A single thread will be reading and writing to/from this queue, so no thread safety is required.
     * </p>
     *
     * @param parent
     * 		the parent of the node in question
     * @param childIndex
     * 		the position where the node is found
     * @param original
     * 		the node originally at the specified position
     * @param nodeAlreadyPresent
     * 		true if we already have a node with the teacher's hash
     */
    void expectLessonFor(T parent, int childIndex, T original, boolean nodeAlreadyPresent);

    /**
     * Remove and return an element from the queue maintained by {@link #expectLessonFor(Object, int, Object, boolean)}.
     *
     * @return the next thing in the queue
     */
    ExpectedLesson<T> getNextExpectedLesson();

    /**
     * Check if the queue with expected lessons has a next element.
     *
     * @return true if the queue built by {@link #expectLessonFor(Object, int, Object, boolean)} is not empty
     */
    boolean hasNextExpectedLesson();
}
