// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.platform;

import com.swirlds.base.time.Time;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.statistics.StatsBuffered;
import org.hiero.consensus.metrics.statistics.StatsRunningAverage;
import org.hiero.consensus.metrics.statistics.StatsSpeedometer;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the latency of updating the two metric types that the consensus layer updates on its hot paths, in the
 * steady state that a metric is in for most of a node's life:
 * <ul>
 *   <li>{@link PlatformSpeedometerMetric#cycle()}, backed by {@link StatsSpeedometer}, which counts how often something
 *   happens per second, for example the consensus rounds per second;</li>
 *   <li>{@link PlatformRunningAverageMetric#update(double)}, backed by {@link StatsRunningAverage}, which averages a
 *   value over time, for example the time from an event's creation to its consensus, updated for every consensus
 *   event.</li>
 * </ul>
 *
 * <p>Both metrics average with exponential weights: the last half-life (10 s, the default of
 * {@link MetricsConfig#halfLife()}) carries half of the weight. Both also keep a history of their values for the
 * minimum, maximum, and standard deviation that they report.
 *
 * <h2>Steady state</h2>
 *
 * <p>A new metric runs different code at first. The speedometer averages without weights until half-life / ln 2
 * seconds (14.4 s) have passed, and the history of all values ignores its first 60 s. The benchmark measures the metric
 * after both, when the history of recent values is full as well.
 *
 * <h2>The clock</h2>
 *
 * <p>Every update reads the clock: a speedometer update once, a running-average update three times. What the metric
 * computes depends on the times it reads. With the system clock, the results would differ from pass to pass, and the
 * benchmark would update about a thousand times more often than production does. The metrics therefore read a
 * {@link SteppingTime}, whose time the benchmark advances by a fixed step, {@link #STEP_NANOS}, before each update: the
 * results are the same in every pass, and the output check compares them with constants. Each read of the stepping
 * clock also reads the system clock, as the production clock does, so the measured time includes the cost of the
 * clock reads. Only the times the metric computes with are made up; the code it runs is the code it runs in
 * production. The step hardly matters: on an Apple M4 laptop, steps of 10 µs, 100 µs, and 1 ms gave the same mean
 * time per update within 0.1%, in chunks interleaved in the same run.
 *
 * <h2>Passes</h2>
 *
 * <p>One invocation, a pass, makes {@link #CALLS_PER_PASS} updates of one metric. The metric and its clock are created
 * new for every pass and brought to the steady state outside the measured region, by {@link #FILL_UPDATES} updates
 * {@link #FILL_STEP_NANOS} apart. After the pass, the mean, minimum, maximum, and standard deviation of the metric must
 * equal the expected values exactly.
 *
 * <p>The primary score is the mean time per update. A {@link LatencyRecorder} also times every update, with one
 * timestamp per call ({@link LatencyRecorder#lap()}), so each latency also contains the clock step of the next update.
 * {@link LatencyProfiler} reports the percentiles and the allocation per call.
 *
 * <p>An update takes so little time that the timing is a large part of every latency. On an Apple M4 laptop,
 * {@code lap()} added 17–21 ns to a speedometer update (23–27% of the timed call) and 7–13 ns to a running-average
 * update (6–10%), measured with and without laps in chunks interleaved in the same run; on the benchmark machine it is
 * unmeasured. A CPU profile cannot show this share: reading the clock waits for the preceding instructions to finish,
 * so the profiler charges their time to the clock read. The share is about constant, so a regression of the metrics
 * still shows in full nanoseconds, but as a smaller percentage.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation, so the latency results and
 * {@code latency.mean} exclude garbage collection. JMH's primary score, measured by JMH itself, still includes
 * discarded passes.
 *
 * <p>The benchmark updates each metric from one thread, so the lock in {@link StatsSpeedometer#update(double)} is
 * never contended.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-23, 468541c502</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms1g -Xmx1g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: MetricUpdateBenchmark.runningAverageUpdate / MetricUpdateBenchmark.speedometerCycle</li>
 *   <li>Steady state after: 5 s / 5 s</li>
 *   <li>Noise (latency.mean, CV): 0.41% / 0.70% per 5 s iteration, 0.10% / 0.04% between forks, 0.07% / 0.02% between runs (3 of 3 / 3 of 3 runs quiet)</li>
 *   <li>Expected detectable regression: 0.45% / 0.69% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 28.0 M / 49.0 M; expected stable: mean, p50, p90, p99, p99.99 / mean, p50, p90, p99</li>
 *   <li>Runtime: 0.5 / 0.5 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39942067825, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39942077255, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39942086611</li>
 * </ul>
 */
@Fork(
        value = 2,
        jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 5)
public class MetricUpdateBenchmark {

    /** The number of measured updates in one invocation. */
    private static final int CALLS_PER_PASS = 1_000_000;

    /** The time between two measured updates: 10,000 updates per second. */
    private static final long STEP_NANOS = 100_000L;

    /** The number of updates that bring a new metric to the steady state. */
    private static final int FILL_UPDATES = 1_300;

    /**
     * The time between two updates that bring a new metric to the steady state. {@link #FILL_UPDATES} of them cover
     * 130 s: past the start-up of the speedometer, past the 60 s that the history of all values ignores, and long
     * enough to fill the history of recent values.
     */
    private static final long FILL_STEP_NANOS = 100_000_000L;

    /** The half-life of the metrics, the production default. */
    private static final double HALF_LIFE = new TestConfigBuilder()
            .getOrCreateConfig()
            .getConfigData(MetricsConfig.class)
            .halfLife();

    /**
     * A clock whose time moves only when the benchmark advances it. Each read of {@link #nanoTime()} also reads the
     * system clock, so that it costs about as much as a read of the production clock, which returns
     * {@link System#nanoTime()}.
     */
    public static final class SteppingTime implements Time {

        /** The current time in nanoseconds. */
        private long nanos;

        /**
         * Advances the time.
         *
         * @param stepNanos the nanoseconds to advance by
         */
        public void advance(final long stepNanos) {
            nanos += stepNanos;
        }

        /**
         * Returns the current time. {@code System.nanoTime() >>> 63} is the sign bit of the system clock, which is
         * never negative on Linux and macOS, so it adds 0; the JIT compiler cannot know that, so it keeps the read.
         */
        @Override
        public long nanoTime() {
            return nanos + (System.nanoTime() >>> 63);
        }

        /** The metrics never read it; throws, so that a change that reads it fails the benchmark. */
        @Override
        public long currentTimeMillis() {
            throw new UnsupportedOperationException("The metrics are expected to read only nanoTime()");
        }

        /** The metrics never read it; throws, so that a change that reads it fails the benchmark. */
        @NonNull
        @Override
        public Instant now() {
            throw new UnsupportedOperationException("The metrics are expected to read only nanoTime()");
        }
    }

    /** The state of {@link #speedometerCycle(SpeedometerState)}: a speedometer in the steady state. */
    @State(Scope.Thread)
    public static class SpeedometerState {

        /** The clock of the speedometer. */
        private SteppingTime time;

        /** The speedometer updated in one invocation. */
        private PlatformSpeedometerMetric metric;

        /** The latency recorder for the benchmark. */
        private LatencyRecorder recorder;

        /**
         * Creates a new speedometer and brings it to the steady state. This method is called once per invocation.
         */
        @Setup(Level.Invocation)
        public void prepareInvocation() {
            time = new SteppingTime();
            metric = new PlatformSpeedometerMetric(
                    new SpeedometerMetric.Config("benchmark", "speedometer").withHalfLife(HALF_LIFE), time);
            for (int i = 0; i < FILL_UPDATES; i++) {
                time.advance(FILL_STEP_NANOS);
                metric.cycle();
            }
            recorder = LatencyProfiler.recorder();

            // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during
            // the measured calls.
            System.gc();
        }

        /**
         * Verifies the speedometer after the benchmark invocation. This method is called once per invocation.
         */
        @TearDown(Level.Invocation)
        public void verifyInvocation() {
            verify(
                    metric.getStatsBuffered(),
                    9990.278798032077,
                    9990.193220569383,
                    9990.278798032077,
                    0.0247236685945821);
            time = null;
            metric = null;
            recorder = null;
        }
    }

    /** The state of {@link #runningAverageUpdate(RunningAverageState)}: a running average in the steady state. */
    @State(Scope.Thread)
    public static class RunningAverageState {

        /** The seed of the random number generator. The same seed produces the same values. */
        private static final long SEED = 0;

        /** The number of different values, a power of two. They fit into the first-level cache. */
        private static final int NUMBER_OF_VALUES = 4096;

        /** The values the benchmark passes to the running average, in turn. */
        private double[] values;

        /** The clock of the running average. */
        private SteppingTime time;

        /** The running average updated in one invocation. */
        private PlatformRunningAverageMetric metric;

        /** The latency recorder for the benchmark. */
        private LatencyRecorder recorder;

        /**
         * Generates the values for the benchmark. This method is called once per trial.
         */
        @Setup(Level.Trial)
        public void generateValues() {
            final Random random = new Random(SEED);
            values = new double[NUMBER_OF_VALUES];
            for (int i = 0; i < NUMBER_OF_VALUES; i++) {
                values[i] = random.nextDouble();
            }
        }

        /**
         * Creates a new running average and brings it to the steady state. This method is called once per invocation.
         */
        @Setup(Level.Invocation)
        public void prepareInvocation() {
            time = new SteppingTime();
            metric = new PlatformRunningAverageMetric(
                    new RunningAverageMetric.Config("benchmark", "runningAverage").withHalfLife(HALF_LIFE), time);
            for (int i = 0; i < FILL_UPDATES; i++) {
                time.advance(FILL_STEP_NANOS);
                metric.update(values[i % NUMBER_OF_VALUES]);
            }
            recorder = LatencyProfiler.recorder();

            // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during
            // the measured calls.
            System.gc();
        }

        /**
         * Verifies the running average after the benchmark invocation. This method is called once per invocation.
         */
        @TearDown(Level.Invocation)
        public void verifyInvocation() {
            verify(
                    metric.getStatsBuffered(),
                    0.50244837357714,
                    0.5024402479325283,
                    0.5025394875876215,
                    2.1250720922136385E-5);
            time = null;
            metric = null;
            recorder = null;
        }
    }

    /**
     * Verifies that the metric's mean, minimum, maximum, and standard deviation equal the expected values exactly.
     */
    private static void verify(
            final StatsBuffered stats,
            final double expectedMean,
            final double expectedMin,
            final double expectedMax,
            final double expectedStdDev) {
        if (Double.compare(stats.getMean(), expectedMean) != 0
                || Double.compare(stats.getMin(), expectedMin) != 0
                || Double.compare(stats.getMax(), expectedMax) != 0
                || Double.compare(stats.getStdDev(), expectedStdDev) != 0) {
            throw new IllegalStateException(
                    "Expected mean %s, min %s, max %s, and standard deviation %s, but got %s, %s, %s, and %s"
                            .formatted(
                                    expectedMean,
                                    expectedMin,
                                    expectedMax,
                                    expectedStdDev,
                                    stats.getMean(),
                                    stats.getMin(),
                                    stats.getMax(),
                                    stats.getStdDev()));
        }
    }

    /**
     * Cycles the speedometer {@link #CALLS_PER_PASS} times and records the latency of each call.
     *
     * @param state the speedometer and its clock
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(CALLS_PER_PASS)
    public void speedometerCycle(final SpeedometerState state) {
        final SteppingTime time = state.time;
        final PlatformSpeedometerMetric metric = state.metric;
        final LatencyRecorder recorder = state.recorder;
        recorder.startMeasurement();
        for (int i = 0; i < CALLS_PER_PASS; i++) {
            time.advance(STEP_NANOS);
            metric.cycle();
            recorder.lap();
        }
        recorder.stopMeasurement();
    }

    /**
     * Updates the running average {@link #CALLS_PER_PASS} times and records the latency of each call.
     *
     * @param state the running average, its clock, and the values
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(CALLS_PER_PASS)
    public void runningAverageUpdate(final RunningAverageState state) {
        final double[] values = state.values;
        final SteppingTime time = state.time;
        final PlatformRunningAverageMetric metric = state.metric;
        final LatencyRecorder recorder = state.recorder;
        recorder.startMeasurement();
        for (int i = 0; i < CALLS_PER_PASS; i++) {
            time.advance(STEP_NANOS);
            metric.update(values[i & (RunningAverageState.NUMBER_OF_VALUES - 1)]);
            recorder.lap();
        }
        recorder.stopMeasurement();
    }
}
