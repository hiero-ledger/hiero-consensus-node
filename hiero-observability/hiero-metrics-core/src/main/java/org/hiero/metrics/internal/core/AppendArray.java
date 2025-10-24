// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.hiero.metrics.api.core.ArrayAccessor;

/**
 * Thread-safe append-only array allowing concurrent writes with single-threaded reads.
 * This class is not a general purpose collection, it is designed for specific use case of collecting metrics
 * from multiple threads and then processing them from a single exporter thread.
 * <p>
 * Usage pattern:
 * <ul>
 *   <li>Multiple threads can call {@code add()} concurrently</li>
 *   <li>Call {@code readyToRead()} before reading from single thread</li>
 *   <li>Use {@code size()} and {@code getItem()} for reading from the same thread called {@code readyToRead()}</li>
 * </ul>
 * <p>
 * {@link #readyToRead(Consumer)} can be used to update ready to read items with provided consumer.
 */
public class AppendArray<T> implements ArrayAccessor<T> {

    private T[] items;
    private final AtomicInteger size = new AtomicInteger();

    private T[] readItemsRef;
    private int readSize;

    @SuppressWarnings("unchecked")
    public AppendArray(int capacity) {
        this.items = (T[]) new Object[capacity];
    }

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

    public void readyToRead(@NonNull Consumer<T> updater) {
        // capture atomically size and array reference
        synchronized (this) {
            // fix readSize
            readSize = size.get();
            // assign to a local since reference can be changed by add() method
            readItemsRef = this.items;
        }

        for (int i = 0; i < readSize; i++) {
            updater.accept(readItemsRef[i]);
        }
    }

    @Override
    public int size() {
        return readSize;
    }

    @NonNull
    @Override
    public T get(int index) {
        if (index >= readSize) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + readSize);
        }
        return readItemsRef[index];
    }
}
