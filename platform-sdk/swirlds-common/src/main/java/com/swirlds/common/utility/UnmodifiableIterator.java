// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * This class can be used to wrap an iterator that is modifiable to make it unmodifiable.
 *
 * @param <T>
 * 		the type of the object that the iterator walks over
 */
public class UnmodifiableIterator<T> implements Iterator<T> {

    private final Iterator<T> baseIterator;

    public UnmodifiableIterator(@NonNull final Iterator<T> baseIterator) {
        Objects.requireNonNull(baseIterator, "baseIterator must not be null");
        this.baseIterator = baseIterator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return baseIterator.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        return baseIterator.next();
    }

    /**
     * Not supported.
     *
     * @throws UnsupportedOperationException
     * 		if called
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forEachRemaining(final Consumer<? super T> action) {
        baseIterator.forEachRemaining(action);
    }
}
