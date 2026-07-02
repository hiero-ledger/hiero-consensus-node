// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.benchmark.Utils.printVirtualMap;

import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowLearningSynchronizer;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowTeachingSynchronizer;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

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
            final Configuration configuration)
            throws Exception {
        final Metrics metrics = BenchmarkMetrics.getMetrics();

        try (final PairedStreams streams = new PairedStreams()) {
            final LearningSynchronizer learner;
            final TeachingSynchronizer teacher;

            if (delayStorageMicroseconds == 0 && delayNetworkMicroseconds == 0) {
                learner = new LearningSynchronizer(configuration, metrics);
                teacher = new TeachingSynchronizer(desiredTree, configuration);
            } else {
                learner = new BenchmarkSlowLearningSynchronizer(
                        configuration,
                        metrics,
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent);
                teacher = new BenchmarkSlowTeachingSynchronizer(
                        desiredTree,
                        configuration,
                        randomSeed,
                        delayStorageMicroseconds,
                        delayStorageFuzzRangePercent,
                        delayNetworkMicroseconds,
                        delayNetworkFuzzRangePercent);
            }

            final AtomicReference<VirtualMap> syncMapContainer = new AtomicReference<>();

            try (final StandardWorkGroup workGroup =
                    new StandardWorkGroup("synchronization-test", streams::disconnect)) {
                workGroup.fork("teaching-synchronizer-main", () -> teachingSynchronizerThread(streams, teacher));
                workGroup.fork(
                        "learning-synchronizer-main",
                        () -> learningSynchronizerThread(streams, startingTree, learner, syncMapContainer));
                workGroup.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            return syncMapContainer.get();
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
