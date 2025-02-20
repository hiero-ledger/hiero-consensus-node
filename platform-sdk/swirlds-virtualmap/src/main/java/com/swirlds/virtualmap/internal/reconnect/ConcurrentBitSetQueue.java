// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A concurrent FIFO queue-like data structure of ever-increasing Long values
 * implemented by an array of BitSets that together support more than
 * {@value Integer#MAX_VALUE} bits. This data structure supports a single writer thread
 * and a single reader thread.
 */
final class ConcurrentBitSetQueue {

    /**
     * Maximum number of elements per BitSet.
     * Currently, at 2^30 to improve performance on divisions
     */
    private static final int LIMIT = 0x40000000;

    /**
     * To speed up division, instead of doing {@code n / LIMIT},
     * we can compute it as {@code n >> lg(LIMIT)}, i.e., as
     * {@code n >> RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR}.
     */
    private static final int RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR = 30;

    /**
     * Value to compute {@code n % LIMIT} with the &amp; operator
     * to improve performance. Instead of {@code n % LIMIT},
     * we should use {@code n & DIVISOR}.
     */
    private static final int DIVISOR = LIMIT - 1;

    /**
     * Map to keep track of the bits associated with each interval.
     */
    private final ConcurrentLinkedDeque<BitSetNode> bitsets;

    /**
     * Number of elements currently in the queue
     */
    private final AtomicLong size;

    /**
     * Value to keep track of the latest element added
     * to guarantee a strictly increasing insertion.
     * No negative values are allowed, so the minimum
     * value accepted is 0.
     * This value is only require during insertion, hence
     * it is not required to be volatile.
     */
    private long previousBitIndex;

    /**
     * Value to keep track of the next element to be
     * removed. We keep track of the first element
     * inserted to speed up finding the first element
     * in the queue.
     */
    private long indexForRemoval;

    /**
     * Creates a new {@link ConcurrentBitSetQueue}.
     */
    ConcurrentBitSetQueue() {
        this.size = new AtomicLong();
        this.bitsets = new ConcurrentLinkedDeque<>();
        this.indexForRemoval = 0;
        this.previousBitIndex = -1;
    }

    /**
     * Adds the given long value to the queue. Only strictly increasing
     * long values are supported, i.e., each value added must be strictly
     * greater than the one before it.
     *
     * @param value a positive bit index
     * @throws IllegalArgumentException
     * 			If the bitIndex is not strictly greater than the previous one inserted
     */
    void add(final long value) {
        if (value <= previousBitIndex) {
            throw new IllegalArgumentException("Each value added must be strictly greater than the one before it");
        }

        final int index = getIndexInBitSetFor(value);
        final long bitSetIndex = getBitSetIndexFor(value);
        final long offset = bitSetIndex * LIMIT;
        final BitSetNode bitSetNode;
        if (bitsets.isEmpty() || bitsets.peekLast().offset != offset) {
            bitSetNode = new BitSetNode(offset, new BitSet(LIMIT));
            bitsets.add(bitSetNode);
        } else {
            bitSetNode = bitsets.peekLast();
        }

        final BitSet bitSet = bitSetNode.bitSet;
        bitSet.set(index);

        previousBitIndex = value;
        size.incrementAndGet();
    }

    /**
     * Removes the next long in the queue
     *
     * @return next long available in the queue
     * @throws IllegalStateException
     * 			If this queue is empty
     */
    long remove() {
        if (isEmpty()) {
            throw new IllegalStateException("BitSetQueue is empty");
        }

        int index;
        BitSetNode bitSetNode;
        index = getIndexInBitSetFor(indexForRemoval);
        bitSetNode = bitsets.peek();
        assert bitSetNode != null;
        if (bitSetNode.offsetEnd <= indexForRemoval || bitSetNode.bitSet.nextSetBit(index) < 0) {
            bitsets.remove();
            bitSetNode = bitsets.peek();
            assert bitSetNode != null;
            index = 0;
        }

        final BitSet bitSet = bitSetNode.bitSet;
        final int setIndex = bitSet.nextSetBit(index);
        final long value = bitSetNode.offset + setIndex;
        indexForRemoval = value + 1;
        this.size.decrementAndGet();
        return value;
    }

    /**
     * Returns true if this {@code ConcurrentBitSetQueue} contains no bits that are set to true.
     *
     * @return boolean indicating whether this {@code ConcurrentBitSetQueue} is empty
     */
    boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Returns the number of elements in this queue
     *
     * @return the number of elements in this queue
     */
    long size() {
        return size.get();
    }

    /**
     * Computes the index of the BitSet this value
     * should be set in
     *
     * @param value
     * 			Value to set/retrieve
     * @return index of the BitSet this value should be set in
     */
    static long getBitSetIndexFor(final long value) {
        return value >> RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR;
    }

    /**
     * Computes the index in the BitSet this value
     * should be set in
     *
     * @param value
     * 			Value to set/retrieve
     * @return index in the BitSet this value should be set in
     */
    static int getIndexInBitSetFor(final long value) {
        return (int) (value & DIVISOR);
    }

    private static final class BitSetNode {
        private final BitSet bitSet;
        private final long offset;
        private final long offsetEnd;

        private BitSetNode(final long offset, final BitSet bitset) {
            this.offset = offset;
            this.bitSet = bitset;
            this.offsetEnd = offset + LIMIT;
        }
    }
}
