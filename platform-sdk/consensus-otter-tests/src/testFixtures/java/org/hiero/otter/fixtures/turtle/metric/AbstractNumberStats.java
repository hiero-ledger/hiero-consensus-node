// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.metric;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractNumberStats provides a base implementation for the {@link NumberStats} interface.
 * It defines common behaviors and properties for computing and tracking statistics
 * such as mean, count, minimum, and maximum on a series of numeric values.
 *
 * Subclasses are responsible for providing specific implementations for:
 * - Computing the mean
 * - Determining the minimum and maximum values
 * - Updating the statistics with a new numeric value
 *
 * Thread safety is ensured by using a {@link Lock}, allowing safe access and modification
 * of shared state in a multi-threaded environment.
 */
public abstract class AbstractNumberStats implements NumberStats {
    protected final Lock lock = new ReentrantLock();
    protected long count = 0;

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMean() {
        lock.lock();
        try {
            return computeMean();
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMin() {
        lock.lock();
        try {
            return count == 0 ? Double.NaN : getMinUnsafe();
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMax() {
        lock.lock();
        try {
            return count == 0 ? Double.NaN : getMaxUnsafe();
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Number> void updateValue(final T value) {
        lock.lock();
        try {
            count++;
            update(value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format(
                    "%s{count=%d, mean=%.4f, min=%.4f, max=%.4f}",
                    getClass().getSimpleName(), count, getMean(), getMin(), getMax());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the internal state of the subclass with the given numeric value.
     * This method is meant to be implemented by subclasses to define specific
     * behaviors for processing and updating statistics with the provided value.
     *
     * @param value the numeric value to incorporate into the statistics; must not be null
     * @param <T>   the type of the numeric value, which must extend {@link Number}
     */
    protected abstract <T extends Number> void update(final T value);

    /**
     * Computes and returns the mean (average) of the numeric values processed by the subclass.
     * The calculation depends on the implementation in the concrete subclass.
     *
     * @return the computed mean of the numeric values
     */
    protected abstract double computeMean();

    /**
     * Retrieves the minimum value from the internal state without any additional safety checks
     * or locking mechanisms. This method is intended to be used internally by subclasses and may
     * assume that appropriate synchronization, if necessary, has already been handled by the caller.
     *
     * @return the minimum value from the internal state as a double
     */
    protected abstract double getMinUnsafe();

    /**
     * Retrieves the maximum value from the internal state without applying any synchronization
     * or safety checks. This method is intended to be used internally by subclasses and assumes
     * that any required locking or synchronization has been handled by the caller.
     *
     * @return the maximum value from the internal state as a double
     */
    protected abstract double getMaxUnsafe();
}
