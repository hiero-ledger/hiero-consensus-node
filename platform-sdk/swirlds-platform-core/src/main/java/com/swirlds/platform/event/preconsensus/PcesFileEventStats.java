// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.utility.LongRunningAverage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Used by {@link PcesFileWriter} to keeps local stats during the writing process.
 */
public class PcesFileEventStats {
    private final LongRunningAverage averageEventSize = new LongRunningAverage(10000);
    private final AtomicLong totalExpansions = new AtomicLong();

    /**
     * Updates the stats related to the total write operation
     * @param size the written event size in bytes
     * @param bufferExpanded whether a buffer expansion happened
     */
    void updateEventStats(final int size, final boolean bufferExpanded) {
        averageEventSize.add(size);
        if (bufferExpanded) {
            totalExpansions.incrementAndGet();
        }
    }

    /**
     * @return the average event size written in bytes
     */
    public long averageEventSize() {
        return averageEventSize.getAverage();
    }

    /**
     * @return the number of times the buffer was expanded
     */
    public long totalExpansions() {
        return totalExpansions.get();
    }
}
