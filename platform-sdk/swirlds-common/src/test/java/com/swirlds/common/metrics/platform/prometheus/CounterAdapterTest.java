// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.GLOBAL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.impl.DefaultCounter;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class CounterAdapterTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String MAPPING_NAME = "CaTeGoRy_NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";

    private static final String[] NODE_LABEL = new String[] {"node"};
    private static final String[] NODE_VALUE = new String[] {"1"};

    private static final double EPSILON = 1e-6;

    @Test
    void testCreateGlobalMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new DefaultCounter(
                new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION).withUnit(UNIT));

        // when
        new CounterAdapter(registry, metric, GLOBAL);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.type).isEqualTo(Collector.Type.COUNTER);
        assertThat(mapping.name).isEqualTo(MAPPING_NAME + "_" + UNIT);
        assertThat(mapping.help).isEqualTo(DESCRIPTION);
        assertThat(mapping.unit).isEqualTo(UNIT);
    }

    @Test
    void testCreatePlatformMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new DefaultCounter(
                new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION).withUnit(UNIT));

        // when
        new CounterAdapter(registry, metric, PLATFORM);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.type).isEqualTo(Collector.Type.COUNTER);
        assertThat(mapping.name).isEqualTo(MAPPING_NAME + "_" + UNIT);
        assertThat(mapping.help).isEqualTo(DESCRIPTION);
        assertThat(mapping.unit).isEqualTo(UNIT);
    }

    @Test
    void testCreateBrokenNamesMetric() {
        // given
        final String brokenName = ".- /%()";
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new DefaultCounter(new Counter.Config(brokenName, brokenName));

        // when
        new CounterAdapter(registry, metric, GLOBAL);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.name)
                .withFailMessage("Adjusting the name and category to Prometheus' requirements failed")
                .isEqualTo(":___per_Percent_:___per_Percent");
    }

    @Test
    void testConstructorWithNullParameters() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION));

        // then
        assertThatThrownBy(() -> new CounterAdapter(null, metric, GLOBAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CounterAdapter(null, metric, PLATFORM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CounterAdapter(registry, null, GLOBAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CounterAdapter(registry, null, PLATFORM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CounterAdapter(registry, metric, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUpdateGlobalMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final DefaultCounter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));
        metric.add(42L);
        final CounterAdapter adapter = new CounterAdapter(registry, metric, GLOBAL);

        // when
        adapter.update(Snapshot.of(metric), null);

        // then
        assertThat(registry.getSampleValue(MAPPING_NAME + "_total")).isCloseTo(42L, offset(EPSILON));
    }

    @Test
    void testUpdatePlatformMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final DefaultCounter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));
        metric.add(42L);
        final CounterAdapter adapter = new CounterAdapter(registry, metric, PLATFORM);

        // when
        adapter.update(Snapshot.of(metric), NodeId.of(1L));

        // then
        assertThat(registry.getSampleValue(MAPPING_NAME + "_total", NODE_LABEL, NODE_VALUE))
                .isCloseTo(42L, offset(EPSILON));
    }

    @Test
    void testUpdateWithNullParameters() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final DefaultCounter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));
        final CounterAdapter adapter = new CounterAdapter(registry, metric, PLATFORM);
        final NodeId nodeId = NodeId.of(1L);

        // then
        assertThatThrownBy(() -> adapter.update(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.update(null, nodeId)).isInstanceOf(NullPointerException.class);
    }
}
