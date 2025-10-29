// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat.container;

import static org.hiero.metrics.api.stat.StatUtils.INT_LAST;
import static org.hiero.metrics.api.stat.StatUtils.INT_SUM;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.ToDoubleBiFunction;

/**
 * Holds two integers that can be updated atomically.
 */
public final class AtomicIntPair {

    private final AtomicLong container;
    private final LongBinaryOperator operator;

    /**
     * @param leftOperator  the method that will be used to calculate the new value for the left integer when
     *                         {@link #accumulate(int, int)} is called
     * @param rightOperator the method that will be used to calculate the new value for the right integer when
     *                         {@link #accumulate(int, int)} is called
     */
    public AtomicIntPair(final IntBinaryOperator leftOperator, final IntBinaryOperator rightOperator) {
        operator = (current, supplied) -> {
            final int left = leftOperator.applyAsInt(extractLeftInt(current), extractLeftInt(supplied));
            final int right = rightOperator.applyAsInt(extractRightInt(current), extractRightInt(supplied));
            return combineInts(left, right);
        };
        this.container = new AtomicLong();
    }

    public static AtomicIntPair createAccumulatingSum() {
        return new AtomicIntPair(INT_SUM, INT_SUM);
    }

    public static AtomicIntPair createKeepLatest() {
        return new AtomicIntPair(INT_LAST, INT_LAST);
    }

    /**
     * Update the integers with the provided values. The update will be done by the accumulator method provided in the
     * constructor
     *
     * @param leftValue  the value provided to the left integer
     * @param rightValue the value provided to the left integer
     */
    public void accumulate(final int leftValue, final int rightValue) {
        container.accumulateAndGet(combineInts(leftValue, rightValue), operator);
    }

    /**
     * @return the current value of the left integer
     */
    public int getLeft() {
        return extractLeftInt(container.get());
    }

    /**
     * @return the current value of the right integer
     */
    public int getRight() {
        return extractRightInt(container.get());
    }

    /**
     * Compute a double value based on the input of the integer pair
     *
     * @param compute the method to compute the double
     * @return the double computed
     */
    public double computeDouble(final ToDoubleBiFunction<Integer, Integer> compute) {
        final long twoInts = container.get();
        return compute.applyAsDouble(extractLeftInt(twoInts), extractRightInt(twoInts));
    }

    /**
     * Same as {@link #computeDouble(ToDoubleBiFunction)} but also atomically resets the integers to the initial value
     */
    public double computeDoubleAndReset(final ToDoubleBiFunction<Integer, Integer> compute) {
        return computeDoubleAndSet(compute, 0, 0);
    }

    /**
     * Atomically computes a double using the provided function and sets the values to the ones provided
     *
     * @param compute the compute function
     * @param left    the left value to set
     * @param right   the right value to set
     * @return the double computed
     */
    public double computeDoubleAndSet(
            final ToDoubleBiFunction<Integer, Integer> compute, final int left, final int right) {
        final long twoInts = container.getAndSet(combineInts(left, right));
        return compute.applyAsDouble(extractLeftInt(twoInts), extractRightInt(twoInts));
    }

    /**
     * Sets the values of the two ints atomically
     *
     * @param left  the left value to set
     * @param right the right value to set
     */
    public void set(final int left, final int right) {
        container.set(combineInts(left, right));
    }

    /**
     * Compute an arbitrary value based on the input of the integer pair
     *
     * @param compute the method to compute the result
     * @param <T>     the type of the result
     * @return the result
     */
    public <T> T compute(final BiFunction<Integer, Integer, T> compute) {
        final long twoInts = container.get();
        return compute.apply(extractLeftInt(twoInts), extractRightInt(twoInts));
    }

    /**
     * Same as {@link #compute(BiFunction)} but also atomically resets the integers to {@code 0}
     */
    public <T> T computeAndReset(final BiFunction<Integer, Integer, T> compute) {
        final long twoInts = container.getAndSet(0);
        return compute.apply(extractLeftInt(twoInts), extractRightInt(twoInts));
    }

    /**
     * Same as {@link #compute(BiFunction)} but also atomically sets the integers to the provided values.
     */
    public <T> T computeAndSet(final BiFunction<Integer, Integer, T> compute, final int left, final int right) {
        final long twoInts = container.getAndSet(combineInts(left, right));
        return compute.apply(extractLeftInt(twoInts), extractRightInt(twoInts));
    }

    /**
     * Resets the integers to the initial value
     */
    public void reset() {
        container.getAndSet(0);
    }

    /**
     * Extract the left integer from a long
     *
     * @param pair the long to extract from
     * @return the left integer
     */
    private static int extractLeftInt(final long pair) {
        return (int) (pair >> 32);
    }

    /**
     * Extract the right integer from a long
     *
     * @param pair the long to extract from
     * @return the right integer
     */
    private static int extractRightInt(final long pair) {
        return (int) pair;
    }

    /**
     * Combine the two integers into a single long
     *
     * @param left  the left integer
     * @param right the right integer
     * @return the combined long
     */
    private static long combineInts(final int left, final int right) {
        return (((long) left) << 32) | (right & 0xffffffffL);
    }
}
