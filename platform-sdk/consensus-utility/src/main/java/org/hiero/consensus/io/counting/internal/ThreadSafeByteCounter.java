// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe implementation of the {@link ModifiableByteCounter}
 */
public class ThreadSafeByteCounter implements ModifiableByteCounter {

    private final AtomicLong count = new AtomicLong(0);

    @Override
    public long addToCount(final long value) {
        return count.addAndGet(value);
    }

    @Override
    public long getCount() {
        return count.get();
    }

    @Override
    public long getAndReset() {
        return count.getAndSet(0);
    }
}
