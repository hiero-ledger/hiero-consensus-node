// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.hiero.metrics.openmetrics.framework.MetricsFramework;
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

/**
 * JMH benchmark comparing OpenMetrics HTTP endpoint latency across different metrics frameworks.
 * <p>
 * Test scenario:
 * <ul>
 *   <li><b>Init phase</b> - Initialize the framework, generate metrics based on provided configuration
 *   and update them to some random initial values.</li>
 *   <li><b>Test phase</b> - Update random metric measurement and call HTTP endpoint in single thread.</li>
 * </ul>
 *
 * <p>Compares:
 * <ul>
 *   <li><b>hiero</b> - Hiero metrics framework (baseline)</li>
 *   <li><b>prometheus</b> - Newer Prometheus Java client</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class FrameworksHttpLatencyComparisonBenchmark {

    /** Whether to request gzip compression from the endpoint. */
    @Param({"true", "false"})
    public boolean useGzip;

    /** Target total number of measurements to create (approximately). */
    @Param({"10000", "50000"})
    public int measurementsCount;

    /** Minimum values cardinality for labeled metrics (inclusive bound). */
    @Param({"10"})
    public int cardinalityLowBound;

    /** Maximum values cardinality for labeled metrics (inclusive bound). */
    @Param({"100"})
    public int cardinalityHighBound;

    /** Framework to benchmark. */
    @Param({"hiero", "prometheus"})
    public String frameworkName;

    // --- State managed by setup/teardown ---
    private TestScenario testScenario;

    @Setup(Level.Trial)
    public void setupTrial() {
        MetricsFramework framework = MetricsFramework.resolve(frameworkName);
        System.out.println("Using framework: " + frameworkName);

        testScenario = new TestScenario(framework, 12345L);
        testScenario.generateMetrics(measurementsCount, cardinalityLowBound, cardinalityHighBound);
        testScenario.updateAllMetricMeasurements(); // warm-up all metrics

        System.gc();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        testScenario.close();
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
    @Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    public void callOpenMetricsEndpoint() throws IOException, InterruptedException {
        // make some updates to metrics
        testScenario.updateRandomMetricMeasurement();
        testScenario.callEndpoint(useGzip);
    }
}
