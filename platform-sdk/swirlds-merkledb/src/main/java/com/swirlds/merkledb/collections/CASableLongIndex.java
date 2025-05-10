// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

/**
 * An interface for classes that provide long index functionality. Such long indices must
 * support Compare-And-Swap operations and iterations over all valid index entries.
 * Implementation can use atomic CAS or straightforward logic in single-threaded case.
 */
public interface CASableLongIndex {

    /**
     * Read current long value at index
     * @param index
     *      position, key, etc.
     * @return
     *      read value
     */
    long get(final long index);

    /**
     * Updates the element at index to newValue if the element's current value is equal to oldValue.
     * @param index
     *      position, key, etc.
     * @param oldValue
     *      expected value to be overwritten
     * @param newValue
     *      new value to replace the expected value
     * @return
     *      true if successful, false if the current value was not equal to the expected value
     */
    boolean putIfEqual(final long index, final long oldValue, final long newValue);

    /**
     * Iterates over all valid index entries and calls the specified action for each of them.
     *
     * @param action Action to call.
     * @param <T> Type of throwables allowed to throw by this method
     * @throws InterruptedException If the thread running the method is interrupted
     * @throws T If an error occurs
     */
    <T extends Throwable> void forEach(LongAction<T> action) throws InterruptedException, T;

    /**
     * Action interface to use in {@link #forEach(LongAction)}. It could be a standard Java API
     * interface like BiFunction, but all these APIs work with boxed Long type instead of
     * primitive longs, which adds unnecessary load on GC. So let's use something more simple
     *
     * @param <T> Type of throwable allowed to throw by the action
     */
    interface LongAction<T extends Throwable> {
        void handle(long index, long value) throws InterruptedException, T;
    }
}
