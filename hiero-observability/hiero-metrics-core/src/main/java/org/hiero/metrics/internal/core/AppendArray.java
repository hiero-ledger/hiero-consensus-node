// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.metrics.api.core.ArrayAccessor;

/**
 * Thread-safe append-only array allowing concurrent writes with single-threaded reads.
 * This class is not a general purpose collection, it is designed for specific use case of collecting metrics
 * from multiple threads and then processing them from a single exporter thread.
 * <p>
 * Usage pattern:
 * <ul>
 *   <li>Multiple threads can call {@code add()} concurrently</li>
 *   <li>Call {@code readyToRead()} before reading from single thread to get fixed size of ready to rad items</li>
 *   <li>Use {@code size()} and {@code getItem()} for reading from the same thread called {@code readyToRead()}</li>
 * </ul>
 * <p>
 * {@link #readyToRead} must used to fix size of ready to read items.
 */
public class AppendArray<T> implements ArrayAccessor<T> {

    private T[] items;
    private final AtomicInteger size = new AtomicInteger();

    private T[] readItemsRef;
    private int readSize;

    /**
     * Create an append-only array with the given initial capacity.
     *
     * @param capacity initial capacity
     */
    @SuppressWarnings("unchecked")
    public AppendArray(int capacity) {
        this.items = (T[]) new Object[capacity];
        readItemsRef = items;
        readSize = 0;
    }

    /**
     * Add a value to the array.
     * This method is thread-safe and can be called concurrently from multiple threads.
     *
     * @param value the value to add
     */
    @SuppressWarnings("unchecked")
    public void add(T value) {
        int idx = size.getAndIncrement();

        if (idx >= items.length) {
            synchronized (this) {
                if (idx >= items.length) {
                    T[] newArray = (T[]) new Object[size.get() * 2];
                    System.arraycopy(items, 0, newArray, 0, items.length);
                    items = newArray;
                }
            }
        }
        items[idx] = value;
    }

    /**
     * Prepare the array for reading. Fixes the size of items ready to read.
     *
     * @return the number of items ready to read
     */
    public synchronized int readyToRead() {
        // fix readSize
        readSize = size.get();
        // assign to a local since reference can be changed by add() method
        readItemsRef = this.items;
        return readSize;
    }

    /**
     * @return the number of items ready to read (after last call to {@link #readyToRead()})
     */
    @Override
    public int size() {
        return readSize;
    }

    /**
     * Get the item at the given index.
     * Index should be in range [0, size()) where size is the value returned by last call to {@link #readyToRead()}.
     *
     * @param index the index of the item to get
     * @return the item at the given index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     */
    @NonNull
    @Override
    public T get(int index) {
        if (index >= readSize) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + readSize);
        }
        return readItemsRef[index];
    }
}
