// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import com.swirlds.base.time.Time;
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

/**
 * Checks the {@link PipelineTimer} on pipelines whose behavior is known. It checks the tool and is not a tracked
 * benchmark.
 *
 * <ul>
 *   <li>{@code direct}: the entry point hands each item to the output at once, on the timer's thread. The pipeline
 *       allocates nothing, so {@code alloc.norm} is what the timer itself allocates per item, and the latencies are the
 *       timer's own overhead.</li>
 *   <li>{@code keepsUp}: two sequential schedulers of the wiring framework, each spinning {@value #STAGE_NANOS} ns per
 *       item, which caps the pipeline at 5,000 items/s, get the items at half that rate. Every latency is at least the
 *       sum of both times: {@code latency.min} is at least {@code 2 × STAGE_NANOS}.</li>
 *   <li>{@code saturated}: the same pipeline gets the items at 1.1 times its capacity, so its latency grows by about
 *       10% of the elapsed time. Every pass must fail the timer's saturation check; the pass is then not recorded.</li>
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

    /** The time each stage of the two-stage pipelines spins per item. */
    private static final long STAGE_NANOS = 200_000;

    /** The capacity of the two-stage pipelines, in items per second. */
    private static final double STAGES_CAPACITY = 1e9 / STAGE_NANOS;

    /** The length of a pass, in seconds of due times. */
    private static final double PASS_SECONDS = 2;

    /** The rate of the {@code direct} pipeline's items, per second. */
    private static final double DIRECT_RATE = 100_000;

    /** The unprocessed tasks a stage may hold before the health monitor counts it as unhealthy. */
    private static final long STAGE_CAPACITY = 500;

    @Param({"direct", "keepsUp", "saturated"})
    public String pipeline;

    private WiringModel model;
    private UncaughtExceptionCollector exceptions;
    private PipelineTimer timer;
    private int items;

    /** The output of the direct pipeline. */
    private Consumer<Object> output;

    /** Whether the latest pass failed the saturation check. */
    private boolean saturated;

    @Setup(Level.Trial)
    public void setUpTrial() {
        exceptions = new UncaughtExceptionCollector();
        model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .withUncaughtExceptionHandler(exceptions)
                .build();
        timer = switch (pipeline) {
            case "direct" -> {
                items = (int) (PASS_SECONDS * DIRECT_RATE);
                yield PipelineTimer.builder(Item.evenlySpaced(items, DIRECT_RATE))
                        .entryPoint(Item.class, item -> item, item -> output.accept(item))
                        .exitPoint((Consumer<Object> consumer) -> output = consumer)
                        .drain(() -> {})
                        .build();
            }
            case "keepsUp" -> stages(0.5);
            case "saturated" -> stages(1.1);
            default -> throw new IllegalArgumentException("Unknown pipeline " + pipeline);
        };
        model.start();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        model.stop();
    }

    @Setup(Level.Invocation)
    public void prepareInvocation() {
        timer.prepare();
        System.gc();
    }

    @Benchmark
    public void replay() {
        saturated = false;
        try {
            timer.replay();
        } catch (final IllegalStateException e) {
            if (!e.getMessage().startsWith("The pipeline did not keep up")) {
                throw e;
            }
            saturated = true;
        }
    }

    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
        if (timer.arrivals() != items) {
            throw new IllegalStateException("Expected " + items + " arrivals, but got " + timer.arrivals());
        }
        final boolean expectSaturated = pipeline.equals("saturated");
        if (saturated != expectSaturated) {
            throw new IllegalStateException(
                    "Expected a pass that " + (expectSaturated ? "fails" : "passes") + " the saturation check");
        }
    }

    /** Builds the timer of two sequential stages that get the items at the given fraction of their capacity. */
    private PipelineTimer stages(final double load) {
        final TaskScheduler<Item> first = stage("first");
        final TaskScheduler<Item> second = stage("second");
        final BindableInputWire<Item, Item> firstInput = first.buildInputWire("items");
        final BindableInputWire<Item, Item> secondInput = second.buildInputWire("items");
        firstInput.bind(PipelineTimerBenchmark::spin);
        secondInput.bind(PipelineTimerBenchmark::spin);
        first.getOutputWire().solderTo(secondInput);
        final double rate = load * STAGES_CAPACITY;
        items = (int) (PASS_SECONDS * rate);
        return PipelineTimer.builder(Item.evenlySpaced(items, rate))
                .entryPoint(Item.class, item -> item, firstInput)
                .exitPoint(second.getOutputWire())
                .drain(() -> {
                    first.flush();
                    second.flush();
                })
                .build();
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
