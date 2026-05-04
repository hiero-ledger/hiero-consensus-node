// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.benchmark.Utils.printVirtualMap;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.network.NetworkSimulationConfig;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapMetrics;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A utility class to support benchmarks for reconnect.
 */
public class MerkleBenchmarkUtils {

    private static final Logger logger = LogManager.getLogger(MerkleBenchmarkUtils.class);

    public static ReconnectBenchmarkResult hashAndTestSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final NetworkSimulationConfig networkConfig,
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
                networkConfig,
                configuration);
    }

    /**
     * Synchronize two trees and verify that the end result is the expected result.
     */
    private static ReconnectBenchmarkResult testSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final NetworkSimulationConfig networkConfig,
            final Configuration configuration)
            throws Exception {
        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

        final Metrics metrics = BenchmarkMetrics.getMetrics();

        try (PairedStreams streams = new PairedStreams(networkConfig)) {
            final AtomicReconnectMapStats reconnectStats = new AtomicReconnectMapStats();
            final ReconnectMapStats mapStats = new ReconnectMapMetrics(metrics, null, reconnectStats);
            final VirtualMapLearner vmapLearner = new VirtualMapLearner(startingTree, reconnectConfig, mapStats);
            final LearnerTreeView learnerView = vmapLearner.getLearnerView();
            final Runnable disconnect = streams::disconnect;
            final LearningSynchronizer learner = new LearningSynchronizer(
                    getStaticThreadManager(),
                    streams.getLearnerInput(),
                    streams.getLearnerOutput(),
                    learnerView,
                    disconnect,
                    reconnectConfig);
            final TeachingSynchronizer teacher = new TeachingSynchronizer(
                    Time.getCurrent(),
                    getStaticThreadManager(),
                    streams.getTeacherInput(),
                    streams.getTeacherOutput(),
                    desiredTree.buildTeacherView(reconnectConfig),
                    disconnect,
                    reconnectConfig);

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
                vmapLearner.abortOnException();
                throw new MerkleSynchronizationException(
                        "Exception(s) in synchronization test", firstReconnectException.get());
            }

            return new ReconnectBenchmarkResult(
                    vmapLearner.getVirtualMap(),
                    reconnectStats,
                    streams.getTeacherToLearnerStats(),
                    streams.getLearnerToTeacherStats());
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
