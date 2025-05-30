// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * A readable queue of elements.
 *
 * @param <E> The type of element held in the queue.
 */
public interface ReadableQueueState<E> extends ReadableState {

    /**
     * Retrieves but does not remove the element at the head of the queue, or returns null if the queue is empty.
     *
     * @return The element at the head of the queue, or null if the queue is empty.
     */
    @Nullable
    E peek();

    /**
     * An iterator over all elements in the queue without removing any elements from the queue.
     * @return An iterator over all elements in the queue.
     */
    @NonNull
    Iterator<E> iterator();
}
