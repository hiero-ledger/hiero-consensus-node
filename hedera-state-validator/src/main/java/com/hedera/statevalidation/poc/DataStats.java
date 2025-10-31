// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc;

import java.util.concurrent.atomic.AtomicLong;

public final class DataStats {
    private final AtomicLong totalSpaceSize = new AtomicLong();
    private final AtomicLong totalItemCount = new AtomicLong();
    private final AtomicLong obsoleteSpaceSize = new AtomicLong();
    private final AtomicLong obsoleteItemCount = new AtomicLong();

    public void addTotalSpaceBytes(long bytes) {
        totalSpaceSize.addAndGet(bytes);
    }

    public void incrementTotalItemCount() {
        totalItemCount.incrementAndGet();
    }

    public void addObsoleteSpaceBytes(long bytes) {
        obsoleteSpaceSize.addAndGet(bytes);
    }

    public void incrementObsoleteItemCount() {
        obsoleteItemCount.incrementAndGet();
    }

    public long getTotalSpaceSize() {
        return totalSpaceSize.get();
    }

    public long getTotalItemCount() {
        return totalItemCount.get();
    }

    public long getObsoleteSpaceSize() {
        return obsoleteSpaceSize.get();
    }

    public long getObsoleteItemCount() {
        return obsoleteItemCount.get();
    }

    @Override
    public String toString() {
        return String.format(
                """
                DataStats:
                  Total space: %,d bytes
                  Total items: %,d
                  Obsolete space: %,d bytes
                  Obsolete items: %,d""",
                getTotalSpaceSize(), getTotalItemCount(), getObsoleteSpaceSize(), getObsoleteItemCount());
    }
}
