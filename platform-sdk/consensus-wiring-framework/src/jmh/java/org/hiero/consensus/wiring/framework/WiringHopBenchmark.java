// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.pipeline.Item;
import org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer;
import org.hiero.consensus.benchmark.tools.pipeline.UncaughtExceptionCollector;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
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
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Measures the latency of one hop through the wiring framework: the time from the moment an item is due on an input
 * wire until its handler starts. The handler does nothing, so the latency is what the framework adds to every hop of
 * every pipeline.
 *
 * <p>Each scheduler type is configured as most production schedulers of that type are, since the options decide which
 * code runs per item: a counter of the unprocessed items, a timer of the busy fraction, a check for squelching. The
 * capacity does not: without hard backpressure, which production does not use, only the health monitor reads it. The
 * wiring model is built as in production: the health monitor with its heartbeat, a pool of the production size, the
 * production metrics implementation ({@link DefaultPlatformMetrics}, not started), and the system clock.
 *
 * <p>The items are due evenly spaced, at {@link #rate} items per second. Between two items the scheduler's thread has
 * nothing to do and goes to sleep, so every hop includes waking it. The rate sets how long it slept.
 *
 * <p>The warmup iterations run at {@value #WARMUP_RATE} items per second, whatever the rate, so that the JVM compiles
 * the code of the hop. At a few items per second it would stay interpreted for the whole run. On a node, all
 * schedulers of a type share that code, and the busiest one gets it compiled.
 *
 * <p>One invocation, a pass, replays {@value #PASS_SECONDS} s of items, but at least {@value #MIN_ITEMS_PER_PASS}. A
 * {@link PipelineTimer} hands each item to the input wire at its due time and records its latency, which
 * {@link LatencyProfiler} reports: run the benchmark with {@code -prof} and the profiler's class name. After every
 * invocation, every item must have reached the handler.
 */
@State(Scope.Thread)
@Fork(
        value = 2,
        jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class WiringHopBenchmark {

    /** The length of a pass, in seconds of due times. */
    private static final int PASS_SECONDS = 10;

    /**
     * The fewest items of a pass, so that an iteration of a single pass still reports the median:
     * {@link LatencyProfiler} needs 100 latencies above a percentile.
     */
    private static final int MIN_ITEMS_PER_PASS = 210;

    /** The rate of the warmup iterations, in items per second. */
    private static final int WARMUP_RATE = 20_000;

    /** The scheduler type; {@link #configuration()} holds the options of each. */
    @Param({"SEQUENTIAL", "SEQUENTIAL_THREAD", "CONCURRENT", "DIRECT"})
    public String scheduler;

    /** The items per second. */
    @Param({"3", "10", "30", "100", "460", "2000", "6800", "17000"})
    public int rate;

    private ScheduledExecutorService metricsExecutor;
    private ForkJoinPool pool;
    private WiringModel model;
    private UncaughtExceptionCollector exceptions;

    /** Replays the items of a warmup iteration. */
    private PipelineTimer warmupTimer;

    private int warmupItems;

    /** Receives the items a warmup iteration hands to the handler. */
    private Consumer<Item> warmupExit;

    /** Replays the items of a measurement iteration. */
    private PipelineTimer measurementTimer;

    private int measurementItems;

    /** Receives the items a measurement iteration hands to the handler. */
    private Consumer<Item> measurementExit;

    /** The timer of the running iteration. */
    private PipelineTimer timer;

    /** The number of items the timer of the running iteration replays in a pass. */
    private int items;

    /** Receives the items of the running iteration; read by the handler on the scheduler's thread. */
    private volatile Consumer<Item> exit;

    /**
     * Builds the wiring model with one scheduler, and the timers of the warmup and the measurement iterations. This
     * method is called once per trial.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        final Configuration configuration =
                new TestConfigBuilder().withConfigDataType(WiringConfig.class).getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                NodeId.of(0),
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);

        exceptions = new UncaughtExceptionCollector();
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);
        final int parallelism = (int) Math.max(
                1,
                wiringConfig.defaultPoolMultiplier() * Runtime.getRuntime().availableProcessors()
                        + wiringConfig.defaultPoolConstant());
        pool = ExecutorFactory.create("WiringHopBenchmark", exceptions).createForkJoinPool(parallelism);
        model = WiringModelBuilder.create(metrics, Time.getCurrent())
                .withDefaultPool(pool)
                .withWiringConfig(wiringConfig)
                .withUncaughtExceptionHandler(exceptions)
                .build();

        final TaskSchedulerConfiguration schedulerConfiguration = configuration();
        final TaskScheduler<Void> hop = model.<Void>schedulerBuilder("hop")
                .configure(schedulerConfiguration)
                .build();
        final BindableInputWire<Item, Void> input = hop.buildInputWire("items");
        input.bindConsumer(item -> exit.accept(item));
        final Runnable drain = Boolean.TRUE.equals(schedulerConfiguration.flushingEnabled()) ? hop::flush : () -> {};

        warmupItems = PASS_SECONDS / 2 * WARMUP_RATE;
        warmupTimer = PipelineTimer.builder(Item.evenlySpaced(warmupItems, WARMUP_RATE))
                .entryPoint(Item.class, item -> item, input)
                .exitPoint((Consumer<Item> consumer) -> warmupExit = consumer)
                .drain(drain)
                .build();
        measurementItems = Math.max(MIN_ITEMS_PER_PASS, PASS_SECONDS * rate);
        measurementTimer = PipelineTimer.builder(Item.evenlySpaced(measurementItems, rate))
                .entryPoint(Item.class, item -> item, input)
                .exitPoint((Consumer<Item> consumer) -> measurementExit = consumer)
                .drain(drain)
                .build();
        model.start();
    }

    /**
     * Stops the wiring model and its threads. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        model.stop();
        pool.shutdownNow();
        metricsExecutor.shutdownNow();
    }

    /**
     * Selects the timer of the iteration: a warmup iteration replays at {@link #WARMUP_RATE}, a measurement iteration
     * at {@link #rate}. This method is called once per iteration.
     *
     * @param iterationParams tells a warmup iteration from a measurement iteration
     */
    @Setup(Level.Iteration)
    public void selectTimer(final IterationParams iterationParams) {
        if (iterationParams.getType() == IterationType.WARMUP) {
            timer = warmupTimer;
            items = warmupItems;
            exit = warmupExit;
        } else {
            timer = measurementTimer;
            items = measurementItems;
            exit = measurementExit;
        }
    }

    /**
     * Prepares the benchmark invocation. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        timer.prepare();

        // Collect the garbage of the previous invocation now, so that it is not collected during the pass.
        System.gc();
    }

    /**
     * Replays the items of one pass into the input wire, each at its due time.
     */
    @Benchmark
    public void hop() {
        timer.replay();
    }

    /**
     * Checks that the scheduler threw nothing and that every item reached the handler. This method is called once per
     * invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
        if (timer.arrivals() != items) {
            throw new IllegalStateException("Expected " + items + " arrivals, but got " + timer.arrivals());
        }
    }

    /**
     * Returns the configuration of the scheduler, as most production schedulers of its type have it.
     */
    private TaskSchedulerConfiguration configuration() {
        return TaskSchedulerConfiguration.parse(
                switch (scheduler) {
                    case "SEQUENTIAL" -> "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC";
                    case "SEQUENTIAL_THREAD" ->
                        "SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC"
                                + " BUSY_FRACTION_METRIC";
                    case "CONCURRENT" -> "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC";
                    case "DIRECT" -> "DIRECT";
                    case "DIRECT_THREADSAFE" -> "DIRECT_THREADSAFE";
                    default -> throw new IllegalArgumentException("Unknown scheduler type " + scheduler);
                });
    }
}
