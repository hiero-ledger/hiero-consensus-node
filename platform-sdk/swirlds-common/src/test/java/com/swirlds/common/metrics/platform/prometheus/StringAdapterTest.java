// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.GLOBAL;
import static com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint.AdapterType.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.platform.PlatformFunctionGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class StringAdapterTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String MAPPING_NAME = CATEGORY + "_" + NAME;
    private static final String DESCRIPTION = "DeScRiPtIoN";

    private static final String[] GLOBAL_LABEL = new String[] {"value"};
    private static final String[] NODE_LABEL = new String[] {"node", "value"};
    private static final String[] NODE_VALUE = new String[] {"1"};

    private static final double EPSILON = 1e-6;

    @Test
    void testCreateGlobalMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World")
                        .withDescription(DESCRIPTION));

        // when
        new StringAdapter(registry, metric, GLOBAL);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.type).isEqualTo(Collector.Type.INFO);
        assertThat(mapping.name).isEqualTo(MAPPING_NAME);
        assertThat(mapping.help).isEqualTo(DESCRIPTION);
        assertThat(mapping.unit).isEmpty();
    }

    @Test
    void testCreatePlatformMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World")
                        .withDescription(DESCRIPTION));

        // when
        new StringAdapter(registry, metric, PLATFORM);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.type).isEqualTo(Collector.Type.INFO);
        assertThat(mapping.name).isEqualTo(MAPPING_NAME);
        assertThat(mapping.help).isEqualTo(DESCRIPTION);
        assertThat(mapping.unit).isEmpty();
    }

    @Test
    void testCreateBrokenNamesMetric() {
        // given
        final String brokenName = ".- /%";
        final CollectorRegistry registry = new CollectorRegistry();
        final Metric metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(brokenName, brokenName, String.class, () -> "Hello World"));

        // when
        new StringAdapter(registry, metric, GLOBAL);

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
        final Metric metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World")
                        .withDescription(DESCRIPTION));

        // then
        assertThatThrownBy(() -> new StringAdapter(null, metric, GLOBAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StringAdapter(null, metric, PLATFORM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StringAdapter(registry, null, GLOBAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StringAdapter(registry, null, PLATFORM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StringAdapter(registry, metric, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUpdateGlobalMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final PlatformFunctionGauge<String> metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World"));
        final StringAdapter adapter = new StringAdapter(registry, metric, GLOBAL);

        // when
        adapter.update(Snapshot.of(metric), null);

        // then
        assertThat(registry.getSampleValue(MAPPING_NAME + "_info", GLOBAL_LABEL, new String[] {"Hello World"}))
                .isCloseTo(1.0, offset(EPSILON));
    }

    @Test
    void testUpdatePlatformMetric() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final PlatformFunctionGauge<String> metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World"));
        final StringAdapter adapter = new StringAdapter(registry, metric, PLATFORM);

        // when
        adapter.update(Snapshot.of(metric), NodeId.of(1L));

        // then
        assertThat(registry.getSampleValue(MAPPING_NAME + "_info", NODE_LABEL, new String[] {"1", "Hello World"}))
                .isCloseTo(1.0, offset(EPSILON));
    }

    @Test
    void testUpdateWithNullParameters() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final PlatformFunctionGauge<String> metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World"));
        final StringAdapter adapter = new StringAdapter(registry, metric, PLATFORM);
        final NodeId nodeId = NodeId.of(1L);

        // then
        assertThatThrownBy(() -> adapter.update(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.update(null, nodeId)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testAdaptedUnitIsEmptyWhenConfigUnitIsSet() {
        // given
        final CollectorRegistry registry = new CollectorRegistry();
        final PlatformFunctionGauge<String> metric = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello World")
                        .withDescription(DESCRIPTION)
                        .withUnit("AnUnIt"));

        // when
        new BooleanAdapter(registry, metric, PLATFORM);

        // then
        final Collector.MetricFamilySamples mapping =
                registry.metricFamilySamples().nextElement();
        assertThat(mapping.type).isEqualTo(Collector.Type.GAUGE);
        assertThat(mapping.name).isEqualTo(MAPPING_NAME);
        assertThat(mapping.help).isEqualTo(DESCRIPTION);
        assertThat(mapping.unit).isEmpty();
    }
}
