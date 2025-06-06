// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.utility.LongRunningAverage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps local stats of the writing process.
 * The class is independent of the time unit selected for averaging the results
 * The consistency metrics units should be managed by the caller.
 * The suggested time unit for the events is nanoseconds.
 * Used by {@link PcesFileWriter}
 */
public class PcesFileWriterStats {
    private final LongRunningAverage averageEventSize = new LongRunningAverage(10000);
    private final LongRunningAverage averageTotalWriteDuration = new LongRunningAverage(10000);
    private final LongRunningAverage averageWriteDuration = new LongRunningAverage(10000);
    private final LongRunningAverage averageSyncDuration = new LongRunningAverage(10000);
    private final AtomicLong totalExpansions = new AtomicLong();

    /**
     * Updates the stats related to the total write operation
     * @param startTime when the operation started
     * @param endTime when the operation finished
     * @param size the written event size in bytes
     * @param bufferExpanded whether a buffer expansion happened
     */
    void updateWriteStats(final long startTime, final long endTime, final int size, final boolean bufferExpanded) {
        averageEventSize.add(size);
        averageTotalWriteDuration.add(endTime - startTime);
        if (bufferExpanded) {
            totalExpansions.incrementAndGet();
        }
    }

    /**
     * Updates the stats related to the total and partial write operation
     * @param startTime when the operation started
     * @param endTime when the operation finished
     * @param size the written event size in bytes
     */
    void updateWriteStats(final long startTime, final long endTime, final int size) {
        updateWriteStats(startTime, endTime, size, false);
        updatePartialWriteStats(startTime, endTime);
    }

    /**
     * Updates the stats related to the partial write operation
     * @param startTime when the operation started
     * @param endTime when the operation finished
     */
    void updatePartialWriteStats(final long startTime, final long endTime) {
        averageWriteDuration.add(endTime - startTime);
    }

    /**
     * Updates the stats related to the sync operation
     * @param startTime when the operation started
     * @param endTime when the operation finished
     */
    void updateSyncStats(final long startTime, final long endTime) {
        averageSyncDuration.add(endTime - startTime);
    }

    /**
     * @return the average event size written in bytes
     */
    public long averageEventSize() {
        return averageEventSize.getAverage();
    }

    /**
     * @return the average write operation time
     */
    public long averageTotalWriteDuration() {
        return averageTotalWriteDuration.getAverage();
    }

    /**
     * @return the average write sys call duration (for writers with complex write operations where it might require more than just one syscall)
     */
    public long averageWriteDuration() {
        return averageWriteDuration.getAverage();
    }

    /**
     * @return the average sync operation time
     */
    public long averageSyncDuration() {
        return averageSyncDuration.getAverage();
    }

    /**
     * @return the number of times the buffer was expanded
     */
    public long totalExpansions() {
        return totalExpansions.get();
    }
}
