// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.metric;

import static com.hedera.node.app.workflows.handle.metric.UnavailableMetrics.UNAVAILABLE_METRICS;
import static org.junit.jupiter.api.Assertions.*;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnavailableMetricsTest {
    @Mock
    private Metric metric;

    @Mock
    private MetricConfig<?, ?> config;

    @Test
    void noMethodIsSupported() {
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.getMetric("category", "name"));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.findMetricsByCategory("category"));
        assertThrows(UnsupportedOperationException.class, UNAVAILABLE_METRICS::getAll);
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.getOrCreate(config));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.remove("category", "name"));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.remove(metric));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.remove(config));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.addUpdater(() -> {}));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_METRICS.removeUpdater(() -> {}));
        assertThrows(UnsupportedOperationException.class, UNAVAILABLE_METRICS::start);
    }
}
