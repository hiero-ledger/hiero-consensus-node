// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.benchmark.Utils.printVirtualMap;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowLearningSynchronizer;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowTeachingSynchronizer;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A utility class to support benchmarks for reconnect.
 */
public class MerkleBenchmarkUtils {

    private static final Logger logger = LogManager.getLogger(MerkleBenchmarkUtils.class);

    public static VirtualMap hashAndTestSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            final NodeId selfId,
            final Configuration configuration)
            throws Exception {
        printVirtualMap("Starting Tree", startingTree);
        printVirtualMap("Desired Tree", desiredTree);

        if (startingTree != null) {
            // calculate hash
            startingTree.getHash();
        }
        if (desiredTree != null) {
            // calculate hash
            desiredTree.getHash();
        }
        return testSynchronization(
                startingTree,
                desiredTree,
                randomSeed,
                delayStorageMicroseconds,
                delayStorageFuzzRangePercent,
                delayNetworkMicroseconds,
                delayNetworkFuzzRangePercent,
                selfId,
                configuration);
    }

    /**
     * Synchronize two trees and verify that the end result is the expected result.
     */
    private static VirtualMap testSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final long randomSeed,
            final long delayStorageMicroseconds,
            final double delayStorageFuzzRangePercent,
            final long delayNetworkMicroseconds,
            final double delayNetworkFuzzRangePercent,
            final NodeId selfId,
            final Configuration configuration)
            throws Exception {
        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        final GossipConfig gossipConfig = configuration.getConfigData(GossipConfig.class);
        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

        final Metrics metrics = BenchmarkMetrics.getMetrics();

        try (PairedStreams streams = new PairedStreams(selfId, socketConfig, gossipConfig)) {
            final LearningSynchronizer learner;
            final TeachingSynchronizer teacher;

            if (delayStorageMicroseconds == 0 && delayNetworkMicroseconds == 0) {
                learner = new LearningSynchronizer(getStaticThreadManager(), reconnectConfig, metrics);
                teacher = new TeachingSynchronizer(
                        desiredTree, Time.getCurrent(), getStaticThreadManager(), reconnectConfig);
            } else {
                learner = new BenchmarkSlowLearningSynchronizer(
                        reconnectConfig,
                        metrics,
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent);
                teacher = new BenchmarkSlowTeachingSynchronizer(
                        desiredTree,
                        reconnectConfig,
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent);
            }

            final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
            final Function<Throwable, Boolean> exceptionListener = t -> {
                firstReconnectException.compareAndSet(null, t);
                return false;
            };

            AtomicReference<VirtualMap> syncMapContainer = new AtomicReference<>();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "synchronization-test", null, exceptionListener);
            workGroup.execute("teaching-synchronizer-main", () -> teachingSynchronizerThread(streams, teacher));
            workGroup.execute(
                    "learning-synchronizer-main",
                    () -> learningSynchronizerThread(streams, startingTree, learner, syncMapContainer));

            try {
                workGroup.waitForTermination();
            } catch (InterruptedException e) {
                workGroup.shutdown();
                Thread.currentThread().interrupt();
            }

            if (workGroup.hasExceptions()) {
                throw new MerkleSynchronizationException(
                        "Exception(s) in synchronization test", firstReconnectException.get());
            }

            return syncMapContainer.get();
        }
    }

    private static void teachingSynchronizerThread(final PairedStreams streams, final TeachingSynchronizer teacher) {
        try {
            teacher.synchronize(streams.getTeacherInput(), streams.getTeacherOutput(), () -> {
                try {
                    streams.disconnect();
                } catch (final IOException e) {
                    // test code, no danger
                    logger.error("Error while shutting down sockets", e);
                }
            });
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void learningSynchronizerThread(
            final PairedStreams streams,
            final VirtualMap startingTree,
            final LearningSynchronizer learner,
            final AtomicReference<VirtualMap> syncMapContainer) {
        try {
            syncMapContainer.set(
                    learner.synchronize(startingTree, streams.getLearnerInput(), streams.getLearnerOutput(), () -> {
                        try {
                            streams.disconnect();
                        } catch (final IOException e) {
                            // test code, no danger
                            logger.error("Error while shutting down sockets", e);
                        }
                    }));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
