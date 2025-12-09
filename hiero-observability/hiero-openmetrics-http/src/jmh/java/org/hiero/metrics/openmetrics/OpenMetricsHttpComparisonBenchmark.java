// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.hiero.metrics.openmetrics.frameworks.PrometheusFramework;
import org.hiero.metrics.openmetrics.frameworks.PrometheusSimpleClientFramework;
import org.hiero.metrics.openmetrics.scenario.AbstractHttpTestScenario;
import org.hiero.metrics.openmetrics.scenario.DefaultHttpTestScenario;
import org.hiero.metrics.openmetrics.scenario.PrometheusSimpleClientTestScenario;
import org.hiero.metrics.openmetrics.scenario.PrometheusTestScenario;
import org.hiero.metrics.test.fixtures.framework.DefaultMetricsFramework;
import org.hiero.metrics.test.fixtures.framework.MetricFramework;
import org.hiero.metrics.test.fixtures.framework.MetricType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
public class OpenMetricsHttpComparisonBenchmark {

    private static final MetricType[] COMMON_METRIC_TYPES = MetricFramework.getCommonSupportedTypes(
            new DefaultMetricsFramework(), new PrometheusFramework(), new PrometheusSimpleClientFramework());

    @Param({"true"})
    public boolean useGzip;

    @Param({"10000"})
    public int measurementsCount;

    @Param({"4"})
    public int labelsBound; // exclusive bound as [0, labelsBound)

    @Param({"default", "prometheus-simpleclient", "prometheus"})
    public String frameworkName;

    private AbstractHttpTestScenario<?> httpTestScenario;

    @Setup(Level.Trial)
    public void setupGlobal() throws IOException {
        switch (frameworkName) {
            case "default" -> httpTestScenario = new DefaultHttpTestScenario();
            case "prometheus-simpleclient" -> httpTestScenario = new PrometheusSimpleClientTestScenario();
            case "prometheus" -> httpTestScenario = new PrometheusTestScenario();
            case null, default -> throw new IllegalArgumentException("unknown metrics framework: " + frameworkName);
        }

        System.out.println("Using framework: " + frameworkName);
        httpTestScenario
                .getFramework()
                .generateAllMetricsTypesDeterministic(measurementsCount, labelsBound, true, COMMON_METRIC_TYPES);
        System.gc();
    }

    @TearDown(Level.Trial)
    public void tearDownGlobal() {
        httpTestScenario.close();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(
            value = 1,
            jvmArgs = {
                // Memory settings
                "-Xmx150m",
                "-Xms100m",
                "-XX:MetaspaceSize=100M",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:+HeapDumpOnOutOfMemoryError",

                // GC settings from CN
                "-XX:+UseZGC",
                "-XX:ZAllocationSpikeTolerance=2",
                "-XX:+ZGenerational"
            })
    @Warmup(iterations = 1, time = 1)
    @Measurement(iterations = 180, time = 1, timeUnit = TimeUnit.SECONDS)
    public void callOpenMetricsEndpoint() throws IOException, InterruptedException {
        // make some updates to metrics
        httpTestScenario.getFramework().updateRandomMeasurements(1);
        httpTestScenario.callEndpoint(useGzip);
    }
}
