// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io;

import com.swirlds.base.utility.AutoCloseableNonThrowing;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Similar to an {@link Iterator} but throws IO exceptions.
 *
 * @param <T>
 * 		the type that is being iterated over
 */
public interface IOIterator<T> extends AutoCloseableNonThrowing {

    /**
     * Check if there is another object available. If this method returns true then {@link #next()} will
     * return a valid object without throwing. If this method returns false then {@link #next()} will throw
     * a {@link NoSuchElementException}.
     *
     * @return true if there is another object available
     * @throws IOException
     * 		if there is a problem reading the next object
     */
    boolean hasNext() throws IOException;

    /**
     * Get the next object if there is one available.
     *
     * @return the next object
     * @throws IOException
     * 		if there is a problem reading the next object
     * @throws NoSuchElementException
     * 		if {@link #hasNext()} returns false and this method is called
     */
    T next() throws IOException;

    /**
     * Get the next item without advancing the iterator. Optional method.
     *
     * @return the object that will be returned when {@link #next()} is called
     * @throws IOException
     * 		if there is a problem reading the next object
     * @throws NoSuchElementException
     * 		if {@link #hasNext()} returns false and this method is called
     */
    default T peek() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * For each remaining object in the iterator, pass that object to a lambda method.
     *
     * @param action
     * 		the action that will operate on each object still in the iterator
     * @throws IOException
     * 		if there is a problem reading an object
     */
    default void forEachRemaining(Consumer<? super T> action) throws IOException {
        while (hasNext()) {
            action.accept(next());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default void close() {
        // Override if needed
    }

    /**
     * Transform this iterator into an iterator that returns a different type. Iterating
     * over the returned iterator causes the parent iterator to also advance.
     *
     * @param converter
     * 		a function that converts from the old type into the new type.
     * @param <U>
     * 		the type returned by the new iterator
     * @return an iterator that walks the same data but returns a different type
     */
    default <U> IOIterator<U> transform(final Function<T, U> converter) {

        final IOIterator<T> parentIterator = this;

        return new IOIterator<>() {
            private U next;

            @Override
            public boolean hasNext() throws IOException {
                if (next != null) {
                    return true;
                }
                if (!parentIterator.hasNext()) {
                    return false;
                }
                next = converter.apply(parentIterator.next());
                return true;
            }

            @Override
            public U peek() throws IOException {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return next;
            }

            @Override
            public U next() throws IOException {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                try {
                    return next;
                } finally {
                    next = null;
                }
            }
        };
    }
}
