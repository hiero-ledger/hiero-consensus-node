// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.benchmark.Utils.printVirtualMap;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.network.NetworkSimulationConfig;
import com.swirlds.benchmark.reconnect.network.PairedStreams;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * A utility class to support benchmarks for reconnect.
 */
public class MerkleBenchmarkUtils {

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
        return testSynchronization(startingTree, desiredTree, networkConfig, configuration);
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
        final Metrics metrics = BenchmarkMetrics.getMetrics();

        try (final PairedStreams streams = new PairedStreams(networkConfig)) {
            final LearningSynchronizer learner =
                    new LearningSynchronizer(getStaticThreadManager(), configuration, metrics);
            final TeachingSynchronizer teacher =
                    new TeachingSynchronizer(desiredTree, getStaticThreadManager(), configuration);

            final AtomicReference<VirtualMap> syncMapContainer = new AtomicReference<>();

            try (final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "synchronization-test", streams::disconnect)) {
                workGroup.fork("teaching-synchronizer-main", () -> teachingSynchronizerThread(streams, teacher));
                workGroup.fork(
                        "learning-synchronizer-main",
                        () -> learningSynchronizerThread(streams, startingTree, learner, syncMapContainer));
                workGroup.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MerkleSynchronizationException("Reconnect benchmark was interrupted", e);
            }

            return new ReconnectBenchmarkResult(
                    syncMapContainer.get(),
                    ReconnectMapStatsSnapshot.from(metrics),
                    streams.getTeacherToLearnerStats(),
                    streams.getLearnerToTeacherStats());
        }
    }

    private static void teachingSynchronizerThread(final PairedStreams streams, final TeachingSynchronizer teacher) {
        try {
            teacher.synchronize(streams.getTeacherInput(), streams.getTeacherOutput(), streams::disconnect);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException("Teacher synchronization was interrupted", ex);
        }
    }

    private static void learningSynchronizerThread(
            final PairedStreams streams,
            final VirtualMap startingTree,
            final LearningSynchronizer learner,
            final AtomicReference<VirtualMap> syncMapContainer) {
        try {
            syncMapContainer.set(learner.synchronize(
                    startingTree, streams.getLearnerInput(), streams.getLearnerOutput(), streams::disconnect));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerkleSynchronizationException("Learner synchronization was interrupted", e);
        }
    }
}
