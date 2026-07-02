// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapSyncConfig;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.streams.AsyncOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;

/**
 * A {@link LearningSynchronizer} with simulated delay.
 */
public class BenchmarkSlowLearningSynchronizer extends LearningSynchronizer {

    private final long randomSeed;
    private final long delayStorageMicroseconds;
    private final double delayStorageFuzzRangePercent;
    private final long delayNetworkMicroseconds;
    private final double delayNetworkFuzzRangePercent;

    /**
     * Create a new learning synchronizer with simulated latency.
     *
     * @param configuration the configuration
     * @param metrics metrics
     * @param randomSeed seed for the delay fuzzers
     * @param delayStorageMicroseconds base storage delay in microseconds
     * @param delayStorageFuzzRangePercent fuzz range for storage delay as a percentage
     * @param delayNetworkMicroseconds base network delay in microseconds
     * @param delayNetworkFuzzRangePercent fuzz range for network delay as a percentage
     */
    public BenchmarkSlowLearningSynchronizer(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent) {

        super(configuration, metrics);

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
            @NonNull final DataOutputStream out, @NonNull final VirtualMapSyncConfig syncConfig) {
        return new BenchmarkSlowAsyncOutputStream(
                out,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                syncConfig);
    }
}
