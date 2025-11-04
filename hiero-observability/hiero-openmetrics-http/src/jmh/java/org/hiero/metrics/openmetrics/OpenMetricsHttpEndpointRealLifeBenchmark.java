// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.metrics.openmetrics.scenario.DefaultHttpTestScenario;
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

@State(Scope.Benchmark)
public class OpenMetricsHttpEndpointRealLifeBenchmark {

    private static final Random RANDOM = new Random();

    @Param({"10000"})
    public int dataPointsCount;

    @Param({"4"})
    public int labelsBound;

    @Param({"true"})
    public boolean useGzip;

    private DefaultHttpTestScenario httpTestScenario;

    @Setup(Level.Trial)
    public void setupGlobal() throws IOException {

        httpTestScenario = new DefaultHttpTestScenario();
        httpTestScenario.getFramework().generateAllMetricsTypesDeterministic(dataPointsCount, labelsBound, true);

        System.gc();
    }

    @TearDown(Level.Trial)
    public void tearDownGlobal() {
        httpTestScenario.close();
    }

    @TearDown(Level.Iteration)
    public void exportMetrics() throws IOException, InterruptedException {
        // export metrics
        httpTestScenario.callEndpoint(useGzip);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Fork(
            value = 1,
            jvmArgs = {
                "-XX:+UnlockDiagnosticVMOptions",
                "-Dcom.sun.management.jmxremote",

                // Memory settings
                "-Xmx128m",
                "-Xms64m",
                "-XX:MetaspaceSize=100M",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:+HeapDumpOnOutOfMemoryError",

                // GC settings from CN
                "-XX:+UseZGC",
                "-XX:ZAllocationSpikeTolerance=2",
                "-XX:+ZGenerational"
            })
    @Warmup(iterations = 1, time = 1)
    @Threads(8)
    @Measurement(iterations = 200, time = 3, timeUnit = TimeUnit.SECONDS)
    public void testMemoryLeaks8Threads() throws InterruptedException {
        httpTestScenario.getFramework().updateRandomDataPoints(RANDOM.nextInt(10));
        Thread.sleep(RANDOM.nextLong(50, 100));
    }
}
