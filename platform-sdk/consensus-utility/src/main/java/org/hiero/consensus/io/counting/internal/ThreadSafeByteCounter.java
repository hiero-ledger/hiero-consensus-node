// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe implementation of the {@link ModifiableByteCounter}
 */
public class ThreadSafeByteCounter implements ModifiableByteCounter {

    private final AtomicLong count = new AtomicLong(0);
    private final AtomicLong totalCount = new AtomicLong(0);

    @Override
    public void addToCount(final long value) {
        count.addAndGet(value);
        totalCount.addAndGet(value);
    }

    @Override
    public long getCount() {
        return count.get();
    }

    @Override
    public long getTotalCount() {
        return totalCount.get();
    }

    @Override
    public long getAndReset() {
        return count.getAndSet(0);
    }
}
