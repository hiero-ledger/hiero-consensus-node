// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.MetricsEvent;
import com.swirlds.common.metrics.platform.PlatformDurationGauge;
import com.swirlds.common.metrics.platform.PlatformFunctionGauge;
import com.swirlds.common.metrics.platform.PlatformIntegerPairAccumulator;
import com.swirlds.common.metrics.platform.PlatformRunningAverageMetric;
import com.swirlds.common.metrics.platform.PlatformSpeedometerMetric;
import com.swirlds.common.metrics.platform.PlatformStatEntry;
import com.swirlds.common.metrics.platform.SnapshotEvent;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.impl.DefaultCounter;
import com.swirlds.metrics.impl.DefaultDoubleAccumulator;
import com.swirlds.metrics.impl.DefaultDoubleGauge;
import com.swirlds.metrics.impl.DefaultIntegerAccumulator;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import com.swirlds.metrics.impl.DefaultLongAccumulator;
import com.swirlds.metrics.impl.DefaultLongGauge;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.Info;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrometheusEndpointTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final NodeId NODE_ID_1 = NodeId.of(1L);
    private static final String LABEL_1 = NODE_ID_1.toString();
    private static final NodeId NODE_ID_2 = NodeId.of(2L);
    private static final String LABEL_2 = NODE_ID_2.toString();

    private static final InetSocketAddress ADDRESS = new InetSocketAddress(0);

    private static final double EPSILON = 1e-6;

    @Mock
    private CollectorRegistry registry;

    private HttpServer httpServer;

    private static final MetricsConfig metricsConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(MetricsConfig.class);

    @BeforeEach
    void setup() throws IOException {
        this.httpServer = HttpServer.create(ADDRESS, 1);
    }

    @Test
    void testMethodsWithIllegalParameters() throws IOException {
        // given
        final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer);

        // then
        assertThatThrownBy(() -> new PrometheusEndpoint(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> endpoint.handleMetricsChange(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> endpoint.handleSnapshots(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testFilteringOfTimeMetric() throws IOException {
        // given
        final FunctionGauge<String> time = new PlatformFunctionGauge<>(
                new FunctionGauge.Config<>(Metrics.INFO_CATEGORY, "time", String.class, () -> ""));
        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {
            final MetricsEvent event = new MetricsEvent(MetricsEvent.Type.ADDED, null, time);

            // when
            endpoint.handleMetricsChange(event);

            // then
            verify(registry, never()).register(any());
        }
    }

    @Test
    void testRemoveMetric() throws IOException {
        // given
        final Counter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {
            final MetricsEvent addEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(addEvent);

            // when
            final MetricsEvent removeEvent = new MetricsEvent(MetricsEvent.Type.REMOVED, null, metric);
            endpoint.handleMetricsChange(removeEvent);

            // then
            verify(registry).unregister(any());
        }
    }

    @Test
    void testAddMetricTwice() throws IOException {
        // given
        final Counter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));
        final MetricsEvent event = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {
            endpoint.handleMetricsChange(event);

            // then
            assertThatCode(() -> endpoint.handleMetricsChange(event)).doesNotThrowAnyException();
        }
    }

    @Test
    void addRemoveNonExistingMetric() throws IOException {
        // given
        final Counter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));
        final MetricsEvent event = new MetricsEvent(MetricsEvent.Type.REMOVED, null, metric);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // then
            assertThatCode(() -> endpoint.handleMetricsChange(event)).doesNotThrowAnyException();
        }
    }

    @Test
    void testSnapshotOfUnregisteredMetric() throws IOException {
        // given
        final DefaultCounter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // then
            final SnapshotEvent snapshotEvent = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric)));
            assertThatCode(() -> endpoint.handleSnapshots(snapshotEvent)).doesNotThrowAnyException();
        }
    }

    @Test
    void testGlobalCounter() throws IOException {
        // given
        final DefaultCounter metric = new DefaultCounter(new Counter.Config(CATEGORY, NAME));

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final io.prometheus.client.Counter collector = (io.prometheus.client.Counter) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.add(42L);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(42.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformCounter() throws IOException {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final DefaultCounter metric1 = new DefaultCounter(config);
        final DefaultCounter metric2 = new DefaultCounter(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final io.prometheus.client.Counter collector = (io.prometheus.client.Counter) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.add(3L);
            metric2.add(5L);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(5.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalDoubleAccumulator() throws IOException {
        // given
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME);
        final DefaultDoubleAccumulator metric = new DefaultDoubleAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(Math.PI);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(Math.PI, within(EPSILON));
        }
    }

    @Test
    void testPlatformDoubleAccumulator() throws IOException {
        // given
        final DoubleAccumulator.Config config = new DoubleAccumulator.Config(CATEGORY, NAME);
        final DefaultDoubleAccumulator metric1 = new DefaultDoubleAccumulator(config);
        final DefaultDoubleAccumulator metric2 = new DefaultDoubleAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(Math.PI);
            metric2.update(Math.E);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(Math.PI, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(Math.E, within(EPSILON));
        }
    }

    @Test
    void testGlobalDoubleGauge() throws IOException {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DefaultDoubleGauge metric = new DefaultDoubleGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.set(Math.E);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(Math.E, within(EPSILON));
        }
    }

    @Test
    void testPlatformDoubleGauge() throws IOException {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DefaultDoubleGauge metric1 = new DefaultDoubleGauge(config);
        final DefaultDoubleGauge metric2 = new DefaultDoubleGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.set(Math.PI);
            metric2.set(Math.E);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(Math.PI, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(Math.E, within(EPSILON));
        }
    }

    @Test
    void testGlobalDurationGauge() throws IOException {
        // given
        final DurationGauge.Config config = new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MILLIS);
        final PlatformDurationGauge metric = new PlatformDurationGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.set(Duration.ofSeconds(1L));
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(UnitConstants.SECONDS_TO_MILLISECONDS, within(EPSILON));
        }
    }

    @Test
    void testPlatformDurationGauge() throws IOException {
        // given
        final DurationGauge.Config config = new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MILLIS);
        final PlatformDurationGauge metric1 = new PlatformDurationGauge(config);
        final PlatformDurationGauge metric2 = new PlatformDurationGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.set(Duration.ofNanos(1000L));
            metric2.set(Duration.ofNanos(1L));
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get())
                    .isEqualTo(UnitConstants.MICROSECONDS_TO_MILLISECONDS, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get())
                    .isEqualTo(UnitConstants.NANOSECONDS_TO_MILLISECONDS, within(EPSILON));
        }
    }

    @Test
    void testGlobalBooleanFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<Boolean> config =
                new FunctionGauge.Config<>(CATEGORY, NAME, Boolean.class, () -> true);
        final PlatformFunctionGauge<Boolean> metric = new PlatformFunctionGauge<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(1.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformBooleanFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<Boolean> config1 =
                new FunctionGauge.Config<>(CATEGORY, NAME, Boolean.class, () -> true);
        final PlatformFunctionGauge<Boolean> metric1 = new PlatformFunctionGauge<>(config1);
        final FunctionGauge.Config<Boolean> config2 =
                new FunctionGauge.Config<>(CATEGORY, NAME, Boolean.class, () -> false);
        final PlatformFunctionGauge<Boolean> metric2 = new PlatformFunctionGauge<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalNumberFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<Double> config =
                new FunctionGauge.Config<>(CATEGORY, NAME, Double.class, () -> Math.PI);
        final PlatformFunctionGauge<Double> metric = new PlatformFunctionGauge<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(Math.PI, within(EPSILON));
        }
    }

    @Test
    void testPlatformNumberFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<Double> config1 =
                new FunctionGauge.Config<>(CATEGORY, NAME, Double.class, () -> Math.E);
        final PlatformFunctionGauge<Double> metric1 = new PlatformFunctionGauge<>(config1);
        final FunctionGauge.Config<Double> config2 =
                new FunctionGauge.Config<>(CATEGORY, NAME, Double.class, () -> Math.PI);
        final PlatformFunctionGauge<Double> metric2 = new PlatformFunctionGauge<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(Math.E, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(Math.PI, within(EPSILON));
        }
    }

    @Test
    void testGlobalStringFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<String> config =
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello");
        final PlatformFunctionGauge<String> metric = new PlatformFunctionGauge<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.get()).isEmpty();

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).containsEntry("value", "Hello");
        }
    }

    @Test
    void testPlatformStringFunctionGauge() throws IOException {
        // given
        final FunctionGauge.Config<String> config1 =
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Hello");
        final PlatformFunctionGauge<String> metric1 = new PlatformFunctionGauge<>(config1);
        final FunctionGauge.Config<String> config2 =
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, () -> "Goodbye");
        final PlatformFunctionGauge<String> metric2 = new PlatformFunctionGauge<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEmpty();
            assertThat(collector.labels(LABEL_2).get()).isEmpty();

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).containsEntry("value", "Hello");
            assertThat(collector.labels(LABEL_2).get()).containsEntry("value", "Goodbye");
        }
    }

    @Test
    void testGlobalIntegerAccumulator() throws IOException {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final DefaultIntegerAccumulator metric = new DefaultIntegerAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(42);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(42.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformIntegerAccumulator() throws IOException {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final DefaultIntegerAccumulator metric1 = new DefaultIntegerAccumulator(config);
        final DefaultIntegerAccumulator metric2 = new DefaultIntegerAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(3);
            metric2.update(5);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(5.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalIntegerGauge() throws IOException {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME);
        final DefaultIntegerGauge metric = new DefaultIntegerGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.set(42);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(42.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformIntegerGauge() throws IOException {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME);
        final DefaultIntegerGauge metric1 = new DefaultIntegerGauge(config);
        final DefaultIntegerGauge metric2 = new DefaultIntegerGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.set(3);
            metric2.set(5);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(5.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalBooleanIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<Boolean> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Boolean.class, (a, b) -> a < b);
        final PlatformIntegerPairAccumulator<Boolean> metric = new PlatformIntegerPairAccumulator<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(3, 5);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(1.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformBooleanIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<Boolean> config1 =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Boolean.class, (a, b) -> a < b);
        final PlatformIntegerPairAccumulator<Boolean> metric1 = new PlatformIntegerPairAccumulator<>(config1);
        final IntegerPairAccumulator.Config<Boolean> config2 =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Boolean.class, (a, b) -> a > b);
        final PlatformIntegerPairAccumulator<Boolean> metric2 = new PlatformIntegerPairAccumulator<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(3, 5);
            metric2.update(7, 13);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalNumberIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, (a, b) -> (double) a / b);
        final PlatformIntegerPairAccumulator<Double> metric = new PlatformIntegerPairAccumulator<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(3, 5);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(3.0 / 5.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformNumberIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<Double> config1 =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, (a, b) -> (double) a / b);
        final PlatformIntegerPairAccumulator<Double> metric1 = new PlatformIntegerPairAccumulator<>(config1);
        final IntegerPairAccumulator.Config<Double> config2 =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, (a, b) -> (double) a * b);
        final PlatformIntegerPairAccumulator<Double> metric2 = new PlatformIntegerPairAccumulator<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(3, 5);
            metric2.update(7, 13);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0 / 5.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(7.0 * 13.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalStringIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<String> config = new IntegerPairAccumulator.Config<>(
                CATEGORY, NAME, String.class, (a, b) -> String.format("%d.%d", a, b));
        final PlatformIntegerPairAccumulator<String> metric = new PlatformIntegerPairAccumulator<>(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.get()).isEmpty();

            // when
            metric.update(3, 5);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).containsEntry("value", "3.5");
        }
    }

    @Test
    void testPlatformStringIntegerPairAccumulator() throws IOException {
        // given
        final IntegerPairAccumulator.Config<String> config1 = new IntegerPairAccumulator.Config<>(
                CATEGORY, NAME, String.class, (a, b) -> String.format("%d.%d", a, b));
        final PlatformIntegerPairAccumulator<String> metric1 = new PlatformIntegerPairAccumulator<>(config1);
        final IntegerPairAccumulator.Config<String> config2 = new IntegerPairAccumulator.Config<>(
                CATEGORY, NAME, String.class, (a, b) -> String.format("%d|%d", a, b));
        final PlatformIntegerPairAccumulator<String> metric2 = new PlatformIntegerPairAccumulator<>(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEmpty();
            assertThat(collector.labels(LABEL_2).get()).isEmpty();

            // when
            metric1.update(3, 5);
            metric2.update(7, 13);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).containsEntry("value", "3.5");
            assertThat(collector.labels(LABEL_2).get()).containsEntry("value", "7|13");
        }
    }

    @Test
    void testGlobalLongAccumulator() throws IOException {
        // given
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME);
        final DefaultLongAccumulator metric = new DefaultLongAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(42L);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(42.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformLongAccumulator() throws IOException {
        // given
        final LongAccumulator.Config config = new LongAccumulator.Config(CATEGORY, NAME);
        final DefaultLongAccumulator metric1 = new DefaultLongAccumulator(config);
        final DefaultLongAccumulator metric2 = new DefaultLongAccumulator(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(3L);
            metric2.update(5L);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(5.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalLongGauge() throws IOException {
        // given
        final LongGauge.Config config = new LongGauge.Config(CATEGORY, NAME);
        final DefaultLongGauge metric = new DefaultLongGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.set(42L);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(42.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformLongGauge() throws IOException {
        // given
        final LongGauge.Config config = new LongGauge.Config(CATEGORY, NAME);
        final DefaultLongGauge metric1 = new DefaultLongGauge(config);
        final DefaultLongGauge metric2 = new DefaultLongGauge(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.set(3L);
            metric2.set(5L);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(3.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(5.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalRunningAverageMetric() throws IOException {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final PlatformRunningAverageMetric metric = new PlatformRunningAverageMetric(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels("mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("stddev").get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric.update(1000.0);
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.labels("mean").get()).isEqualTo(1000.0, within(EPSILON));
            assertThat(collector.labels("min").get()).isEqualTo(1000.0, within(EPSILON));
            assertThat(collector.labels("max").get()).isEqualTo(1000.0, within(EPSILON));
            assertThat(collector.labels("stddev").get()).isEqualTo(0.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformRunningAverageMetric() throws IOException {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final PlatformRunningAverageMetric metric1 = new PlatformRunningAverageMetric(config);
        final PlatformRunningAverageMetric metric2 = new PlatformRunningAverageMetric(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1, "mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "stddev").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "stddev").get()).isEqualTo(0.0, within(EPSILON));

            // when
            metric1.update(3000.0);
            metric2.update(5000.0);
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1, "mean").get()).isEqualTo(3000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "min").get()).isEqualTo(3000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "max").get()).isEqualTo(3000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "stddev").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "mean").get()).isEqualTo(5000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "min").get()).isEqualTo(5000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "max").get()).isEqualTo(5000.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "stddev").get()).isEqualTo(0.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalSpeedometerMetric() throws IOException {
        // given
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final FakeTime time = new FakeTime();
        final PlatformSpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels("mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("stddev").get()).isEqualTo(0.0, within(EPSILON));

            // when
            time.set(Duration.ZERO);
            metric.cycle();
            time.set(Duration.ofMillis(500));
            metric.cycle();
            time.set(Duration.ofMillis(1000));
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.labels("mean").get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels("min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels("max").get()).isEqualTo(2.0, within(EPSILON));
            assertThat(collector.labels("stddev").get()).isEqualTo(1.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformSpeedometerMetric() throws IOException {
        // given
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final FakeTime time = new FakeTime();
        final PlatformSpeedometerMetric metric1 = new PlatformSpeedometerMetric(config, time);
        final PlatformSpeedometerMetric metric2 = new PlatformSpeedometerMetric(config, time);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1, "mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "stddev").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "mean").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "max").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "stddev").get()).isEqualTo(0.0, within(EPSILON));

            // when
            time.set(Duration.ZERO);
            metric1.cycle();
            metric2.update(100.0);
            time.set(Duration.ofMillis(500));
            metric1.cycle();
            metric2.update(100.0);
            time.set(Duration.ofMillis(1000));
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1, "mean").get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "max").get()).isEqualTo(2.0, within(EPSILON));
            assertThat(collector.labels(LABEL_1, "stddev").get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "mean").get()).isEqualTo(100.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "min").get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "max").get()).isEqualTo(200.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2, "stddev").get()).isEqualTo(100.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalBooleanStatEntry() throws IOException {
        // given
        final StatEntry.Config<Boolean> config = new StatEntry.Config<>(CATEGORY, NAME, Boolean.class, () -> true);
        final PlatformStatEntry metric = new PlatformStatEntry(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(1.0, within(EPSILON));
        }
    }

    @Test
    void testPlatformBooleanStatEntry() throws IOException {
        // given
        final StatEntry.Config<Boolean> config1 = new StatEntry.Config<>(CATEGORY, NAME, Boolean.class, () -> true);
        final PlatformStatEntry metric1 = new PlatformStatEntry(config1);
        final StatEntry.Config<Boolean> config2 = new StatEntry.Config<>(CATEGORY, NAME, Boolean.class, () -> false);
        final PlatformStatEntry metric2 = new PlatformStatEntry(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(1.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));
        }
    }

    @Test
    void testGlobalNumberStatEntry() throws IOException {
        // given
        final StatEntry.Config<Double> config = new StatEntry.Config<>(CATEGORY, NAME, Double.class, () -> Math.PI);
        final PlatformStatEntry metric = new PlatformStatEntry(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).isEqualTo(Math.PI, within(EPSILON));
        }
    }

    @Test
    void testPlatformNumberStatEntry() throws IOException {
        // given
        final StatEntry.Config<Double> config1 = new StatEntry.Config<>(CATEGORY, NAME, Double.class, () -> Math.E);
        final PlatformStatEntry metric1 = new PlatformStatEntry(config1);
        final StatEntry.Config<Double> config2 = new StatEntry.Config<>(CATEGORY, NAME, Double.class, () -> Math.PI);
        final PlatformStatEntry metric2 = new PlatformStatEntry(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Gauge collector = (Gauge) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(0.0, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(0.0, within(EPSILON));

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).isEqualTo(Math.E, within(EPSILON));
            assertThat(collector.labels(LABEL_2).get()).isEqualTo(Math.PI, within(EPSILON));
        }
    }

    @Test
    void testGlobalStringStatEntry() throws IOException {
        // given
        final StatEntry.Config<String> config = new StatEntry.Config<>(CATEGORY, NAME, String.class, () -> "Hello");
        final PlatformStatEntry metric = new PlatformStatEntry(config);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent = new MetricsEvent(MetricsEvent.Type.ADDED, null, metric);
            endpoint.handleMetricsChange(metricsEvent);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.get()).isEmpty();

            // when
            final SnapshotEvent snapshotEvent = new SnapshotEvent(null, List.of(Snapshot.of(metric)));
            endpoint.handleSnapshots(snapshotEvent);

            // then
            assertThat(collector.get()).containsEntry("value", "Hello");
        }
    }

    @Test
    void testPlatformStringStatEntry() throws IOException {
        // given
        final StatEntry.Config<String> config1 = new StatEntry.Config<>(CATEGORY, NAME, String.class, () -> "Hello");
        final PlatformStatEntry metric1 = new PlatformStatEntry(config1);
        final StatEntry.Config<String> config2 = new StatEntry.Config<>(CATEGORY, NAME, String.class, () -> "Goodbye");
        final PlatformStatEntry metric2 = new PlatformStatEntry(config2);

        try (final PrometheusEndpoint endpoint = new PrometheusEndpoint(httpServer, registry)) {

            // when
            final MetricsEvent metricsEvent1 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_1, metric1);
            endpoint.handleMetricsChange(metricsEvent1);
            final MetricsEvent metricsEvent2 = new MetricsEvent(MetricsEvent.Type.ADDED, NODE_ID_2, metric1);
            endpoint.handleMetricsChange(metricsEvent2);
            final ArgumentCaptor<Collector> captor = ArgumentCaptor.forClass(Collector.class);
            verify(registry).register(captor.capture());
            final Info collector = (Info) captor.getValue();

            // then
            assertThat(collector.labels(LABEL_1).get()).isEmpty();
            assertThat(collector.labels(LABEL_2).get()).isEmpty();

            // when
            final SnapshotEvent snapshotEvent1 = new SnapshotEvent(NODE_ID_1, List.of(Snapshot.of(metric1)));
            endpoint.handleSnapshots(snapshotEvent1);
            final SnapshotEvent snapshotEvent2 = new SnapshotEvent(NODE_ID_2, List.of(Snapshot.of(metric2)));
            endpoint.handleSnapshots(snapshotEvent2);

            // then
            assertThat(collector.labels(LABEL_1).get()).containsEntry("value", "Hello");
            assertThat(collector.labels(LABEL_2).get()).containsEntry("value", "Goodbye");
        }
    }
}
