// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A class that tracks the number of events in memory
 */
public class EventCounter {
    /**
     * Number of events currently in memory, for all instantiated Platforms put together. This is only used for an
     * internal statistic, not for any of the algorithms.
     */
    private static final AtomicLong numEventsInMemory = new AtomicLong(0);

    private static final RunningAverageMetric.Config AVG_EVENTS_IN_MEM_CONFIG = new RunningAverageMetric.Config(
                    INTERNAL_CATEGORY, "eventsInMem")
            .withDescription("total number of events in memory, for all members on the local machine together")
            .withUnit("count");

    /**
     * Number of events currently in memory, for all instantiated Platforms put together.
     *
     * @return long value for number of events.
     */
    public static long getNumEventsInMemory() {
        return numEventsInMemory.get();
    }

    /**
     * Called when an event is linked.
     */
    public static void incrementLinkedEventCount() {
        numEventsInMemory.incrementAndGet();
    }

    /**
     * Called when a linked event is cleared, to decrement the count of how many uncleared events are in memory
     */
    public static void decrementLinkedEventCount() {
        numEventsInMemory.decrementAndGet();
    }

    /**
     * Provide the metrics instance to the event counter.
     *
     * @param metrics the metrics engine
     */
    public static void registerEventCounterMetrics(@NonNull final Metrics metrics) {
        final RunningAverageMetric avgEventsInMem = metrics.getOrCreate(AVG_EVENTS_IN_MEM_CONFIG);
        metrics.addUpdater(() -> avgEventsInMem.update(getNumEventsInMemory()));
    }
}
