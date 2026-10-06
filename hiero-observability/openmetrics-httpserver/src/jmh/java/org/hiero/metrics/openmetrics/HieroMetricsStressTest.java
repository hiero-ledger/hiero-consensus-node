// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.hiero.metrics.openmetrics.framework.HieroMetricsFramework;
import org.hiero.metrics.openmetrics.framework.MetricsFramework;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Stress test that uses JMH framework to simulate productional-like environment, that can be used to analyze memory,
 * but not latency or throughput of the benchmark method ({@link Mode#Throughput} is just used as one of the available modes).
 * <p>
 * Test scenario:
 * <ul>
 *   <li><b>Init phase</b> - Initialize the framework, generate metrics based on provided configuration.</li>
 *   <li><b>Metrics updates</b> - Update random metric measurement in 8 threads (actual benchmark method invocations).</li>
 *   <li><b>Metrics export</b> - Export metrics via HTTP after each iteration (3 seconds).</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class HieroMetricsStressTest {

    /** Whether to request gzip compression from the endpoint. */
    @Param({"true"})
    public boolean useGzip;

    /** Target total number of measurements to create (approximately). */
    @Param({"10000"})
    public int measurementsCount;

    /** Minimum values cardinality for labeled metrics (inclusive bound). */
    @Param({"10"})
    public int cardinalityLowBound;

    /** Maximum values cardinality for labeled metrics (inclusive bound). */
    @Param({"100"})
    public int cardinalityHighBound;

    // --- State managed by setup/teardown ---
    private TestScenario testScenario;

    @Setup(Level.Trial)
    public void setupTrial() {
        MetricsFramework framework = new HieroMetricsFramework();

        testScenario = new TestScenario(framework, 12345L);
        testScenario.generateMetrics(measurementsCount, cardinalityLowBound, cardinalityHighBound);
        testScenario.updateAllMetricMeasurements(); // warm-up all metrics

        System.gc();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        testScenario.close();
    }

    @TearDown(Level.Iteration)
    public void exportMetrics() throws IOException, InterruptedException {
        // export metrics
        testScenario.callEndpoint(useGzip);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
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
    @Threads(8)
    @Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 200, time = 3, timeUnit = TimeUnit.SECONDS)
    public void callOpenMetricsEndpoint() {
        testScenario.updateRandomMetricMeasurement();
    }
}
