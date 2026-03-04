// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe container for collecting validation statistics across different data types.
 */
public final class DataStats {

    private final StatGroup p2kv = new StatGroup();
    private final StatGroup p2h = new StatGroup();
    private final StatGroup k2p = new StatGroup();
    private final StatGroup p2hMemory = new StatGroup();

    public StatGroup getP2kv() {
        return p2kv;
    }

    public StatGroup getP2h() {
        return p2h;
    }

    public StatGroup getK2p() {
        return k2p;
    }

    public StatGroup getP2hMemory() {
        return p2hMemory;
    }

    // --- Aggregations ---

    public long getTotalSpaceSize() {
        // Note: memory items don't track space (no disk footprint)
        return p2h.getSpaceSize() + p2kv.getSpaceSize() + k2p.getSpaceSize();
    }

    public long getTotalItemCount() {
        return p2h.getItemCount() + p2kv.getItemCount() + k2p.getItemCount() + p2hMemory.getItemCount();
    }

    public long getObsoleteSpaceSize() {
        // Note: memory items don't track obsolete space (no disk footprint)
        return p2h.getObsoleteSpaceSize() + p2kv.getObsoleteSpaceSize() + k2p.getObsoleteSpaceSize();
    }

    public long getObsoleteItemCount() {
        // Note: memory items don't track obsolete items (no disk footprint)
        return p2h.getObsoleteItemCount() + p2kv.getObsoleteItemCount() + k2p.getObsoleteItemCount();
    }

    public boolean hasErrorReads() {
        return p2kv.hasErrors() || p2h.hasErrors() || k2p.hasErrors() || p2hMemory.hasErrors();
    }

    @Override
    public String toString() {
        return String.format(
                """
                Total Data Stats:
                  Total items: %,d
                  Total space: %,d bytes
                  Obsolete items: %,d
                  Obsolete space: %,d bytes""", getTotalItemCount(), getTotalSpaceSize(), getObsoleteItemCount(), getObsoleteSpaceSize());
    }

    /**
     * Grouping of statistics for a single data type.
     */
    public static final class StatGroup {
        private final AtomicLong spaceSize = new AtomicLong();
        private final AtomicLong itemCount = new AtomicLong();
        private final AtomicLong obsoleteSpaceSize = new AtomicLong();
        private final AtomicLong obsoleteItemCount = new AtomicLong();
        private final AtomicLong parseErrorCount = new AtomicLong();
        private final AtomicLong invalidLocationCount = new AtomicLong();

        public void addSpaceSize(long bytes) {
            spaceSize.addAndGet(bytes);
        }

        public void incrementItemCount() {
            itemCount.incrementAndGet();
        }

        public void addObsoleteSpaceSize(long bytes) {
            obsoleteSpaceSize.addAndGet(bytes);
        }

        public void incrementObsoleteItemCount() {
            obsoleteItemCount.incrementAndGet();
        }

        public void incrementParseErrorCount() {
            parseErrorCount.incrementAndGet();
        }

        public void incrementInvalidLocationCount() {
            invalidLocationCount.incrementAndGet();
        }

        public long getSpaceSize() {
            return spaceSize.get();
        }

        public long getItemCount() {
            return itemCount.get();
        }

        public long getObsoleteSpaceSize() {
            return obsoleteSpaceSize.get();
        }

        public long getObsoleteItemCount() {
            return obsoleteItemCount.get();
        }

        public long getParseErrorCount() {
            return parseErrorCount.get();
        }

        public long getInvalidLocationCount() {
            return invalidLocationCount.get();
        }

        public boolean hasErrors() {
            return parseErrorCount.get() > 0 || invalidLocationCount.get() > 0;
        }

        // Helper for the parent toString
        public String toStringContent() {
            return String.format(
                    """
                    Total items: %,d
                      Total space: %,d bytes
                      Obsolete items: %,d
                      Obsolete space: %,d bytes
                      Parse errors: %,d
                      Invalid locations: %,d""",
                    getItemCount(),
                    getSpaceSize(),
                    getObsoleteItemCount(),
                    getObsoleteSpaceSize(),
                    getParseErrorCount(),
                    getInvalidLocationCount());
        }
    }
}
