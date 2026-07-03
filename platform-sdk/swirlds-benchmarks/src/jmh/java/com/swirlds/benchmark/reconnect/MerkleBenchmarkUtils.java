// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.benchmark.Utils.printVirtualMap;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.network.NetworkSimulationConfig;
import com.swirlds.benchmark.reconnect.network.NetworkTransport;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
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
            final NetworkTransport transport,
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
        return testSynchronization(startingTree, desiredTree, networkConfig, transport, configuration);
    }

    /**
     * Synchronize two trees and verify that the end result is the expected result.
     */
    private static ReconnectBenchmarkResult testSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final NetworkSimulationConfig networkConfig,
            final NetworkTransport transport,
            final Configuration configuration)
            throws Exception {
        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

        final Metrics metrics = BenchmarkMetrics.getMetrics();

        try (PairedStreams streams = new PairedStreams(transport, networkConfig, configuration)) {
            streams.getSocketDiagnostics()
                    .ifPresent(diagnostics -> logger.info("Socket transport diagnostics: {}", diagnostics));

            final LearningSynchronizer learner =
                    new LearningSynchronizer(getStaticThreadManager(), reconnectConfig, metrics);
            final TeachingSynchronizer teacher =
                    new TeachingSynchronizer(desiredTree, Time.getCurrent(), getStaticThreadManager(), reconnectConfig);

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
        }
    }
}
