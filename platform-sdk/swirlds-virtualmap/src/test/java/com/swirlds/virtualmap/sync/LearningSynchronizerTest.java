// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_VIRTUAL_MAP_CONFIG;
import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link LearningSynchronizer} owns a learner passed to it: if the synchronization fails, the learner is
 * aborted, so that its data source copy is closed.
 */
class LearningSynchronizerTest {

    private LearningSynchronizer synchronizer;
    private PairedStreams streams;

    @BeforeEach
    void setUp() throws IOException {
        synchronizer = new LearningSynchronizer(getStaticThreadManager(), DEFAULT_CONFIGURATION, createMetrics());
        streams = new PairedStreams();
    }

    @AfterEach
    void tearDown() {
        // Not close(): it flushes buffered data, which fails if a test has already closed the sockets
        streams.disconnect();
    }

    @Test
    @DisplayName("Invalid arguments are rejected before the learner's map is copied")
    void invalidArgumentsRejectedBeforeCopy() {
        final VirtualMap originalMap = mock(VirtualMap.class);

        assertThrows(
                NullPointerException.class,
                () -> synchronizer.synchronize(originalMap, null, streams.getLearnerOutput(), () -> {}));

        verifyNoInteractions(originalMap);
    }

    @Test
    @DisplayName("The learner is aborted if the synchronization can't start")
    void learnerAbortedIfSynchronizationCantStart() {
        final VirtualMapLearner learner = mock(VirtualMapLearner.class);
        // Without a virtual map config, the synchronizer can't choose the traversal order
        when(learner.getVirtualMapConfig()).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> synchronizer.synchronize(
                        learner, streams.getLearnerInput(), streams.getLearnerOutput(), streams::disconnect));

        verify(learner).abortOnException();
    }

    @Test
    @DisplayName("The learner is aborted if an error prevents the synchronization from starting")
    void learnerAbortedIfErrorPreventsSynchronization() {
        final VirtualMapLearner learner = mock(VirtualMapLearner.class);
        final AssertionError error = new AssertionError("test error");
        when(learner.getVirtualMapConfig()).thenThrow(error);

        final AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> synchronizer.synchronize(
                        learner, streams.getLearnerInput(), streams.getLearnerOutput(), streams::disconnect));

        assertSame(error, thrown, "the error should be rethrown");
        verify(learner).abortOnException();
    }

    @Test
    @DisplayName("The learner is aborted if the teacher disconnects")
    void learnerAbortedIfTeacherDisconnects() {
        final VirtualMapLearner learner = mock(VirtualMapLearner.class);
        when(learner.getVirtualMapConfig()).thenReturn(DEFAULT_VIRTUAL_MAP_CONFIG);
        streams.disconnectTeacher();

        assertThrows(
                MerkleSynchronizationException.class,
                () -> synchronizer.synchronize(
                        learner, streams.getLearnerInput(), streams.getLearnerOutput(), streams::disconnect));

        verify(learner).abortOnException();
    }

    private static Metrics createMetrics() {
        final MetricsConfig metricsConfig = DEFAULT_CONFIGURATION.getConfigData(MetricsConfig.class);
        return new DefaultPlatformMetrics(
                null,
                new MetricKeyRegistry(),
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
