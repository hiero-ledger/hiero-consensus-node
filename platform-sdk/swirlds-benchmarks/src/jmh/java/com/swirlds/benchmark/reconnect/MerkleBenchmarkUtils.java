// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.common.merkle.copy.MerkleInitialize.initializeTreeAfterCopy;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.benchmark.BenchmarkMetrics;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowLearningSynchronizer;
import com.swirlds.benchmark.reconnect.lag.BenchmarkSlowTeachingSynchronizer;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility class to support benchmarks for reconnect.
 */
public class MerkleBenchmarkUtils {

    public static MerkleInternal createTreeForMaps(final List<VirtualMap> maps) {
        final BenchmarkMerkleInternal tree = new BenchmarkMerkleInternal("root");
        initializeTreeAfterCopy(tree);
        for (int i = 0; i < maps.size(); i++) {
            tree.setChild(i, maps.get(i));
        }
        tree.reserve();
        return tree;
    }

    public static <T extends MerkleNode> T hashAndTestSynchronization(
            final MerkleNode startingTree,
            final MerkleNode desiredTree,
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

        if (startingTree != null && startingTree.getHash() == null) {
            TestMerkleCryptoFactory.getInstance().digestTreeSync(startingTree);
        }
        if (desiredTree != null && desiredTree.getHash() == null) {
            TestMerkleCryptoFactory.getInstance().digestTreeSync(desiredTree);
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
    private static <T extends MerkleNode> T testSynchronization(
            final MerkleNode startingTree,
            final MerkleNode desiredTree,
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

        try (PairedStreams streams = new PairedStreams(selfId, socketConfig, gossipConfig)) {
            final LearningSynchronizer learner;
            final TeachingSynchronizer teacher;

            if (delayStorageMicroseconds == 0 && delayNetworkMicroseconds == 0) {
                learner = new LearningSynchronizer(
                        getStaticThreadManager(),
                        streams.getLearnerInput(),
                        streams.getLearnerOutput(),
                        startingTree,
                        () -> {
                            try {
                                streams.disconnect();
                            } catch (final IOException e) {
                                // test code, no danger
                                e.printStackTrace();
                            }
                        },
                        TestMerkleCryptoFactory.getInstance(),
                        reconnectConfig,
                        BenchmarkMetrics.getMetrics());
                teacher = new TeachingSynchronizer(
                        configuration,
                        Time.getCurrent(),
                        getStaticThreadManager(),
                        streams.getTeacherInput(),
                        streams.getTeacherOutput(),
                        desiredTree,
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
                        startingTree,
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
                        reconnectConfig,
                        BenchmarkMetrics.getMetrics());
                teacher = new BenchmarkSlowTeachingSynchronizer(
                        configuration,
                        streams.getTeacherInput(),
                        streams.getTeacherOutput(),
                        desiredTree,
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

            final MerkleNode generatedTree = learner.getRoot();
            return (T) generatedTree;
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
