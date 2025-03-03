// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VirtualMapStatisticsTest {

    private static final String LABEL = "VMST";

    private VirtualMapStatistics statistics;
    private Metrics metrics;

    private Metric getMetric(final String section, final String suffix) {
        return getMetric(metrics, "vmap_" + section + suffix);
    }

    private Metric getMetric(final Metrics metrics, final String name) {
        return metrics.getMetric(VirtualMapStatistics.STAT_CATEGORY, name);
    }

    private static void assertValueSet(final Metric metric) {
        assertNotEquals(0.0, metric.get(VALUE));
    }

    private static <T> void assertValueEquals(final Metric metric, final T value) {
        assertEquals(value, metric.get(VALUE));
    }

    @BeforeEach
    void setupTest() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);

        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        statistics = new VirtualMapStatistics(LABEL);
        statistics.registerMetrics(metrics);
    }

    @Test
    void testSetSize() {
        // given
        final Metric metric = getMetric(metrics, "vmap_size_" + LABEL);
        // when
        statistics.setSize(12345678L);
        // then
        assertValueEquals(metric, 12345678L);
    }

    @Test
    void testCountAddedEntities() {
        // given
        final Metric metric = getMetric("queries_", "addedEntities_" + LABEL);
        // when
        statistics.countAddedEntities();
        // then
        assertValueSet(metric);
    }

    @Test
    void testCountUpdatedEntities() {
        // given
        final Metric metric = getMetric("queries_", "updatedEntities_" + LABEL);
        // when
        statistics.countUpdatedEntities();
        // then
        assertValueSet(metric);
    }

    @Test
    void testCountRemovedEntities() {
        // given
        final Metric metric = getMetric("queries_", "removedEntities_" + LABEL);
        // when
        statistics.countRemovedEntities();
        // then
        assertValueSet(metric);
    }

    @Test
    void testCountReadEntities() {
        // given
        final Metric metric = getMetric("queries_", "readEntities_" + LABEL);
        // when
        statistics.countReadEntities();
        // then
        assertValueSet(metric);
    }

    @Test
    void testNodeCacheSize() {
        // given
        final Metric metric = getMetric("lifecycle_", "nodeCacheSizeB_" + LABEL);
        // when
        statistics.setNodeCacheSize(2345L);
        // then
        assertValueEquals(metric, 2345L);
    }

    @Test
    void testPipelineSize() {
        // given
        final Metric metric = getMetric("lifecycle_", "pipelineSize_" + LABEL);
        // when
        statistics.setPipelineSize(23456);
        // then
        assertValueEquals(metric, 23456);
    }

    @Test
    void testFlushBackpressureMs() {
        // given
        final Metric metric = getMetric("lifecycle_", "flushBackpressureMs_" + LABEL);
        // when
        statistics.recordFlushBackpressureMs(34567);
        // then
        assertValueSet(metric);
    }

    @Test
    void testFamilySizeBackpressureMs() {
        // given
        final Metric metric = getMetric("lifecycle_", "familySizeBackpressureMs_" + LABEL);
        // when
        statistics.recordFamilySizeBackpressureMs(4567);
        // then
        assertValueSet(metric);
    }

    @Test
    void testMergeDurationMs() {
        // given
        final Metric metric = getMetric("lifecycle_", "mergeDurationMs_" + LABEL);
        // when
        statistics.recordMerge(45678L);
        // then
        assertValueEquals(metric, 45678L);
    }

    @Test
    void testFlushCountAndDurationMs() {
        // given
        final Metric metricDurationMs = getMetric("lifecycle_", "flushDurationMs_" + LABEL);
        final Metric metricCount = getMetric("lifecycle_", "flushCount_" + LABEL);
        // when
        statistics.recordFlush(5678L);
        // then
        assertValueEquals(metricDurationMs, 5678L);
        assertValueEquals(metricCount, 1L);
    }

    @Test
    void testHashDurationMs() {
        // given
        final Metric metric = getMetric("lifecycle_", "hashDurationMs_" + LABEL);
        // when
        statistics.recordHash(56789L);
        // then
        assertValueEquals(metric, 56789L);
    }
}
