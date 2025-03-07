// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Provides auto-expanding storage for triples of longs, up to some maximum capacity. The triples
 * are stored in "chunks" of configurable size, and retrieved by using an integer index.
 *
 * Allocated chunks are never released; but the existing chunks may be re-used by calling
 * {@link ThreeLongsList#clear()} and then storing more triples starting again at index 0.
 *
 * <b>Important:</b> This class is not thread-safe, and by default has a capacity of only 4k triples (12k total longs).
 */
public class ThreeLongsList {
    private static final int LONGS_PER_TRIPLE = 3;
    private static final int SECOND_LONG_OFFSET = 1;
    private static final int THIRD_LONG_OFFSET = 2;

    public static final int SMALL_MAX_TRIPLES = 4_000;
    public static final int SMALL_TRIPLES_PER_CHUNK = 1_000;

    private final int maxTriples;
    private final int triplesPerChunk;
    private final List<long[]> data = new ArrayList<>();

    private int numChunks = 0;
    private int nextIndex = 0;

    /**
     * Construct a new ThreeLongsList with the default max and initial capacities.
     */
    public ThreeLongsList() {
        this(SMALL_MAX_TRIPLES, SMALL_TRIPLES_PER_CHUNK);
    }

    /**
     * Construct a new ThreeLongsList with the given max capacity and default
     * initial triples per chunk.
     *
     * @param maxTriples
     * 		the maximum number of triplets that can be stored
     */
    public ThreeLongsList(final int maxTriples) {
        this(maxTriples, Math.min(maxTriples, SMALL_TRIPLES_PER_CHUNK));
    }

    /**
     * Construct a new ThreeLongsList with the given max capacity and triples per chunk.
     *
     * @param maxTriples
     * 		the maximum number of triplets that can be stored
     * @throws IllegalArgumentException
     * 		if triplesPerChunk is non-positive or greater than maxTriples
     */
    public ThreeLongsList(final int maxTriples, final int triplesPerChunk) {
        if (triplesPerChunk < 1 || maxTriples < triplesPerChunk) {
            throw new IllegalArgumentException("Cannot construct ThreeLongsList with " + maxTriples
                    + " max triples and " + triplesPerChunk + " triples per chunk");
        }
        this.maxTriples = maxTriples;
        this.triplesPerChunk = triplesPerChunk;
    }

    /**
     * Adds a triple of longs.
     *
     * @param l1
     * 		first long to add
     * @param l2
     * 		second long to add
     * @param l3
     * 		third long to add
     * @throws IllegalStateException
     * 		if the list size would exceed its max capacity
     */
    public void add(final long l1, final long l2, final long l3) {
        if (nextIndex == maxTriples) {
            throw new IllegalStateException("Cannot store any more triples, reached max capacity of " + maxTriples);
        }

        final int chunkIndex = nextIndex / triplesPerChunk;
        int subIndex = nextIndex % triplesPerChunk;
        if (subIndex == 0 && numChunks == chunkIndex) {
            final long[] nextChunk = new long[LONGS_PER_TRIPLE * triplesPerChunk];
            data.add(nextChunk);
            numChunks++;
        }

        final long[] chunk = data.get(chunkIndex);
        subIndex *= LONGS_PER_TRIPLE;
        chunk[subIndex] = l1;
        chunk[subIndex + SECOND_LONG_OFFSET] = l2;
        chunk[subIndex + THIRD_LONG_OFFSET] = l3;

        nextIndex++;
    }

    /**
     * Get long triplet at given index
     *
     * @param index
     * 		the index to get
     * @return array of the three longs in triplet
     * @throws IndexOutOfBoundsException
     * 		if index is negative or not less than nextIndex
     */
    public long[] get(final int index) {
        if (index < 0 || index >= nextIndex) {
            throw new IndexOutOfBoundsException("Index " + index + " unusable with " + nextIndex + " triples stored");
        }
        final int chunkIndex = index / triplesPerChunk;
        final long[] chunk = data.get(chunkIndex);
        final int subIndex = (index % triplesPerChunk) * LONGS_PER_TRIPLE;
        return Arrays.copyOfRange(chunk, subIndex, subIndex + LONGS_PER_TRIPLE);
    }

    /**
     * Clear contents leaving capacity the same.
     */
    public void clear() {
        nextIndex = 0;
    }

    /**
     * For each to iterate over all triples stored.
     *
     * @param handler
     * 		callback to receive each triple.
     */
    public <T extends Throwable> void forEach(final ThreeLongFunction<T> handler) throws T {
        int index = 0;
        for (final long[] chunk : data) {
            for (int j = 0, k = 0; j < triplesPerChunk; j++, k += LONGS_PER_TRIPLE) {
                if (index == nextIndex) {
                    return;
                }
                handler.process(chunk[k], chunk[k + SECOND_LONG_OFFSET], chunk[k + THIRD_LONG_OFFSET]);
                index++;
            }
        }
    }

    /**
     * Gets the number of long triplets actually stored in this list (that is, the number
     * of times that {@link ThreeLongsList#add(long, long, long)} has been called since
     * the last call to {@link ThreeLongsList#clear()}.
     *
     * @return the number of triplets in the list
     */
    public int size() {
        return nextIndex;
    }

    /**
     * Gets the maximum number of triples that can be stored in this list.
     *
     * @return the max capacity of the list
     */
    public int capacity() {
        return maxTriples;
    }

    /**
     * Simple functional interface to process three longs. A Throwable of the given
     * type may be thrown.
     *
     * @param <T>
     *     Type of Throwable that may be thrown
     */
    @FunctionalInterface
    public interface ThreeLongFunction<T extends Throwable> {
        void process(long l1, long l2, long l3) throws T;
    }

    int getTriplesPerChunk() {
        return triplesPerChunk;
    }
}
