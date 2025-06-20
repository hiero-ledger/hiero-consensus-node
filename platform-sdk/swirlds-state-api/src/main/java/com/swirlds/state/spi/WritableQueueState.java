// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Predicate;

/**
 * A writable queue of elements.
 *
 * @param <E> The type of element held in the queue.
 */
public interface WritableQueueState<E> extends ReadableQueueState<E> {
    /**
     * Adds the given element to the end of the queue.
     *
     * @param element The element to add.
     */
    void add(@NonNull E element);

    /**
     * Retrieves and removes the element at the head of the queue, or returns null if the queue is empty.
     *
     * @return The element at the head of the queue, or null if the queue is empty.
     */
    @Nullable
    default E poll() {
        return removeIf(e -> true);
    }

    /**
     * Retrieves and removes the element at the head of the queue if, and only if, the predicate returns true when
     * executed with the head element.
     * @param predicate A function that returns true if the supplied element meets the criteria to be removed
     * @return The head of the queue, or null if the queue is empty or the predicate returns false.
     */
    @Nullable
    E removeIf(@NonNull Predicate<E> predicate);
}
