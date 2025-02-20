// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe implementation of the {@link Counter}
 */
public class ThreadSafeCounter implements Counter {

    public ThreadSafeCounter() {}

    /**
     * the count of bytes passed through the stream
     */
    private final AtomicLong count = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public long addToCount(long value) {
        return count.addAndGet(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetCount() {
        count.set(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        return count.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getAndResetCount() {
        return count.getAndSet(0);
    }
}
