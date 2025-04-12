// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.utility.LongRunningAverage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps stats of the writing process.
 * Used by {@link PcesFileWriter}
 */
public class PcesFileWritingStats {
    private final LongRunningAverage averageEventSize = new LongRunningAverage(10000);
    private final LongRunningAverage averageTotalWriteDuration = new LongRunningAverage(10000);
    private final LongRunningAverage averageFsWriteDuration = new LongRunningAverage(10000);
    private final LongRunningAverage averageSyncDuration = new LongRunningAverage(10000);
    private final AtomicLong totalExpansions = new AtomicLong();

    void updateWriteStats(
            final long startTartTime,
            final long writeStart,
            final long writeFinish,
            final int size,
            final boolean bufferExpanded,
            final long endTime) {
        averageEventSize.add(size);
        averageFsWriteDuration.add(endTime - startTartTime);
        if (writeFinish != -1) {
            averageTotalWriteDuration.add(writeFinish - writeStart);
        }
        if (bufferExpanded) {
            totalExpansions.incrementAndGet();
        }
    }

    void updateSyncStats(final long startTartTime, final long endTime) {
        averageSyncDuration.add(endTime - startTartTime);
    }

    public long averageEventSize() {
        return averageEventSize.getAverage();
    }

    public long averageTotalWriteDuration() {
        return averageTotalWriteDuration.getAverage();
    }

    public long averageFsWriteDuration() {
        return averageFsWriteDuration.getAverage();
    }

    public long averageSyncDuration() {
        return averageSyncDuration.getAverage();
    }

    public long totalExpansions() {
        return totalExpansions.get();
    }
}
