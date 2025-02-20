// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A maximum value that is updated atomically and is thread safe
 */
public class AtomicMax {
    /** default value to return if max is not initialized */
    private static final long DEFAULT_UNINITIALIZED = 0;

    private final AtomicLong max;
    /** the value to return before any values update the max */
    private final long uninitializedValue;

    public AtomicMax(final long uninitializedValue) {
        this.uninitializedValue = uninitializedValue;
        max = new AtomicLong(uninitializedValue);
    }

    public AtomicMax() {
        this(DEFAULT_UNINITIALIZED);
    }

    public long get() {
        return max.get();
    }

    public void reset() {
        max.set(uninitializedValue);
    }

    public long getAndReset() {
        return max.getAndSet(uninitializedValue);
    }

    public void update(final long value) {
        max.accumulateAndGet(value, Math::max);
    }
}
