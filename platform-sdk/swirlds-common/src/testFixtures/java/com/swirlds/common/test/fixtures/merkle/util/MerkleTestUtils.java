// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapMetrics;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapIterator;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Utility methods for testing merkle trees.
 */
public final class MerkleTestUtils {

    private static Metrics createMetrics() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        return new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    private static final Metrics metrics = createMetrics();

    private MerkleTestUtils() {}

    /**
     * Compare two trees for equality.
     */
    public static boolean areVmsEqual(final VirtualMap rootA, final VirtualMap rootB) {
        return rootA.getHash().equals(rootB.getHash());
    }

    /**
     * For every virtual map in the trees and for every virtual key in the given key set, make
     * sure either the map in both trees contains the key, or the map in both trees doesn't
     * contain the key.
     */
    public static boolean checkVirtualMapKeys(
            final VirtualMap rootA, final VirtualMap rootB, final Set<Bytes> virtualKeys) {
        final Iterator<VirtualLeafBytes> iteratorA = new VirtualMapIterator(rootA);
        final Iterator<VirtualLeafBytes> iteratorB = new VirtualMapIterator(rootB);
        while (iteratorA.hasNext()) {
            if (!iteratorB.hasNext()) {
                return false;
            }
            final VirtualLeafBytes a = iteratorA.next();
            final VirtualLeafBytes b = iteratorB.next();

            if (!rootA.getBytes(a.keyBytes()).equals(rootB.getBytes(b.keyBytes()))) {
                return false;
            }
        }

        return true;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Synchronize two trees and verify that the end result is the expected result.
     */
    @SuppressWarnings("unchecked")
    public static VirtualMap testSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final int latencyMilliseconds,
            final ReconnectConfig reconnectConfig)
            throws Exception {
        if (!(startingTree instanceof VirtualMap startingMap)) {
            throw new UnsupportedOperationException("Reconnects are only supported for virtual maps");
        }
        if (!(desiredTree instanceof VirtualMap desiredMap)) {
            throw new UnsupportedOperationException("Reconnects are only supported for virtual maps");
        }
        try (PairedStreams streams = new PairedStreams()) {

            final LearningSynchronizer learner;
            final TeachingSynchronizer teacher;

            final VirtualMap newRoot = startingMap.newReconnectRoot();
            final ReconnectMapStats mapStats = new ReconnectMapMetrics(metrics, null, null);
            final LearnerTreeView learnerView = newRoot.buildLearnerView(reconnectConfig, mapStats);

            if (latencyMilliseconds == 0) {
                learner =
                        new LearningSynchronizer(
                                getStaticThreadManager(),
                                streams.getLearnerInput(),
                                streams.getLearnerOutput(),
                                newRoot,
                                learnerView,
                                streams::disconnect,
                                reconnectConfig) {

                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-learning-synchronizer",
                                        breakConnection,
                                        createSuppressedExceptionListener(reconnectExceptionListener),
                                        true);
                            }
                        };
                teacher =
                        new TeachingSynchronizer(
                                Time.getCurrent(),
                                getStaticThreadManager(),
                                streams.getTeacherInput(),
                                streams.getTeacherOutput(),
                                desiredMap.buildTeacherView(reconnectConfig),
                                streams::disconnect,
                                reconnectConfig) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> exceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-teaching-synchronizer",
                                        breakConnection,
                                        createSuppressedExceptionListener(exceptionListener),
                                        true);
                            }
                        };
            } else {
                learner =
                        new LaggingLearningSynchronizer(
                                streams.getLearnerInput(),
                                streams.getLearnerOutput(),
                                newRoot,
                                learnerView,
                                latencyMilliseconds,
                                streams::disconnect,
                                reconnectConfig) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-learning-synchronizer",
                                        breakConnection,
                                        createSuppressedExceptionListener(reconnectExceptionListener),
                                        true);
                            }
                        };
                teacher =
                        new LaggingTeachingSynchronizer(
                                streams.getTeacherInput(),
                                streams.getTeacherOutput(),
                                desiredMap.buildTeacherView(reconnectConfig),
                                latencyMilliseconds,
                                streams::disconnect,
                                reconnectConfig) {
                            @Override
                            protected StandardWorkGroup createStandardWorkGroup(
                                    ThreadManager threadManager,
                                    Runnable breakConnection,
                                    Function<Throwable, Boolean> reconnectExceptionListener) {
                                return new StandardWorkGroup(
                                        threadManager,
                                        "test-teaching-synchronizer",
                                        breakConnection,
                                        createSuppressedExceptionListener(reconnectExceptionListener),
                                        true);
                            }
                        };
            }

            final AtomicReference<Throwable> firstReconnectException = new AtomicReference<>();
            final Function<Throwable, Boolean> exceptionListener = createSuppressedExceptionListener(t -> {
                firstReconnectException.compareAndSet(null, t);
                return false;
            });
            final StandardWorkGroup workGroup = new StandardWorkGroup(
                    getStaticThreadManager(), "synchronization-test", null, exceptionListener, true);
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

            final VirtualMap generatedTree = newRoot;

            assertReconnectValidity(startingTree, desiredTree, generatedTree);

            return generatedTree;
        }
    }

    private static Set<Bytes> getVirtualKeys(final VirtualMap node) {
        final Set<Bytes> keys = new HashSet<>();
        final Iterator<VirtualLeafBytes> it = new VirtualMapIterator(node);
        while (it.hasNext()) {
            final VirtualLeafBytes leafBytes = it.next();
            keys.add(leafBytes.keyBytes());
        }
        return keys;
    }

    /**
     * Make sure the reconnect was valid.
     *
     * @param startingTree
     * 		the starting state of the learner
     * @param desiredTree
     * 		the state of the teacher
     * @param generatedTree
     * 		the ending state of the learner
     */
    private static void assertReconnectValidity(
            final VirtualMap startingTree, final VirtualMap desiredTree, final VirtualMap generatedTree) {

        // Checks that the trees are equal as merkle structures
        assertTrue(areVmsEqual(generatedTree, desiredTree), "reconnect should produce identical tree");

        final Set<Bytes> allKeys = new HashSet<>();
        allKeys.addAll(getVirtualKeys(startingTree));
        allKeys.addAll(getVirtualKeys(desiredTree));
        // A deeper check at VirtualMap level
        assertTrue(checkVirtualMapKeys(generatedTree, desiredTree, allKeys));

        assertNotSame(startingTree, desiredTree, "trees should be distinct objects");

        assertEquals(1, desiredTree.getReservationCount(), "teacher tree should have a reference count of exactly 1");

        if (startingTree != null) {
            assertTrue(startingTree.isMutable(), "tree should be mutable");
        }
    }

    public static VirtualMap hashAndTestSynchronization(
            final VirtualMap startingTree, final VirtualMap desiredTree, final ReconnectConfig reconnectConfig)
            throws Exception {
        System.out.println("------------");
        System.out.println("starting: " + startingTree);
        System.out.println("desired: " + desiredTree);

        if (startingTree != null) {
            startingTree.getHash(); // calculate hash
        }
        if (desiredTree != null) {
            desiredTree.getHash(); // calculate hash
        }
        return testSynchronization(startingTree, desiredTree, 0, reconnectConfig);
    }

    /**
     * Creates an exception listener that suppresses specific expected exceptions during testing.
     *
     * @param originalListener the original exception listener to delegate to first
     * @return a listener that suppresses expected exceptions
     */
    private static Function<Throwable, Boolean> createSuppressedExceptionListener(
            Function<Throwable, Boolean> originalListener) {
        return t -> {
            boolean handled = originalListener.apply(t);
            if (handled) {
                return true;
            }
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            if (cause instanceof IOException
                    || cause instanceof UncheckedIOException
                    || cause instanceof ExecutionException
                    || cause instanceof MerkleSynchronizationException) {
                return true; // Suppress print/log for simulated
            }
            return false; // Allow print/log for unexpected
        };
    }
}
