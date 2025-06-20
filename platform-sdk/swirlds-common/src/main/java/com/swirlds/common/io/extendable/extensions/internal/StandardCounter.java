// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions.internal;

/**
 * A counter that is not thread safe.
 */
public class StandardCounter implements Counter {

    private long count;

    public StandardCounter() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetCount() {
        count = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getAndResetCount() {
        final long ret = count;
        count = 0;
        return ret;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long addToCount(final long value) {
        count += value;
        return count;
    }
}
