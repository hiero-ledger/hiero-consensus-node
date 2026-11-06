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
 *       item, get the items at half their capacity. Every latency is at least the sum of both times: {@code latency.min}
 *       is at least {@code 2 × STAGE_NANOS}.</li>
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

    /** The time each stage of the {@code keepsUp} pipeline spins per item, which caps it at 50,000 items/s. */
    private static final long STAGE_NANOS = 20_000;

    /** The items of a pass. */
    private static final int ITEMS = 50_000;

    @Param({"direct", "keepsUp"})
    public String pipeline;

    private WiringModel model;
    private UncaughtExceptionCollector exceptions;
    private PipelineTimer<Item> timer;

    /** The output of the direct pipeline. */
    private Consumer<Object> output;

    @Setup(Level.Trial)
    public void setUpTrial() {
        exceptions = new UncaughtExceptionCollector();
        model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .withUncaughtExceptionHandler(exceptions)
                .build();
        timer = switch (pipeline) {
            case "direct" ->
                PipelineTimer.builder(Item.evenlySpaced(ITEMS, 100_000))
                        .input(Item.class, item -> item, item -> output.accept(item))
                        .output((Consumer<Object> consumer) -> output = consumer)
                        .drain(() -> {})
                        .build();
            case "keepsUp" -> {
                final TaskScheduler<Item> first = stage("first");
                final TaskScheduler<Item> second = stage("second");
                final BindableInputWire<Item, Item> firstInput = first.buildInputWire("items");
                final BindableInputWire<Item, Item> secondInput = second.buildInputWire("items");
                firstInput.bind(PipelineTimerBenchmark::spin);
                secondInput.bind(PipelineTimerBenchmark::spin);
                first.getOutputWire().solderTo(secondInput);
                yield PipelineTimer.builder(Item.evenlySpaced(ITEMS, 0.5e9 / STAGE_NANOS))
                        .input(Item.class, item -> item, firstInput)
                        .output(second.getOutputWire())
                        .drain(() -> {
                            first.flush();
                            second.flush();
                        })
                        .build();
            }
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
        timer.replay();
    }

    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
        if (timer.arrivals() != ITEMS) {
            throw new IllegalStateException("Expected " + ITEMS + " arrivals, but got " + timer.arrivals());
        }
    }

    /** Builds a sequential stage that can be flushed. */
    private TaskScheduler<Item> stage(final String name) {
        return model.<Item>schedulerBuilder(name)
                .withType(TaskSchedulerType.SEQUENTIAL)
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
