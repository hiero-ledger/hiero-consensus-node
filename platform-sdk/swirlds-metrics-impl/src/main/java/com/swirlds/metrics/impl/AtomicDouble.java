// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl;

import static java.lang.Double.doubleToRawLongBits;
import static java.lang.Double.longBitsToDouble;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * A (partial) implementation of an {@code AtomicDouble}, which allows atomic updates and
 * CAS-operations on a {@code double} value.
 * <p>
 * This implementation is inspired by the {@code AtomicDouble} implementations in JSR 166 extra and Guava.
 * <p>
 * Note: In CAS-operations, this class does not compare the {@code double}-values directly, but the
 * bits one gets by calling {@link Double#doubleToRawLongBits(double)}.
 */
public class AtomicDouble {

    private volatile long bits;

    private static final AtomicLongFieldUpdater<AtomicDouble> updater =
            AtomicLongFieldUpdater.newUpdater(AtomicDouble.class, "bits");

    /**
     * Creates a new {@code AtomicLong} with the given initial value.
     *
     * @param initialValue
     * 		the initial value
     */
    public AtomicDouble(double initialValue) {
        bits = doubleToRawLongBits(initialValue);
    }

    /**
     * Creates a new {@code AtomicDouble} with initial value {@code 0.0}.
     */
    public AtomicDouble() {}

    /**
     * Returns the current value, with memory effects as specified by
     * {@link java.lang.invoke.VarHandle#getVolatile(Object...)}.
     *
     * @return the current value
     */
    public final double get() {
        return longBitsToDouble(bits);
    }

    /**
     * Sets the value to newValue, with memory effects as specified by
     * {@link java.lang.invoke.VarHandle#setVolatile(Object...)}.
     *
     * @param newValue
     * 		the new value
     */
    public final void set(double newValue) {
        bits = doubleToRawLongBits(newValue);
    }

    /**
     * Atomically sets the value to {@code newValue} and returns the old value.
     *
     * @param newValue
     * 		the new value
     * @return the previous value
     */
    public final double getAndSet(double newValue) {
        final long newBits = doubleToRawLongBits(newValue);
        return longBitsToDouble(updater.getAndSet(this, newBits));
    }

    /**
     * Atomically sets the value to the given updated value if the current value is bitwise equal, that is,
     * if {@link Double#doubleToRawLongBits(double)} returns the same value.
     *
     * @param expectedValue
     * 		the expected value
     * @param newValue
     * 		the new value
     * @return {@code true} if successful, {@code false} otherwise.
     */
    public final boolean compareAndSet(double expectedValue, double newValue) {
        final long expectedBits = doubleToRawLongBits(expectedValue);
        final long newBits = doubleToRawLongBits(newValue);
        return updater.compareAndSet(this, expectedBits, newBits);
    }

    /**
     * Atomically adds the given value to the current value.
     *
     * @param delta the value to add
     * @return the updated value
     */
    public final double addAndGet(final double delta) {
        return accumulateAndGet(delta, Double::sum);
    }

    /**
     * Atomically updates the current value with the results of applying the given function to the
     * current and given values.
     *
     * @param updateValue the update value
     * @param accumulatorFunction the accumulator function
     * @return the updated value
     */
    public final double accumulateAndGet(
            final double updateValue, @NonNull final DoubleBinaryOperator accumulatorFunction) {
        Objects.requireNonNull(accumulatorFunction);
        return updateAndGet(oldValue -> accumulatorFunction.applyAsDouble(oldValue, updateValue));
    }

    /**
     * Atomically updates the current value with the results of applying the given function.
     *
     * @param updateFunction the update function
     * @return the updated value
     */
    public final double updateAndGet(@NonNull final DoubleUnaryOperator updateFunction) {
        Objects.requireNonNull(updateFunction);
        while (true) {
            long current = bits;
            double currentVal = longBitsToDouble(current);
            double nextVal = updateFunction.applyAsDouble(currentVal);
            long next = doubleToRawLongBits(nextVal);
            if (updater.compareAndSet(this, current, next)) {
                return nextVal;
            }
        }
    }
}
