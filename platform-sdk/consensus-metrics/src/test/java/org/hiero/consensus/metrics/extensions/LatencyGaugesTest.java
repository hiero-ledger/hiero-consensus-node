// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.config.MetricsConfig_;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * Checks the gauges that {@link LatencyGauges} registers, the parameters they take, and the values they report.
 */
class LatencyGaugesTest {

    private static final String CATEGORY = "test";
    /** The default metrics snapshot interval. */
    private static final Duration INTERVAL = Duration.ofSeconds(3);

    private final FakeTime time = new FakeTime();
    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final Metrics metrics = newMetrics(configuration);
    private final LatencyGauges.Config config =
            new LatencyGauges.Config(CATEGORY, "foo").withDescription("foo latency");

    @Test
    void registersOneGaugePerPercentileAndTheMax() {
        LatencyGauges.register(metrics, configuration, time, config);

        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder("foo_p50", "foo_p90", "foo_p99", "foo_p99_9", "foo_max");
        final Metric gauge = metrics.getMetric(CATEGORY, "foo_p99_9");
        assertThat(gauge).isInstanceOf(FunctionGauge.class);
        assertThat(gauge.getDescription()).isEqualTo("p99.9 of foo latency over the last 60 s");
        assertThat(gauge.getFormat()).isEqualTo("%,10.3f");
        assertThat(metrics.getMetric(CATEGORY, "foo_max").getDescription())
                .isEqualTo("max of foo latency over the last 60 s");
    }

    @Test
    void theDescriptionDefaultsToTheName() {
        LatencyGauges.register(metrics, configuration, time, new LatencyGauges.Config(CATEGORY, "foo"));

        assertThat(metrics.getMetric(CATEGORY, "foo_max").getDescription()).isEqualTo("max of foo over the last 60 s");
    }

    @Test
    void gaugesReportTheRecordedLatenciesInMicroseconds() {
        final LatencyGauges gauges = LatencyGauges.register(metrics, configuration, time, config);
        for (int i = 0; i < 1_000; i++) {
            gauges.record(i < 990 ? 200 : 250);
        }
        time.tick(INTERVAL);

        assertThat(valueOf("foo_p50")).isEqualTo(0.2);
        assertThat(valueOf("foo_p90")).isEqualTo(0.2);
        assertThat(valueOf("foo_p99")).isEqualTo(0.2);
        assertThat(valueOf("foo_p99_9")).isEqualTo(0.25);
        assertThat(valueOf("foo_max")).isEqualTo(0.25);
    }

    @Test
    void parametersOverrideTheDefaults() {
        final LatencyGauges gauges = LatencyGauges.register(
                metrics,
                configuration,
                time,
                config.withPrecisionBits(3)
                        .withRangeBits(20)
                        .withPercentiles(25, 75)
                        .withWindow(Duration.ofSeconds(6))
                        .withSnapshots(1)
                        .withUnit(ChronoUnit.NANOS));

        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder("foo_p25", "foo_p75", "foo_max");
        assertThat(metrics.getMetric(CATEGORY, "foo_max").getDescription())
                .isEqualTo("max of foo latency over the last 6 s");

        gauges.record(1_000);
        gauges.record(2_000_000);
        time.tick(INTERVAL);
        assertThat(valueOf("foo_max"))
                .as("with one snapshot per 6 s window, the first step has not ended")
                .isZero();
        time.tick(INTERVAL);
        assertThat(valueOf("foo_p25"))
                .as("with 3 precision bits, 1,000 ns is in the bucket from 960 to 1,023 ns")
                .isEqualTo(1_023.0);
        assertThat(valueOf("foo_max"))
                .as("with 20 range bits, 2 ms falls in the top bucket, which reports 2^20 - 1 ns")
                .isEqualTo((double) ((1 << 20) - 1));
    }

    @Test
    void withersLeaveTheOriginalUnchanged() {
        final LatencyGauges.Config changed = config.withPrecisionBits(3).withPercentiles(25);

        assertThat(changed.getPrecisionBits()).isEqualTo(3);
        assertThat(changed.getPercentiles()).containsExactly(25.0);
        assertThat(changed.getDescription()).isEqualTo("foo latency");
        assertThat(config.getPrecisionBits()).isEqualTo(7);
        assertThat(config.getRangeBits()).isEqualTo(36);
        assertThat(config.getWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getSnapshots()).isEqualTo(20);
        assertThat(config.getPercentiles()).containsExactly(50.0, 90.0, 99.0, 99.9);
        assertThat(config.getUnit()).isEqualTo(ChronoUnit.MICROS);
    }

    @Test
    void percentilesCanBeGivenAsAList() {
        final List<Double> percentiles = new ArrayList<>(List.of(25.0, 99.99));
        final LatencyGauges.Config changed = config.withPercentiles(percentiles);
        percentiles.add(75.0);

        assertThat(changed.getPercentiles())
                .as("a copy of the list at the time it was set")
                .containsExactly(25.0, 99.99);
        LatencyGauges.register(metrics, configuration, time, changed);
        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .containsExactlyInAnyOrder("foo_p25", "foo_p99_99", "foo_max");
    }

    @Test
    void allGaugesOfARoundReportOnTheSameWindow() {
        final LatencyGauges gauges =
                LatencyGauges.register(metrics, configuration, time, config.withUnit(ChronoUnit.NANOS));
        gauges.record(10);
        time.tick(INTERVAL);
        assertThat(valueOf("foo_p50")).isEqualTo(10);

        gauges.record(200);
        assertThat(valueOf("foo_max")).as("read in the same round as the p50").isEqualTo(10);
    }

    @Test
    void windowShorterThanTheSnapshotIntervalThrows() {
        assertThatThrownBy(() ->
                        LatencyGauges.register(metrics, configuration, time, config.withWindow(Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIntervalOfZeroThrows() {
        final Configuration zeroInterval = new TestConfigBuilder()
                .withValue(MetricsConfig_.CSV_WRITE_FREQUENCY, 0)
                .getOrCreateConfig();
        assertThatThrownBy(() -> LatencyGauges.register(metrics, zeroInterval, time, config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidParametersThrow() {
        assertThatThrownBy(() -> LatencyGauges.register(metrics, configuration, time, config.withPercentiles(50, 100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyGauges.register(metrics, configuration, time, config.withPrecisionBits(17)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyGauges.register(metrics, configuration, time, config.withRangeBits(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LatencyGauges.register(metrics, configuration, time, config.withSnapshots(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(metrics.getAll()).as("no gauge of an invalid configuration").isEmpty();
    }

    private double valueOf(final String name) {
        return (Double) metrics.getMetric(CATEGORY, name).get(ValueType.VALUE);
    }

    private static Metrics newMetrics(final Configuration configuration) {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        return new DefaultPlatformMetrics(
                NodeId.of(1L),
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
