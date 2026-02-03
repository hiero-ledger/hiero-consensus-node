// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowLearningSynchronizer;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowTeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapMetrics;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A utility class to support benchmarks for reconnect.
 */
public class MerkleBenchmarkUtils {

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
        System.out.println("------------");
        System.out.println("starting: " + startingTree);
        System.out.println("desired: " + desiredTree);

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
    @SuppressWarnings("unchecked")
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

            final VirtualMap newRoot = startingTree.newReconnectRoot();
            final ReconnectMapStats mapStats = new ReconnectMapMetrics(metrics, null, null);
            final LearnerTreeView learnerView = newRoot.buildLearnerView(reconnectConfig, mapStats);

            if (delayStorageMicroseconds == 0 && delayNetworkMicroseconds == 0) {
                learner = new LearningSynchronizer(
                        getStaticThreadManager(),
                        streams.getLearnerInput(),
                        streams.getLearnerOutput(),
                        newRoot,
                        learnerView,
                        () -> {
                            try {
                                streams.disconnect();
                            } catch (final IOException e) {
                                // test code, no danger
                                e.printStackTrace();
                            }
                        },
                        reconnectConfig);
                teacher = new TeachingSynchronizer(
                        Time.getCurrent(),
                        getStaticThreadManager(),
                        streams.getTeacherInput(),
                        streams.getTeacherOutput(),
                        desiredTree.buildTeacherView(reconnectConfig),
                        () -> {
                            try {
                                streams.disconnect();
                            } catch (final IOException e) {
                                // test code, no danger
                                e.printStackTrace();
                            }
                        },
                        reconnectConfig);
            } else {
                learner = new BenchmarkSlowLearningSynchronizer(
                        streams.getLearnerInput(),
                        streams.getLearnerOutput(),
                        newRoot,
                        learnerView,
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent,
                        () -> {
                            try {
                                streams.disconnect();
                            } catch (final IOException e) {
                                // test code, no danger
                                e.printStackTrace();
                            }
                        },
                        reconnectConfig);
                teacher = new BenchmarkSlowTeachingSynchronizer(
                        streams.getTeacherInput(),
                        streams.getTeacherOutput(),
                        desiredTree.buildTeacherView(reconnectConfig),
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent,
                        () -> {
                            try {
                                streams.disconnect();
                            } catch (final IOException e) {
                                // test code, no danger
                                e.printStackTrace();
                            }
                        },
                        reconnectConfig);
            }

            final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
            final Function<Throwable, Boolean> exceptionListener = t -> {
                firstReconnectException.compareAndSet(null, t);
                return false;
            };
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "synchronization-test", null, exceptionListener);
            workGroup.execute("teaching-synchronizer-main", () -> teachingSynchronizerThread(teacher));
            workGroup.execute("learning-synchronizer-main", () -> learningSynchronizerThread(learner));

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

            return newRoot;
        }
    }

    private static void teachingSynchronizerThread(final TeachingSynchronizer teacher) {
        try {
            teacher.synchronize();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void learningSynchronizerThread(final LearningSynchronizer learner) {
        try {
            learner.synchronize();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
