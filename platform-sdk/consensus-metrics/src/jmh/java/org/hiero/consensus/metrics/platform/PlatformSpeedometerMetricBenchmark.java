// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.platform;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Test speed of PlatformSpeedometerMetric, which was claimed to be a visible bottleneck in consensus algo
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 5)
public class PlatformSpeedometerMetricBenchmark {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";
    private static final double EPSILON = 1e-6;
    private static final MetricsConfig metricsConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(MetricsConfig.class);

    private PlatformSpeedometerMetric metric;

    @Setup(Level.Trial)
    public void setupTrial() {
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);
        this.metric = new PlatformSpeedometerMetric(config);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(10000)
    public void updateMetric(final Blackhole bh) {
        for (long i = 1_000_000; i < 1_010_000; i++) {
            this.metric.update(i);
        }
        bh.consume(this.metric.get());
        this.metric.reset();
    }
}
