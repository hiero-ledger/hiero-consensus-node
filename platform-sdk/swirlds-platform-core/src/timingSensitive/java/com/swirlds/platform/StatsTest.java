// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.common.metrics.statistics.StatsRunningAverage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StatsTest {
    /**
     * default half-life for statistics
     */
    private static final double DEFAULT_HALF_LIFE = 10;
    /**
     * avg time taken to execute the FCQueue getHash method, including locks (in microseconds)
     */
    @SuppressWarnings("removal")
    private static final StatsRunningAverage stats = new StatsRunningAverage(DEFAULT_HALF_LIFE);

    /**
     * how many threads we use for testing
     */
    private static final int THREADS_NUM = 100;

    /**
     * how many time we call recordValue()
     */
    private static final int RECORD_TIME = 10_000_000;

    /**
     * the maximum time to wait
     */
    private static final int TIME_OUT = 2;

    /**
     * Starts multiple threads call recordValue().
     * This test should not throw ArrayIndexOutOfBoundsException.
     */
    @Test
    void recordValueMultiThreadsTest() {
        try {
            ExecutorService executorService = Executors.newFixedThreadPool(THREADS_NUM);
            for (int i = 0; i < RECORD_TIME; i++) {
                executorService.submit(
                        () -> stats.recordValue(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE)));
            }
            executorService.shutdown();
            executorService.awaitTermination(TIME_OUT, TimeUnit.SECONDS);
        } catch (Exception exception) {
            assertFalse(
                    exception instanceof ArrayIndexOutOfBoundsException,
                    "This test should not throw ArrayIndexOutOfBoundsException");
        }
    }
}
