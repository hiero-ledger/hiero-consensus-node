// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;

/**
 * A fixed number of items in a ring. Advancing turns the oldest item into the newest one, for the caller to overwrite,
 * so the items are reused rather than replaced.
 *
 * <p>This class is not thread-safe.
 *
 * @param <T> the type of the items
 */
final class RingBuffer<T> {

    /** The items, in the order of the ring. */
    private final T[] items;

    /** The index of the oldest item. */
    private int oldest;

    /**
     * Creates a ring of items.
     *
     * @param size    the number of items, at least 1
     * @param factory creates each item
     * @throws IllegalArgumentException if the size is less than 1
     */
    @SuppressWarnings("unchecked")
    RingBuffer(final int size, @NonNull final Supplier<T> factory) {
        if (size < 1) {
            throw new IllegalArgumentException("The size must be at least 1, but is " + size);
        }
        // the array never leaves this class, so its runtime type Object[] is never noticed
        this.items = (T[]) new Object[size];
        for (int i = 0; i < size; i++) {
            items[i] = factory.get();
        }
    }

    /**
     * Moves on by one item and returns the item that is now the newest. It used to be the oldest.
     *
     * @return the newest item, for the caller to overwrite
     */
    @NonNull
    T advance() {
        final T newest = items[oldest];
        oldest = (oldest + 1) % items.length;
        return newest;
    }

    /**
     * Returns the oldest item, which the next {@link #advance()} returns. Until the ring has gone round once, it is an
     * item as the factory created it.
     *
     * @return the oldest item
     */
    @NonNull
    T oldest() {
        return items[oldest];
    }
}
