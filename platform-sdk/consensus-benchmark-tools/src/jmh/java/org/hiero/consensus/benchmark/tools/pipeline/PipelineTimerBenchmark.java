// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import com.swirlds.base.time.Time;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
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

/**
 * Checks the {@link PipelineTimer} on pipelines whose behavior is known. It checks the tool and is not a tracked
 * benchmark.
 *
 * <ul>
 *   <li>{@code direct}: the entry point hands each item to the output at once, on the timer's thread. The pipeline
 *       allocates nothing, so {@code alloc.norm} is what the timer itself allocates per item, and the latencies are the
 *       timer's own overhead.</li>
 *   <li>{@code stages}: two sequential schedulers of the wiring framework, each spinning {@value #STAGE_NANOS} ns per
 *       item, which caps the pipeline at 5,000 items/s. The items come at {@code load} times that capacity. Every
 *       latency is at least the sum of both times: {@code latency.min} is at least {@code 2 × STAGE_NANOS}.</li>
 * </ul>
 *
 * <p>Profile it with {@code -prof jfr} and {@code jfr-components.sh}: the timer's busy-waiting shows as "timer:
 * waiting", and its output as "timer: output".
 */
@State(Scope.Thread)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class PipelineTimerBenchmark {

    /** The time each stage of the {@code stages} pipeline spins per item. */
    private static final long STAGE_NANOS = 200_000;

    /** The capacity of the {@code stages} pipeline, in items per second. */
    private static final double STAGES_CAPACITY = 1e9 / STAGE_NANOS;

    /** The items of a pass of the {@code stages} pipeline: 2 s at its capacity. */
    private static final int STAGES_ITEMS = 10_000;

    /** The items of a pass of the {@code direct} pipeline, at 100,000 items/s. */
    private static final int DIRECT_ITEMS = 50_000;

    /** The unprocessed tasks a stage may hold before the health monitor counts it as unhealthy. */
    private static final long STAGE_CAPACITY = 500;

    // TEMPORARY (experiment of #27832): only the stages, at loads around their capacity
    @Param({"stages"})
    public String pipeline;

    /** The rate of the {@code stages} pipeline's items, as a fraction of its capacity. */
    // TEMPORARY (experiment of #27832): the loads around the capacity
    @Param({"0.9", "0.95", "1.05", "1.1"})
    public double load;

    private WiringModel model;
    private UncaughtExceptionCollector exceptions;
    private PipelineTimer<Item> timer;
    private int items;

    /** The output of the direct pipeline. */
    private Consumer<Object> output;

    /** TEMPORARY (experiment of #27832). */
    private ExperimentProbe probe;

    @Setup(Level.Trial)
    public void setUpTrial() {
        exceptions = new UncaughtExceptionCollector();
        model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .withHealthMonitorPeriod(Duration.ofMillis(1))
                .withUncaughtExceptionHandler(exceptions)
                .build();
        final List<TaskScheduler<?>> schedulers;
        switch (pipeline) {
            case "direct" -> {
                items = DIRECT_ITEMS;
                schedulers = List.of();
                timer = PipelineTimer.builder(Item.evenlySpaced(items, 100_000))
                        .input(Item.class, item -> item, item -> output.accept(item))
                        .output((Consumer<Object> consumer) -> output = consumer)
                        .drain(() -> {})
                        .experimentSampler(() -> probe.sample())
                        .build();
            }
            case "stages" -> {
                items = STAGES_ITEMS;
                final TaskScheduler<Item> first = stage("first");
                final TaskScheduler<Item> second = stage("second");
                schedulers = List.of(first, second);
                final BindableInputWire<Item, Item> firstInput = first.buildInputWire("items");
                final BindableInputWire<Item, Item> secondInput = second.buildInputWire("items");
                firstInput.bind(PipelineTimerBenchmark::spin);
                secondInput.bind(PipelineTimerBenchmark::spin);
                first.getOutputWire().solderTo(secondInput);
                timer = PipelineTimer.builder(Item.evenlySpaced(items, load * STAGES_CAPACITY))
                        .input(Item.class, item -> item, firstInput)
                        .output(second.getOutputWire())
                        .drain(() -> {
                            first.flush();
                            second.flush();
                        })
                        .experimentSampler(() -> probe.sample())
                        .build();
            }
            default -> throw new IllegalArgumentException("Unknown pipeline " + pipeline);
        }
        probe = new ExperimentProbe(
                "intake=selfcheck-" + pipeline + " speedup=" + load + " windows=-", timer, items, schedulers, model);
        model.start();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        model.stop();
    }

    /** TEMPORARY (experiment of #27832). */
    @Setup(Level.Iteration)
    public void startIteration(final IterationParams params) {
        probe.startIteration(params.getType().name());
    }

    /** TEMPORARY (experiment of #27832). */
    @TearDown(Level.Iteration)
    public void endIteration() {
        probe.endIteration();
    }

    @Setup(Level.Invocation)
    public void prepareInvocation() {
        timer.prepare();
        System.gc();
    }

    @Benchmark
    public void replay() {
        probe.beforePass();
        timer.replay();
        probe.afterPass();
    }

    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
        if (timer.arrivals() != items) {
            throw new IllegalStateException("Expected " + items + " arrivals, but got " + timer.arrivals());
        }
    }

    /** Builds a sequential stage that can be flushed. */
    private TaskScheduler<Item> stage(final String name) {
        return model.<Item>schedulerBuilder(name)
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withUnhandledTaskCapacity(STAGE_CAPACITY)
                .withFlushingEnabled(true)
                .build();
    }

    /** Spins for {@link #STAGE_NANOS} and hands the item on. */
    private static Item spin(final Item item) {
        final long end = System.nanoTime() + STAGE_NANOS;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        return item;
    }
}
