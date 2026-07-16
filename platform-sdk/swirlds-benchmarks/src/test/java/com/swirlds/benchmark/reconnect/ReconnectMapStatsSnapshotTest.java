// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.MetricType;
import com.swirlds.metrics.api.Metrics;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconnectMapStatsSnapshotTest {

    @Test
    void snapshotsTheCurrentReconnectMetricSetWithoutRetiredCounters() {
        final ReconnectMapStatsSnapshot stats = ReconnectMapStatsSnapshot.from(new MapBackedMetrics(Map.of(
                "transfersFromTeacherTotal", 11L,
                "transfersFromLearnerTotal", 12L,
                "internalHashesTotal", 13L,
                "internalCleanHashesTotal", 14L,
                "leafDataTotal", 15L,
                "leafCleanDataTotal", 16L)));

        assertEquals(
                Map.of(
                        "transfersFromTeacherTotal", 11L,
                        "transfersFromLearnerTotal", 12L,
                        "internalHashesTotal", 13L,
                        "internalCleanHashesTotal", 14L,
                        "leafDataTotal", 15L,
                        "leafCleanDataTotal", 16L),
                stats.values());
    }

    @Test
    void snapshotsEveryRegisteredNumericReconnectMetric() {
        final ReconnectMapStatsSnapshot stats = ReconnectMapStatsSnapshot.from(new MapBackedMetrics(Map.of(
                "transfersFromTeacherTotal", 11L,
                "transfersFromLearnerTotal", 12L,
                "internalHashesTotal", 13L,
                "internalCleanHashesTotal", 14L,
                "leafDataTotal", 15L,
                "leafCleanDataTotal", 16L,
                "futureReconnectCounter", 17L)));

        assertEquals(17L, stats.values().get("futureReconnectCounter"));
    }

    @Test
    void formatsAllCountersForBenchmarkLogs() {
        final ReconnectMapStatsSnapshot stats = new ReconnectMapStatsSnapshot(Map.of("zCounter", 2L, "aCounter", 1L));

        assertEquals("ReconnectMapStatsSnapshot: aCounter=1; zCounter=2", stats.format());
    }

    @Test
    void rejectsNonNumericReconnectMetrics() {
        final IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ReconnectMapStatsSnapshot.from(new MapBackedMetrics(Map.of("unexpectedText", "not numeric"))));

        assertEquals("Non-numeric reconnect metric reconnect_vmap.unexpectedText", exception.getMessage());
    }

    private record MapBackedMetrics(Map<String, Object> values) implements Metrics {

        @Override
        public Object getValue(final String category, final String name) {
            assertEquals("reconnect_vmap", category);
            return values.get(name);
        }

        @Override
        public Metric getMetric(final String category, final String name) {
            final Object value = values.get(name);
            return "reconnect_vmap".equals(category) && value != null ? new TestMetric(name, value) : null;
        }

        @Override
        public Collection<Metric> findMetricsByCategory(final String category) {
            return "reconnect_vmap".equals(category)
                    ? values.entrySet().stream()
                            .map(entry -> (Metric) new TestMetric(entry.getKey(), entry.getValue()))
                            .toList()
                    : java.util.List.of();
        }

        @Override
        public Collection<Metric> getAll() {
            return findMetricsByCategory("reconnect_vmap");
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

    private record TestMetric(String name, Object value) implements Metric {

        @Override
        public String getCategory() {
            return "reconnect_vmap";
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "test metric";
        }

        @Override
        public MetricType getMetricType() {
            return MetricType.GAUGE;
        }

        @Override
        public DataType getDataType() {
            return value instanceof Number ? DataType.INT : DataType.STRING;
        }

        @Override
        public String getUnit() {
            return "";
        }

        @Override
        public String getFormat() {
            return "%s";
        }

        @Override
        public EnumSet<ValueType> getValueTypes() {
            return EnumSet.of(ValueType.VALUE);
        }

        @Override
        public Object get(final ValueType valueType) {
            return value;
        }

        @Override
        public void reset() {
            // Not needed by this test.
        }
    }
}
