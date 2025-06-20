// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import com.swirlds.merkledb.files.DataFileCommon;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.LongStream;

/**
 * A simple, random access list of <b>non-zero</b> longs designed to allow lock-free concurrency
 * control. Unlike a {@link java.util.List}, the size of a {@link LongList} can exceed {@code
 * Integer.MAX_VALUE}.
 *
 * <p>Zero is treated as a sentinel value, marking indexes that have never been used with a {@code
 * put()} call or indexes that no longer contain valid values after a call to {@link #remove}.
 *
 * <p>Implementations should support both concurrent reads and writes. Writing to an index beyond
 * the current capacity of the list (but less than the max capacity) should <b>not</b> fail, but
 * instead trigger an automatic expansion of the list's capacity. Thus a {@link LongList} behaves
 * more like a long-to-long map than a traditional list.
 *
 */
public interface LongList extends CASableLongIndex, Closeable {
    /**
     * A LongList may not contain the non-existent data location, which is used as a sentinel for a
     * never-set index.
     */
    long IMPERMISSIBLE_VALUE = DataFileCommon.NON_EXISTENT_DATA_LOCATION;

    /**
     * Read current long value at index
     * @param index position, key, etc.
     * @param defaultValue default value to return if index is not set
     * @return read value
     */
    long get(long index, long defaultValue);

    /**
     * Stores a long at the given index.
     *
     * @param index the index to use
     * @param value the long to store
     * @throws IndexOutOfBoundsException if the index is negative or beyond the max capacity of the list
     * @throws IllegalArgumentException  if the value is zero
     */
    void put(long index, long value);

    /**
     * Marks the index as not containing a valid long value.
     *
     * @param index the index to clear
     */
    void remove(long index);

    /**
     * Stores a long at the given index, on the condition that the current long therein has a given
     * value.
     *
     * @param index    the index to use
     * @param oldValue the value that must currently obtain at the index
     * @param newValue the new value to store
     * @return whether the newValue was set
     * @throws IndexOutOfBoundsException if the index is negative or beyond the max capacity of the list
     * @throws IllegalArgumentException  if old value is zero (which could never be true)
     */
    @Override
    boolean putIfEqual(long index, long oldValue, long newValue);

    /**
     * Get the maximum capacity of this LongList; that is, one greater than the maximum legal value
     * of an {@code index} parameter used in a {@code put()} call.
     */
    long capacity();

    /**
     * Get the maximum number of indices in this LongList that may be non-zero. (That is, one more
     * than the largest {@code index} used in a call to {@code put()}.
     * <p> This value is eventually consistent with {@code maxValidIndex} returned
     * provided in {@link LongList#updateValidRange}. That is, once {@link LongList#updateValidRange} call is complete
     * the value returned by this method will be not more than {@code maxValidIndex} provided in the call.
     * If {@code maxValidIndex} is greater than the current size, the size remains unchanged.
     * <p>Bounded above by {@link AbstractLongList#capacity()}.
     */
    long size();

    /**
     * Create a stream over the data in this LongList. This is designed for testing and may be
     * inconsistent under current modifications.
     */
    LongStream stream();

    /**
     * Write all longs in this LongList into a file
     * <p>
     * <b> It is not guaranteed what version of data will be written if the LongList is changed
     * via put methods while this LongList is being written to a file. If you need consistency while
     * calling put concurrently then use a BufferedLongListWrapper. </b>
     *
     * @param file The file to write into, it should not exist but its parent directory should exist
     *             and be writable.
     * @throws IOException If there was a problem creating or writing to the file.
     */
    void writeToFile(Path file) throws IOException;

    /**
     * Updates min and max valid indexes in this list. If both values are -1, this indicates
     * the list is empty.
     *
     * <p>After invocation of this method, {@link LongList#get(long)}) calls
     * will return {@link LongList#IMPERMISSIBLE_VALUE} for indices that
     * are before {@code newMinValidIndex} and after {@code newMaxValidIndex}
     * Also, a call to this method releases memory taken by unused chunks.
     * For in-memory implementation it means the chunk clean up and memory release,
     * while file-based reuse the file space in further writes.
     *
     * <p>Note that {@code newMinValidIndex} is allowed to exceed the current size of the list.
     * If {@code newMaxValidIndex} exceeds the current size of the list, there will be no effect.
     *
     * @param newMinValidIndex minimal valid index of the list
     * @param newMaxValidIndex maximal valid index of the list
     * @throws IndexOutOfBoundsException if {@code newMinValidIndex} is negative or
     * {@code newMaxValidIndex} exceeds max number of chunks allowed.
     */
    void updateValidRange(long newMinValidIndex, long newMaxValidIndex);

    /**
     * Min valid index in this list. If the list is empty, the min index is -1.
     *
     * @return min valid index
     */
    long getMinValidIndex();

    /**
     * Max valid index in this list. If the list is empty, the max index is -1;
     *
     * @return max valid index
     */
    long getMaxValidIndex();

    /** {@inheritDoc} */
    @Override
    void close();
}
