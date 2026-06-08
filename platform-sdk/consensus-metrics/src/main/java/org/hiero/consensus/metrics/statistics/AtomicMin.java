// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimum value that is updated atomically and is thread safe
 */
public class AtomicMin {

    private final AtomicLong min;
    /** the value to return before any values update the min */
    private final long uninitializedValue;

    public AtomicMin(final long uninitializedValue) {
        this.uninitializedValue = uninitializedValue;
        min = new AtomicLong(uninitializedValue);
    }

    public AtomicMin() {
        this(Long.MAX_VALUE);
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
