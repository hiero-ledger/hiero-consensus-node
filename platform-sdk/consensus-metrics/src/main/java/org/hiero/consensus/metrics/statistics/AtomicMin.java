// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimum value that is updated atomically and is thread safe
 */
public class AtomicMin {
    /** default value to return if min is not initialized */
    private static final long DEFAULT_UNINITIALIZED = Long.MAX_VALUE;

    private final AtomicLong min;
    /** the value to return before any values update the max */
    private final long uninitializedValue;

    public AtomicMin(final long uninitializedValue) {
        this.uninitializedValue = uninitializedValue;
        min = new AtomicLong(uninitializedValue);
    }

    public AtomicMin() {
        this(DEFAULT_UNINITIALIZED);
    }

    public long get() {
        return min.get();
    }

    public void reset() {
        min.set(uninitializedValue);
    }

    public long getAndReset() {
        return min.getAndSet(uninitializedValue);
    }

    public void update(final long value) {
        min.accumulateAndGet(value, Math::min);
    }
}
