// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

/**
 * A wrapper around an arbitrary object that is auto closeable.
 *
 * @param <T>
 * 		the type of the wrapped object
 */
public class AutoCloseableWrapper<T> implements AutoCloseable {
    private static final AutoCloseableWrapper<?> EMPTY = new AutoCloseableWrapper<>(null, () -> {});
    private final T object;
    private final Runnable closeCallback;

    /**
     * Create a new AutoCloseable wrapper.
     *
     * @param object
     * 		The object that is being wrapped.
     * @param closeCallback
     * 		The function that is called when the wrapper is closed.
     */
    public AutoCloseableWrapper(T object, Runnable closeCallback) {
        this.object = object;
        this.closeCallback = closeCallback;
    }

    /**
     * Get the wrapped object.
     *
     * @return the wrapped object
     */
    public T get() {
        return object;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closeCallback.run();
    }

    /**
     * Get an empty wrapper.
     *
     * @return an empty wrapper
     */
    @SuppressWarnings("unchecked")
    public static <T> AutoCloseableWrapper<T> empty() {
        return (AutoCloseableWrapper<T>) EMPTY;
    }
}
