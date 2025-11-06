// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat.container;

import static org.hiero.metrics.api.stat.StatUtils.DOUBLE_INIT;
import static org.hiero.metrics.api.stat.StatUtils.DOUBLE_SUM;
import static org.hiero.metrics.api.stat.StatUtils.ZERO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import java.util.function.LongBinaryOperator;

/**
 * A double value that may be updated atomically. This class uses an {@link AtomicLong} to
 * represent the bits of the double value.
 */
public final class AtomicDouble implements DoubleSupplier {

    private static final LongBinaryOperator SUM_OPERATOR = convertBinaryOperator(DOUBLE_SUM);

    private final DoubleSupplier initializer;
    private final AtomicLong container;

    /**
     * Creates a new {@code AtomicDouble} with the given initializer.
     *
     * @param initializer the initializer to use
     * @throws NullPointerException if the initializer is null
     */
    public AtomicDouble(@NonNull DoubleSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "Initializer cannot be null");
        container = new AtomicLong(fromDouble(initializer.getAsDouble()));
    }

    /**
     * Creates a new {@code AtomicDouble} with the given initial value.
     *
     * @param initialValue the initial value
     */
    public AtomicDouble(double initialValue) {
        this(initialValue == ZERO ? DOUBLE_INIT : () -> initialValue);
    }

    /**
     * Creates a new {@code AtomicDouble} with an initial value of {@code 0.0}.
     */
    public AtomicDouble() {
        this(DOUBLE_INIT);
    }

    /**
     * Returns the initial value of this {@code AtomicDouble}.
     *
     * @return the initial value
     */
    public double getInitValue() {
        return initializer.getAsDouble();
    }

    /**
     * Returns the current value of this {@code AtomicDouble}.
     *
     * @return the current value
     */
    @Override
    public double getAsDouble() {
        return toDouble(container.get());
    }

    /**
     * Resets the value of this {@code AtomicDouble} to its initial value.
     */
    public void reset() {
        container.set(fromDouble(getInitValue()));
    }

    /**
     * Sets the value of this {@code AtomicDouble} to the given value.
     *
     * @param value the new value
     */
    public void set(double value) {
        container.set(fromDouble(value));
    }

    /**
     * Atomically sets the value to the given updated value and returns the previous value.
     *
     * @param newValue the new value
     * @return the previous value
     */
    public double getAndSet(double newValue) {
        return toDouble(container.getAndSet(fromDouble(newValue)));
    }

    /**
     * Atomically sets the value to the initial value and returns the previous value.
     *
     * @return the previous value
     */
    public double getAndReset() {
        return toDouble(container.getAndSet(fromDouble(getInitValue())));
    }

    /**
     * Atomically sets the value to the given updated value if the current value {@code ==}
     * the expected value.
     *
     * @param expectedValue the expected value
     * @param newValue      the new value
     * @return {@code true} if successful. False return indicates that the actual value
     * was not equal to the expected value.
     */
    public boolean compareAndSet(double expectedValue, double newValue) {
        return container.compareAndSet(fromDouble(expectedValue), fromDouble(newValue));
    }

    /**
     * Atomically updates the current value with the results of applying the given
     * function to the current and given values, returning the updated value.
     * {@link #convertBinaryOperator(DoubleBinaryOperator)} can be used to convert
     * a double binary operator to long binary operator.
     *
     * @param value    the value to be combined with the current value
     * @param operator a side-effect-free function of two arguments
     *                 that produces a result equal to the desired updated value
     * @return the updated value
     */
    public double accumulateAndGet(double value, LongBinaryOperator operator) {
        return container.accumulateAndGet(fromDouble(value), operator);
    }

    /**
     * Atomically adds the given value to the current value.
     *
     * @param delta the value to add
     * @return the updated value
     */
    public double addAndGet(final double delta) {
        return accumulateAndGet(delta, SUM_OPERATOR);
    }

    /**
     * Converts a {@link DoubleBinaryOperator} to a {@link LongBinaryOperator}
     * to be used in {@link #accumulateAndGet(double, LongBinaryOperator)}.
     *
     * @param operator the double binary operator to convert
     * @return long binary operator that applies the given double binary operator
     * to the double values represented by the long inputs and returns the result
     */
    public static LongBinaryOperator convertBinaryOperator(@NonNull DoubleBinaryOperator operator) {
        Objects.requireNonNull(operator, "operator must not be null");
        return (prev, cur) -> fromDouble(operator.applyAsDouble(toDouble(prev), toDouble(cur)));
    }

    private static long fromDouble(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static double toDouble(long value) {
        return Double.longBitsToDouble(value);
    }
}
