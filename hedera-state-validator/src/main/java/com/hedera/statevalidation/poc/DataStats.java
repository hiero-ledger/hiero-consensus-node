// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc;

import java.util.concurrent.atomic.AtomicLong;

public final class DataStats {
    private final AtomicLong totalSpaceSize = new AtomicLong();
    private final AtomicLong totalItemCount = new AtomicLong();
    private final AtomicLong obsoleteSpaceSize = new AtomicLong();
    private final AtomicLong obsoleteItemCount = new AtomicLong();
    private final AtomicLong p2kvFailedToProcessCount = new AtomicLong();
    private final AtomicLong p2hFailedToProcessCount = new AtomicLong();
    private final AtomicLong k2pFailedToProcessCount = new AtomicLong();

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

    public void incrementP2kvFailedToProcessCount() {
        p2kvFailedToProcessCount.incrementAndGet();
    }

    public void incrementP2hFailedToProcessCount() {
        p2hFailedToProcessCount.incrementAndGet();
    }

    public void incrementK2pFailedToProcessCount() {
        k2pFailedToProcessCount.incrementAndGet();
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

    public long getP2kvFailedToProcessCount() {
        return p2kvFailedToProcessCount.get();
    }

    public long getP2hFailedToProcessCount() {
        return p2hFailedToProcessCount.get();
    }

    public long getK2pFailedToProcessCount() {
        return k2pFailedToProcessCount.get();
    }

    @Override
    public String toString() {
        return String.format(
                """
                DataStats:
                  Total space: %,d bytes
                  Total items: %,d
                  Obsolete space: %,d bytes
                  Obsolete items: %,d
                  P2KV items failed to process: %,d
                  P2H items failed to process: %,d
                  K2P items failed to process: %,d""",
                getTotalSpaceSize(),
                getTotalItemCount(),
                getObsoleteSpaceSize(),
                getObsoleteItemCount(),
                getP2kvFailedToProcessCount(),
                getP2hFailedToProcessCount(),
                getK2pFailedToProcessCount());
    }
}
