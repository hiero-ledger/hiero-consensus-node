// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An iterator that doesn't return anything. A convenient utility object.
 *
 * @param <T>
 * 		the type "returned" by the iterator
 */
public class EmptyIterator<T> implements Iterator<T> {

    public EmptyIterator() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        throw new NoSuchElementException();
    }
}
