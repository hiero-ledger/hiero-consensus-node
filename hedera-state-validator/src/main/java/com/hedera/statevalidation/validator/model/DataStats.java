// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe container for collecting validation statistics across different data types.
 */
public final class DataStats {

    private final StatGroup p2kv = new StatGroup();
    private final StatGroup id2c = new StatGroup();
    private final StatGroup k2p = new StatGroup();

    public StatGroup getP2kv() {
        return p2kv;
    }

    public StatGroup getId2c() {
        return id2c;
    }

    public StatGroup getK2p() {
        return k2p;
    }

    // --- Aggregations ---

    public long getTotalSpaceSize() {
        return id2c.getSpaceSize() + p2kv.getSpaceSize() + k2p.getSpaceSize();
    }

    public long getTotalItemCount() {
        return id2c.getItemCount() + p2kv.getItemCount() + k2p.getItemCount();
    }

    public long getObsoleteSpaceSize() {
        return id2c.getObsoleteSpaceSize() + p2kv.getObsoleteSpaceSize() + k2p.getObsoleteSpaceSize();
    }

    public long getObsoleteItemCount() {
        return id2c.getObsoleteItemCount() + p2kv.getObsoleteItemCount() + k2p.getObsoleteItemCount();
    }

    public boolean hasErrorReads() {
        return p2kv.hasErrors() || id2c.hasErrors() || k2p.hasErrors();
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

        public boolean hasErrors() {
            return parseErrorCount.get() > 0;
        }

        // Helper for the parent toString
        public String toStringContent() {
            return String.format(
                    """
                    Total items: %,d
                      Total space: %,d bytes
                      Obsolete items: %,d
                      Obsolete space: %,d bytes
                      Parse errors: %,d""",
                    getItemCount(),
                    getSpaceSize(),
                    getObsoleteItemCount(),
                    getObsoleteSpaceSize(),
                    getParseErrorCount());
        }
    }
}
