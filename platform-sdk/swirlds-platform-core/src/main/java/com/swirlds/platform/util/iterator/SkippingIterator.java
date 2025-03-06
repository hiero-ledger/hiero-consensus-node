// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util.iterator;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * An array iterator that skips certain indices.
 *
 * @param <T>
 * 		the type being iterated
 */
public class SkippingIterator<T> implements Iterator<T> {

    private int cursor;
    private final T[] array;
    private final Set<Integer> skipIndices;
    private int lastReturnableIndex;

    /**
     * Creates a new instance. Mutations to {@code skipIndices} do not affect this instance.
     *
     * @param array
     * 		the array to iterate over
     * @param skipIndices
     * 		the zero based indices to skip over
     * @throws NullPointerException in case {@code array} parameter is {@code null}
     */
    public SkippingIterator(final T[] array, final Set<Integer> skipIndices) {
        Objects.requireNonNull(array, "array must not be null");

        this.array = array;
        this.skipIndices = skipIndices == null ? Collections.emptySet() : Set.copyOf(skipIndices);

        lastReturnableIndex = -1;
        for (int i = array.length - 1; i >= 0; i--) {
            if (!this.skipIndices.contains(i)) {
                lastReturnableIndex = i;
                break;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return cursor < array.length && cursor <= lastReturnableIndex;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        while (skipIndices.contains(cursor)) {
            cursor++;
        }
        final int i = cursor;
        if (i > lastReturnableIndex) {
            throw new NoSuchElementException();
        }
        cursor = i + 1;
        return array[i];
    }
}
