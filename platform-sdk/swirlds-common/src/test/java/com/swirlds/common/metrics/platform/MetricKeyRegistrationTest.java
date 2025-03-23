// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.common.metrics.platform.DefaultPlatformMetrics.calculateMetricKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerGauge;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class MetricKeyRegistrationTest {

    private static final NodeId NODE_ID = NodeId.of(1L);
    private static final String METRIC_KEY = calculateMetricKey("CaTeGoRy", "NaMe");

    @Test
    void testAddingGlobalMetric() {
        // given
        final String metricKey1 = calculateMetricKey("CaTeGoRy", "NaMe");
        final String metricKey2 = calculateMetricKey("CaTeGoRy", "OtherName");
        final MetricKeyRegistry registry = new MetricKeyRegistry();

        // when
        final boolean result1 = registry.register(null, METRIC_KEY, Counter.class);
        final boolean result2 = registry.register(null, metricKey1, Counter.class);
        final boolean result3 = registry.register(null, metricKey2, Counter.class);

        // then
        assertThat(result1).isTrue();
        assertThat(result2).isTrue();
        assertThat(result3).isTrue();
    }

    @Test
    void testAddingPlatformMetric() {
        // given
        final String metricKey1 = calculateMetricKey("CaTeGoRy", "NaMe");
        final String metricKey2 = calculateMetricKey("CaTeGoRy", "OtherName");
        final MetricKeyRegistry registry = new MetricKeyRegistry();

        // when
        final boolean result_1_1 = registry.register(NODE_ID, METRIC_KEY, Counter.class);
        final boolean result_1_2 = registry.register(NODE_ID, metricKey1, Counter.class);
        final boolean result_2_1 = registry.register(NODE_ID, metricKey2, Counter.class);

        // then
        assertThat(result_1_1).isTrue();
        assertThat(result_1_2).isTrue();
        assertThat(result_2_1).isTrue();
    }

    @Test
    void testAddingExistingGlobalMetric() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(null, METRIC_KEY, Counter.class);

        // when
        final boolean result = registry.register(null, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingExistingPlatformMetric() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // when
        final boolean result = registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingExistingPlatformMetricForOtherPlatform() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // when
        final boolean result = registry.register(NodeId.of(111L), METRIC_KEY, Counter.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingGlobalMetricWhenPlatformMetricExists() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // then
        final boolean result = registry.register(null, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void testAddingPlatformMetricWhenGlobalMetricExists() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(null, METRIC_KEY, Counter.class);

        // then
        final boolean result = registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void testAddingExistingGlobalMetricWithWrongType() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(null, METRIC_KEY, Counter.class);

        // then
        final boolean result = registry.register(null, METRIC_KEY, IntegerGauge.class);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void testAddingExistingPlatformMetricWithWrongType() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // then
        final boolean result = registry.register(NODE_ID, METRIC_KEY, IntegerGauge.class);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void testAddingGlobalMetricWhenPlatformMetricWasDeleted() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);
        registry.unregister(NODE_ID, METRIC_KEY);

        // then
        final boolean result = registry.register(null, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingPlatformMetricWhenGlobalMetricWasDeleted() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(null, METRIC_KEY, Counter.class);
        registry.unregister(null, METRIC_KEY);

        // then
        final boolean result = registry.register(NODE_ID, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingGlobalMetricWhenOnlyOnePlatformMetricWasDeleted() {
        // given
        final NodeId nodeId2 = NodeId.of(111L);
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);
        registry.register(nodeId2, METRIC_KEY, Counter.class);
        registry.unregister(NODE_ID, METRIC_KEY);

        // then
        final boolean result = registry.register(null, METRIC_KEY, Counter.class);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void testAddingDeletedGlobalMetricWithWrongType() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(null, METRIC_KEY, Counter.class);
        registry.unregister(null, METRIC_KEY);

        // then
        final boolean result = registry.register(null, METRIC_KEY, IntegerGauge.class);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void testAddingDeletedPlatformMetricWithWrongType() {
        // given
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        registry.register(NODE_ID, METRIC_KEY, Counter.class);
        registry.unregister(NODE_ID, METRIC_KEY);

        // then
        final boolean result = registry.register(NODE_ID, METRIC_KEY, IntegerGauge.class);

        // then
        assertThat(result).isTrue();
    }
}
