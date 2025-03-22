// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility;

import java.util.Iterator;
import java.util.Objects;

/**
 * A wrapper for an iterator that allows the exposed type to be a super type of the iterator provided.
 * <p>
 * Support for {@link #remove()} is the same as the iterator provided.
 *
 * @param <T>
 * 		the type to expose to callers
 */
public class TypedIterator<T> implements Iterator<T> {

    private final Iterator<? extends T> itr;

    /**
     * Creates a new instance.
     *
     * @param itr
     * 		the iterator to wrap
     * @throws NullPointerException in case {@code itr} parameter is {@code null}
     */
    public TypedIterator(final Iterator<? extends T> itr) {
        Objects.requireNonNull(itr, "itr must not be null");
        this.itr = itr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return itr.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        return itr.next();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove() {
        itr.remove();
    }
}
