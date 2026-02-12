// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.lag;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import java.util.function.Supplier;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
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
     */
    public BenchmarkSlowTeachingSynchronizer(
            final SerializableDataInputStream in,
            final SerializableDataOutputStream out,
            final TeacherTreeView view,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            final Runnable breakConnection,
            final ReconnectConfig reconnectConfig) {
        super(Time.getCurrent(), getStaticThreadManager(), in, out, view, breakConnection, reconnectConfig);

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
            final StandardWorkGroup workGroup,
            final SerializableDataOutputStream out,
            final Supplier<Boolean> alive,
            final ReconnectConfig reconnectConfig) {
        return new BenchmarkSlowAsyncOutputStream(
                out,
                workGroup,
                alive,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                reconnectConfig);
    }
}
