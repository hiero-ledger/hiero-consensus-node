// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconnectMapStatsSnapshotTest {

    @Test
    void snapshotsGaugeValuesFromReconnectMapMetricsNames() {
        final ReconnectMapStatsSnapshot stats = ReconnectMapStatsSnapshot.from(new MapBackedMetrics(Map.of(
                "transfersFromTeacherTotal", 11L,
                "transfersFromLearnerTotal", 12L,
                "internalHashesTotal", 13L,
                "internalCleanHashesTotal", 14L,
                "internalDataTotal", 15L,
                "internalCleanDataTotal", 16L,
                "leafHashesTotal", 17L,
                "leafCleanHashesTotal", 18L,
                "leafDataTotal", 19L,
                "leafCleanDataTotal", 20L)));

        assertEquals(11L, stats.transfersFromTeacher());
        assertEquals(12L, stats.transfersFromLearner());
        assertEquals(13L, stats.internalHashes());
        assertEquals(14L, stats.internalCleanHashes());
        assertEquals(15L, stats.internalData());
        assertEquals(16L, stats.internalCleanData());
        assertEquals(17L, stats.leafHashes());
        assertEquals(18L, stats.leafCleanHashes());
        assertEquals(19L, stats.leafData());
        assertEquals(20L, stats.leafCleanData());
    }

    @Test
    void formatsAllCountersForBenchmarkLogs() {
        final ReconnectMapStatsSnapshot stats =
                new ReconnectMapStatsSnapshot(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);

        assertEquals(
                "ReconnectMapStatsSnapshot: transfersFromTeacher=11; transfersFromLearner=12; "
                        + "internalHashes=13; internalCleanHashes=14; internalData=15; internalCleanData=16; "
                        + "leafHashes=17; leafCleanHashes=18; leafData=19; leafCleanData=20",
                stats.format());
    }

    private record MapBackedMetrics(Map<String, Object> values) implements Metrics {

        @Override
        public Object getValue(final String category, final String name) {
            assertEquals("reconnect_vmap", category);
            return values.get(name);
        }

        @Override
        public Metric getMetric(final String category, final String name) {
            return null;
        }

        @Override
        public Collection<Metric> findMetricsByCategory(final String category) {
            return java.util.List.of();
        }

        @Override
        public Collection<Metric> getAll() {
            return java.util.List.of();
        }

        @Override
        public <T extends Metric> T getOrCreate(final MetricConfig<T, ?> config) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void remove(final String category, final String name) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void remove(final Metric metric) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void remove(final MetricConfig<?, ?> config) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void addUpdater(final Runnable updater) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void removeUpdater(final Runnable updater) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void start() {
            // Not needed by this test.
        }
    }
}
