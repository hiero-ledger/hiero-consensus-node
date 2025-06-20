// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.set;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * This iterator walks over elements in a {@link HotspotHashSet}.
 *
 * @param <T>
 * 		the type of element in the set
 */
public class HotspotSetIterator<T> implements Iterator<T> {

    private final List<RandomAccessHashSet<T>> sets;
    private Iterator<T> setIterator;
    private int nextIndex;

    public HotspotSetIterator(final List<RandomAccessHashSet<T>> sets) {
        this.sets = sets;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        while (setIterator == null || !setIterator.hasNext()) {
            if (nextIndex >= sets.size()) {
                return false;
            }
            setIterator = sets.get(nextIndex).iterator();
            nextIndex++;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        return setIterator.next();
    }
}
