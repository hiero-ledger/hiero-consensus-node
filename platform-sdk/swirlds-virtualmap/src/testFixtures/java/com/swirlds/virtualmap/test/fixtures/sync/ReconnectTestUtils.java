// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures.sync;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.assertVmsAreEqual;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.TeachingSynchronizer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Utility methods for testing virtual map reconnects.
 */
public final class ReconnectTestUtils {

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

    private ReconnectTestUtils() {}

    /**
     * Performs synchronization between learner and teacher maps.
     *
     * @param learnerMap leaner map to synchronize with teacher map
     * @param teacherMap teacher map as desired virtual map
     * @param reconnectConfig reconnect config
     * @return resulting map after synchronization
     * @throws Exception if any exception happens during synchronization
     */
    public static VirtualMap testSynchronization(
            final VirtualMap learnerMap, final VirtualMap teacherMap, final ReconnectConfig reconnectConfig)
            throws Exception {
        System.out.println("------------");
        System.out.println("learner map: " + learnerMap.getMetadata());
        System.out.println("teacher map: " + teacherMap.getMetadata());

        assertFalse(teacherMap.isMutable(), "teacher map should be immutable");
        teacherMap.getHash(); // ensure teacher has a hash

        try (PairedStreams streams = new PairedStreams()) {
            final LearningSynchronizer learner =
                    new LearningSynchronizer(getStaticThreadManager(), reconnectConfig, metrics) {

                        @Override
                        protected StandardWorkGroup createStandardWorkGroup(
                                ThreadManager threadManager, Runnable breakConnection) {
                            return new StandardWorkGroup(
                                    threadManager, "test-learning-synchronizer", breakConnection, true);
                        }
                    };

            final TeachingSynchronizer teacher =
                    new TeachingSynchronizer(teacherMap, Time.getCurrent(), getStaticThreadManager(), reconnectConfig) {
                        @Override
                        protected StandardWorkGroup createStandardWorkGroup(
                                @NonNull ThreadManager threadManager, @NonNull Runnable breakConnection) {
                            return new StandardWorkGroup(
                                    threadManager, "test-teaching-synchronizer", breakConnection, true);
                        }
                    };

            final AtomicReference<VirtualMap> syncMapContainer = new AtomicReference<>();
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(
                    getStaticThreadManager(), "synchronization-test", streams::disconnect, true)) {
                workGroup.execute("teaching-synchronizer-main", () -> teachingSynchronizerThread(streams, teacher));
                workGroup.execute(
                        "learning-synchronizer-main",
                        () -> learningSynchronizerThread(streams, learnerMap, learner, syncMapContainer));

                workGroup.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            final VirtualMap syncMap = syncMapContainer.get();
            assertReconnectValidity(learnerMap, teacherMap, syncMap);

            return syncMap;
        }
    }

    /**
     * Asserts reconnect validity.
     *
     * @param learnerMap the starting state of the learner
     * @param teacherMap the state of the teacher
     * @param reconnectMap the ending state of the learner
     */
    public static void assertReconnectValidity(
            final VirtualMap learnerMap, final VirtualMap teacherMap, final VirtualMap reconnectMap) {

        assertNotSame(teacherMap, learnerMap, "leaner map should not be the same instance as teacher map");
        assertNotSame(reconnectMap, learnerMap, "leaner map should not be the same as reconnect map");
        assertNotSame(reconnectMap, teacherMap, "teacher map should not be the same as reconnect map");

        assertTrue(learnerMap.isMutable(), "leaner map should be mutable");
        assertEquals(0, reconnectMap.getFastCopyVersion(), "sync map should be free and has no copies");
        assertEquals(1, teacherMap.getReservationCount(), "teacher map should have a reference count of exactly 1");

        // Checks that the maps are equal as merkle structures
        assertVmsAreEqual(teacherMap, reconnectMap);
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
            final VirtualMap learnerMap,
            final LearningSynchronizer learner,
            AtomicReference<VirtualMap> syncMapContainer) {
        try {
            syncMapContainer.set(learner.synchronize(
                    learnerMap, streams.getLearnerInput(), streams.getLearnerOutput(), streams::disconnect));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
