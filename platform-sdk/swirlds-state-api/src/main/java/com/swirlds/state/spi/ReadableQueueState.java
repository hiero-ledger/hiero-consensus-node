// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.hedera.pbj.runtime.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * A readable queue of elements.
 *
 * @param <E> The type of element held in the queue.
 */
public interface ReadableQueueState<E> {
    /**
     * Gets the "state key" that uniquely identifies this {@link ReadableQueueState} within the {@link
     * Schema} which are scoped to the service implementation. The key is therefore not globally
     * unique, only unique within the service implementation itself.
     *
     * <p>The call is idempotent, always returning the same value. It must never return null.
     *
     * @return The state key. This will never be null, and will always be the same value for an
     *     instance of {@link ReadableQueueState}.
     */
    @NonNull
    String getStateKey();

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
