// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A {@link TeachingSynchronizer} with simulated delay.
 */
public class BenchmarkSlowTeachingSynchronizer extends TeachingSynchronizer {

    private final long randomSeed;
    private final long delayStorageMicroseconds;
    private final double delayStorageFuzzRangePercent;
    private final long delayNetworkMicroseconds;
    private final double delayNetworkFuzzRangePercent;

    /**
     * Create a new teaching synchronizer with simulated latency.
     *
     * @param teacherMap the teacher's map
     * @param reconnectConfig the reconnect configuration
     * @param randomSeed seed for the delay fuzzers
     * @param delayStorageMicroseconds base storage delay in microseconds
     * @param delayStorageFuzzRangePercent fuzz range for storage delay as a percentage
     * @param delayNetworkMicroseconds base network delay in microseconds
     * @param delayNetworkFuzzRangePercent fuzz range for network delay as a percentage
     */
    public BenchmarkSlowTeachingSynchronizer(
            @NonNull final VirtualMap teacherMap,
            @NonNull final ReconnectConfig reconnectConfig,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent) {
        super(teacherMap, getStaticThreadManager(), reconnectConfig);

        this.randomSeed = randomSeed;
        this.delayStorageMicroseconds = delayStorageMicroseconds;
        this.delayStorageFuzzRangePercent = delayStorageFuzzRangePercent;
        this.delayNetworkMicroseconds = delayNetworkMicroseconds;
        this.delayNetworkFuzzRangePercent = delayNetworkFuzzRangePercent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AsyncOutputStream buildOutputStream(
            @NonNull final DataOutputStream out, @NonNull final ReconnectConfig reconnectConfig) {
        return new BenchmarkSlowAsyncOutputStream(
                out,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                reconnectConfig);
    }
}
