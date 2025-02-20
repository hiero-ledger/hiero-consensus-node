// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.pipeline;

import java.util.function.Predicate;

/**
 * <p>
 * A simple linked list implementation that exposes the actual links to the user. Adding elements and removing elements
 * are thread safe with respect to iteration. Adding and removing elements are not thread safe with respect to other
 * add/remove operations.
 * </p>
 *
 * <p>
 * This class was specially designed to meet an algorithmic need by {@link VirtualPipeline}. This class needs a list
 * of elements with the following properties:
 * </p>
 *
 * <ul>
 * <li>
 * forward/backward movement during traversal
 * </li>
 * <li>
 * the ability to remove an element in the middle of the list in O(1) time during traversal
 * </li>
 * <li>
 * thread safety between modification and traversal
 * </li>
 * </ul>
 *
 * @param <T>
 * 		the type of the thing in the list
 */
class PipelineList<T> {

    private PipelineListNode<T> first;
    private PipelineListNode<T> last;

    private int size;

    PipelineList() {}

    /**
     * Get the first node in the list.
     *
     * @return the first node
     */
    synchronized PipelineListNode<T> getFirst() {
        return first;
    }

    /**
     * Add a value onto the end of the list.
     *
     * @param value
     * 		the value to add
     */
    synchronized void add(final T value) {
        final PipelineListNode<T> node = new PipelineListNode<>(value);

        if (first == null) {
            first = node;
        } else {
            last.addNext(node);
        }
        last = node;

        size++;
    }

    /**
     * Remove a node from the list.
     *
     * @param node
     * 		the node to remove
     */
    synchronized void remove(final PipelineListNode<T> node) {
        if (first == node) {
            first = node.getNext();
        }
        if (last == node) {
            last = node.getPrevious();
        }
        node.remove();

        size--;
    }

    /**
     * Check each value in the list with a predicate. Return true iff each value tested
     * causes the predicate to return true. Also returns true if list is empty.
     * Performed while the data structure is locked.
     *
     * @param predicate
     * 		the test to run on each value
     * @return true if the predicate returns true for each value in the list
     */
    synchronized boolean testAll(final Predicate<T> predicate) {
        PipelineListNode<T> target = first;
        while (target != null) {

            if (!predicate.test(target.getValue())) {
                return false;
            }

            target = target.getNext();
        }
        return true;
    }

    /**
     * Get the current size of this list.
     *
     * @return the current size
     */
    synchronized int getSize() {
        return size;
    }
}
