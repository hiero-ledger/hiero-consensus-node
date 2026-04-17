// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

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
     * @param in the input stream for receiving data from the teacher
     * @param out the output stream for sending data to the teacher
     * @param newRoot the root of the virtual tree being reconstructed
     * @param view the learner's view into the merkle tree
     * @param randomSeed seed for the delay fuzzers
     * @param delayStorageMicroseconds base storage delay in microseconds
     * @param delayStorageFuzzRangePercent fuzz range for storage delay as a percentage
     * @param delayNetworkMicroseconds base network delay in microseconds
     * @param delayNetworkFuzzRangePercent fuzz range for network delay as a percentage
     * @param breakConnection a callback to disconnect the connection on failure
     * @param reconnectConfig the reconnect configuration
     */
    public BenchmarkSlowLearningSynchronizer(
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final VirtualMap newRoot,
            @NonNull final LearnerTreeView view,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {

        super(getStaticThreadManager(), in, out, newRoot, view, breakConnection, reconnectConfig);

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
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final DataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new BenchmarkSlowAsyncOutputStream(
                out,
                workGroup,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                reconnectConfig);
    }
}
