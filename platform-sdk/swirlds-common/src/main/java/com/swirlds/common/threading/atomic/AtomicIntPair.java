// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.atomic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.ToDoubleBiFunction;
import org.hiero.base.utility.ByteUtils;

/**
 * Holds two integers that can be updated atomically
 */
public class AtomicIntPair {
    private static final int RESET_VALUE = 0;
    private final AtomicLong container;
    private final LongBinaryOperator operator;

    /**
     * Uses default accumulator method {@link Integer#sum(int, int)}
     */
    public AtomicIntPair() {
        this(Integer::sum, Integer::sum);
    }

    /**
     * @param leftAccumulator  the method that will be used to calculate the new value for the left integer when
     *                         {@link #accumulate(int, int)} is called
     * @param rightAccumulator the method that will be used to calculate the new value for the right integer when
     *                         {@link #accumulate(int, int)} is called
     */
    public AtomicIntPair(final IntBinaryOperator leftAccumulator, final IntBinaryOperator rightAccumulator) {
        operator = (current, supplied) -> {
            final int left =
                    leftAccumulator.applyAsInt(ByteUtils.extractLeftInt(current), ByteUtils.extractLeftInt(supplied));
            final int right = rightAccumulator.applyAsInt(
                    ByteUtils.extractRightInt(current), ByteUtils.extractRightInt(supplied));
            return ByteUtils.combineInts(left, right);
        };
        this.container = new AtomicLong(RESET_VALUE);
    }

    /**
     * Update the integers with the provided values. The update will be done by the accumulator method provided in the
     * constructor
     *
     * @param leftValue  the value provided to the left integer
     * @param rightValue the value provided to the left integer
     */
    public void accumulate(final int leftValue, final int rightValue) {
        container.accumulateAndGet(ByteUtils.combineInts(leftValue, rightValue), operator);
    }

    /**
     * @return the current value of the left integer
     */
    public int getLeft() {
        return ByteUtils.extractLeftInt(container.get());
    }

    /**
     * @return the current value of the right integer
     */
    public int getRight() {
        return ByteUtils.extractRightInt(container.get());
    }

    /**
     * Compute a double value based on the input of the integer pair
     *
     * @param compute the method to compute the double
     * @return the double computed
     */
    public double computeDouble(final ToDoubleBiFunction<Integer, Integer> compute) {
        final long twoInts = container.get();
        return compute.applyAsDouble(ByteUtils.extractLeftInt(twoInts), ByteUtils.extractRightInt(twoInts));
    }

    /**
     * Same as {@link #computeDouble(ToDoubleBiFunction)} but also atomically resets the integers to the initial value
     */
    public double computeDoubleAndReset(final ToDoubleBiFunction<Integer, Integer> compute) {
        return computeDoubleAndSet(compute, RESET_VALUE, RESET_VALUE);
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
        final long twoInts = container.getAndSet(ByteUtils.combineInts(left, right));
        return compute.applyAsDouble(ByteUtils.extractLeftInt(twoInts), ByteUtils.extractRightInt(twoInts));
    }

    /**
     * Sets the values of the two ints atomically
     *
     * @param left  the left value to set
     * @param right the right value to set
     */
    public void set(final int left, final int right) {
        container.set(ByteUtils.combineInts(left, right));
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
        return compute.apply(ByteUtils.extractLeftInt(twoInts), ByteUtils.extractRightInt(twoInts));
    }

    /**
     * Same as {@link #compute(BiFunction)} but also atomically resets the integers to {@code 0}
     */
    public <T> T computeAndReset(final BiFunction<Integer, Integer, T> compute) {
        final long twoInts = container.getAndSet(RESET_VALUE);
        return compute.apply(ByteUtils.extractLeftInt(twoInts), ByteUtils.extractRightInt(twoInts));
    }

    /**
     * Same as {@link #compute(BiFunction)} but also atomically sets the integers to the provided values.
     */
    public <T> T computeAndSet(final BiFunction<Integer, Integer, T> compute, final int left, final int right) {
        final long twoInts = container.getAndSet(ByteUtils.combineInts(left, right));
        return compute.apply(ByteUtils.extractLeftInt(twoInts), ByteUtils.extractRightInt(twoInts));
    }

    /**
     * Resets the integers to the initial value
     */
    public void reset() {
        container.getAndSet(RESET_VALUE);
    }
}
