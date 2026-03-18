// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting.internal;

/**
 * A counting result that is fast, but not thread safe.
 */
public class FastByteCounter implements ModifiableByteCounter {

    private long count;

    @Override
    public long addToCount(final long value) {
        return (count += value);
    }

    @Override
    public long getCount() {
        return count;
    }

    @Override
    public long getAndReset() {
        final long result = count;
        count = 0;
        return result;
    }
}
