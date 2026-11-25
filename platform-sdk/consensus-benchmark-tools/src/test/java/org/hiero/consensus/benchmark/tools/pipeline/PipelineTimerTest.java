// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
import org.hiero.consensus.wiring.framework.wires.SolderType;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.Result;

class PipelineTimerTest {

    public static final int ITEMS_PER_SECOND = 5_000;

    /** 1,000 items, 200 µs apart. */
    private static final List<Item> ITEMS = Item.evenlySpaced(1_000, ITEMS_PER_SECOND);

    /** How far {@link #tickingClock()} moves each time it is read. */
    private static final long READ_NANOS = 1_000;

    /** How late an input may enter on {@link #tickingClock()}: the few clock reads between its due time and entry. */
    private static final long ENTRY_TOLERANCE_NANOS = 10 * READ_NANOS;

    /** The time between two of {@link #ITEMS}. */
    private static final long SPACING_NANOS = 1_000_000_000L / ITEMS_PER_SECOND;

    /** How long a pipeline on a fake clock holds each item. */
    private static final long HOLD_NANOS = 50_000;

    private LatencyProfiler profiler;

    /** The consumer the timer attached as its output. */
    private Consumer<Object> output;

    @BeforeEach
    void startIteration() {
        profiler = new LatencyProfiler();
        profiler.beforeIteration(null, null);
    }

    @AfterEach
    void endIteration() {
        if (profiler != null) {
            profiler.afterIteration(null, null, null);
        }
    }

    @Test
    void everyInputOfADirectPipelineArrives() {
        final PipelineTimer timer = builder(ITEMS)
                .time(tickingClock())
                .entryPoint(Item.class, item -> item, item -> output.accept(item))
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
        assertThat(timer.saturated()).isFalse();
        assertThat(results())
                .containsEntry("latency.samples", (double) ITEMS.size())
                .containsEntry("measured.saturation.discardedPasses", 0.0);
    }

    @Test
    void latenciesFromOtherThreadsContainTheirDelay() {
        final FakeTime time = tickingClock();
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final PipelineTimer timer = builder(ITEMS)
                    .time(time)
                    .entryPoint(
                            Item.class,
                            item -> item,
                            item -> CompletableFuture.runAsync(
                                            () -> {
                                                time.tick(HOLD_NANOS);
                                                output.accept(item);
                                            },
                                            pool)
                                    .join())
                    .build();

            timer.prepare();
            timer.replay();

            assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
            assertThat(results().get("latency.min")).isGreaterThanOrEqualTo((double) HOLD_NANOS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void valuesFromSeveralThreadsAtOnceAllArrive() {
        // In real time, so that the threads hand values to the output at the same time.
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final List<CompletableFuture<?>> tasks = new ArrayList<>();
            final PipelineTimer timer = PipelineTimer.builder(ITEMS)
                    .entryPoint(
                            Item.class,
                            item -> item,
                            item -> tasks.add(CompletableFuture.runAsync(
                                    () -> {
                                        spin(SPACING_NANOS);
                                        output.accept(item);
                                    },
                                    pool)))
                    .exitPoint(this::attach)
                    .drain(() -> CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0]))
                            .join())
                    .build();

            timer.prepare();
            timer.replay();

            assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theDrainRunsAfterTheLastInputAndBeforeTheLatencies() {
        final Queue<Item> held = new ArrayDeque<>();
        final PipelineTimer timer = PipelineTimer.builder(ITEMS)
                .entryPoint(Item.class, item -> item, held::add)
                .exitPoint(this::attach)
                .drain(() -> {
                    assertThat(held).hasSize(ITEMS.size());
                    held.forEach(output);
                })
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
    }

    @Test
    void valuesThatNeverArriveAreSkipped() {
        final AtomicInteger entered = new AtomicInteger();
        final PipelineTimer timer = builder(ITEMS)
                .time(tickingClock())
                .entryPoint(Item.class, item -> item, item -> {
                    if (entered.getAndIncrement() % 2 == 0) {
                        output.accept(item);
                    }
                })
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.arrivals()).isEqualTo(ITEMS.size() / 2);
        assertThat(results()).containsEntry("latency.samples", (double) ITEMS.size() / 2);
    }

    @Test
    void onlyTheLatenciesOfTheSelectedInputsAreRecorded() {
        final Set<Item> selected = new HashSet<>(ITEMS.subList(0, 100));
        final PipelineTimer timer = PipelineTimer.builder(ITEMS)
                .entryPoint(Item.class, item -> item, item -> {
                    output.accept(item);
                    if (!selected.contains(item)) {
                        // an ignored value may arrive any number of times
                        output.accept(item);
                    }
                })
                .exitPoint(this::attach, selected::contains)
                .drain(() -> {})
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.arrivals()).isEqualTo(100);
    }

    @Test
    void aValueThatArrivesTwiceFails() {
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(Item.class, item -> item, item -> {
                    output.accept(item);
                    output.accept(item);
                })
                .build();

        timer.prepare();
        assertThatThrownBy(timer::replay)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arrived twice")
                .hasMessageContaining("999 more errors");
    }

    @Test
    void aValueThatDidNotEnterInThisPassFails() {
        final List<Fresh> inputs = Fresh.evenlySpaced(10);
        final List<Fresh> firstPass = new ArrayList<>();
        final PipelineTimer timer = PipelineTimer.builder(inputs)
                .entryPoint(Fresh.class, fresh -> fresh, fresh -> {
                    firstPass.add(fresh);
                    output.accept(fresh);
                })
                .exitPoint(this::attach)
                .drain(() -> {})
                .build();
        timer.prepare();
        timer.replay();
        assertThat(timer.arrivals()).isEqualTo(10);

        // in the second pass, the output sees the copies of the first pass
        final PipelineTimer stale = PipelineTimer.builder(inputs)
                .entryPoint(Fresh.class, fresh -> fresh, _ -> output.accept(firstPass.removeFirst()))
                .exitPoint(this::attach)
                .drain(() -> {})
                .build();
        stale.prepare();
        assertThatThrownBy(stale::replay)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not enter the pipeline in this pass");
    }

    @Test
    void aValueThatArrivesOutsideAPassFails() {
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(Item.class, item -> item, item -> output.accept(item))
                .build();
        timer.prepare();
        timer.replay();

        output.accept(ITEMS.getFirst());
        timer.prepare();
        assertThatThrownBy(timer::replay)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a pass");
    }

    @Test
    void eachPassNeedsItsOwnPreparation() {
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(Item.class, item -> item, item -> output.accept(item))
                .build();

        assertThatThrownBy(timer::replay).hasMessageContaining("prepare()");
        assertThatThrownBy(timer::arrivals).hasMessageContaining("No pass");
        timer.prepare();
        timer.replay();
        assertThatThrownBy(timer::replay).hasMessageContaining("prepare()");
    }

    @Test
    void aValueThatTurnsNullFails() {
        final AtomicInteger calls = new AtomicInteger();
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(
                        Item.class, item -> calls.incrementAndGet() > ITEMS.size() + 1 ? null : item, output::accept)
                .build();

        assertThatThrownBy(timer::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value of input 1 is null");
    }

    @Test
    void twoInputsWithTheSameValueFail() {
        final Object shared = new Object();
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(Item.class, _ -> shared, output::accept)
                .build();

        assertThatThrownBy(timer::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inputs 0 and 1 have the same value object");
    }

    @Test
    void inputsWithoutAnEntryPointOrAValueAreNotReplayed() {
        final List<TimedInput> inputs = new ArrayList<>();
        final List<Fresh> fresh = Fresh.evenlySpaced(4);
        for (final Fresh input : fresh) {
            inputs.add(input);
            inputs.add(new Item(input.time()));
        }
        final List<Object> entered = new ArrayList<>();
        final Item skipped = (Item) inputs.get(1);
        final PipelineTimer timer = PipelineTimer.<TimedInput>builder(inputs)
                .entryPoint(Item.class, item -> item == skipped ? null : item, item -> {
                    entered.add(item);
                    output.accept(item);
                })
                .exitPoint(this::attach)
                .drain(() -> {})
                .build();

        timer.prepare();
        timer.replay();

        assertThat(entered).hasSize(3).doesNotContain(skipped).allMatch(Item.class::isInstance);
        assertThat(timer.arrivals()).isEqualTo(3);
    }

    @Test
    void theSpeedupDividesTheTimes() {
        // 101 items over 100 ms, replayed in 25 ms
        final List<Item> items = Item.evenlySpaced(101, 1_000);
        final FakeTime time = tickingClock();
        final List<Long> entries = new ArrayList<>();
        final PipelineTimer timer = PipelineTimer.builder(items)
                .time(time)
                .entryPoint(Item.class, item -> item, item -> {
                    entries.add(time.nanoTime());
                    output.accept(item);
                })
                .exitPoint(this::attach)
                .speedup(4)
                .drain(() -> {})
                .build();

        timer.prepare();
        // the pass starts after this clock read, and the items are due 250 µs apart
        final long beforePass = time.nanoTime();
        timer.replay();

        for (int i = 0; i < items.size(); i++) {
            final long due = i * 250_000L;
            assertThat(entries.get(i) - beforePass).as("item " + i).isBetween(due, due + ENTRY_TOLERANCE_NANOS);
        }
    }

    @Test
    void anInputWireGetsEachValueAsItsSolderTypeSays() {
        final List<Item> items = ITEMS.subList(0, 3);
        for (final SolderType solderType : SolderType.values()) {
            final RecordingInputWire wire = new RecordingInputWire(solderType != SolderType.OFFER);
            final PipelineTimer timer = builder(items)
                    .entryPoint(Item.class, item -> item, wire, solderType)
                    .build();

            timer.prepare();
            timer.replay();

            assertThat(wire.calls).as(solderType.name()).containsOnly(solderType);
            // a value that OFFER drops is not expected
            assertThat(timer.arrivals()).as(solderType.name()).isEqualTo(solderType == SolderType.OFFER ? 0 : 3);
        }
    }

    @Test
    void aWiredPipelineWithItsOutputWire() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        final TaskScheduler<Item> scheduler = model.<Item>schedulerBuilder("echo")
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withFlushingEnabled(true)
                .build();
        final BindableInputWire<Item, Item> input = scheduler.buildInputWire("items");
        input.bind(Function.identity());
        final PipelineTimer timer = PipelineTimer.builder(ITEMS)
                .entryPoint(Item.class, item -> item, input)
                .exitPoint(scheduler.getOutputWire())
                .drain(scheduler::flush)
                .build();
        model.start();
        try {
            timer.prepare();
            timer.replay();
        } finally {
            model.stop();
        }

        assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
    }

    @Test
    void aPassWithAGarbageCollectionIsDiscarded() {
        final AtomicInteger entered = new AtomicInteger();
        final PipelineTimer timer = builder(ITEMS)
                .time(tickingClock())
                .entryPoint(Item.class, item -> item, item -> {
                    if (entered.getAndIncrement() == 0) {
                        System.gc();
                    }
                    output.accept(item);
                })
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
        assertThat(results())
                .doesNotContainKey("latency.samples")
                .containsEntry("measured.gc.discardedPasses", 1.0)
                .containsEntry("measured.saturation.discardedPasses", 0.0);
    }

    @Test
    void aPipelineThatFallsBehindIsDiscardedAndCounted() {
        // a pipeline that needs 300 µs per item gets one every 200 µs: the latency grows by half the elapsed time
        final FakeTime time = tickingClock();
        final PipelineTimer timer = builder(ITEMS)
                .time(time)
                .entryPoint(Item.class, item -> item, item -> {
                    time.tick(300_000);
                    output.accept(item);
                })
                .build();

        timer.prepare();
        timer.replay();

        assertThat(timer.saturated()).isTrue();
        assertThat(timer.arrivals()).isEqualTo(ITEMS.size());
        assertThat(results())
                .doesNotContainKey("latency.samples")
                .containsEntry("measured.saturation.discardedPasses", 1.0);
    }

    @Test
    void theInputsBeforeTheFirstMeasuredOneFillThePipelineAtTheirDueTimes() {
        final Item firstMeasured = ITEMS.get(400);
        final FakeTime time = tickingClock();
        final List<Long> entries = new ArrayList<>();
        final PipelineTimer timer = builder(ITEMS)
                .time(time)
                .entryPoint(Item.class, item -> item, item -> {
                    entries.add(time.nanoTime());
                    output.accept(item);
                })
                .measureFrom(firstMeasured::equals)
                .build();

        timer.prepare();
        // the pass starts after this clock read
        final long beforePass = time.nanoTime();
        timer.replay();

        assertThat(entries).hasSize(ITEMS.size());
        for (int i = 0; i < 400; i++) {
            final long due = i * SPACING_NANOS;
            assertThat(entries.get(i) - beforePass).as("input " + i).isBetween(due, due + ENTRY_TOLERANCE_NANOS);
        }
        assertThat(timer.arrivals()).isEqualTo(600);
        assertThat(results()).containsEntry("latency.samples", 600.0);
    }

    @Test
    void whatTheFillAllocatesIsNotMeasured() {
        // 4 MB in arrays of 1 KB: small enough not to start a garbage collection after the one before the pass
        final int fillArrays = 4_000;
        final List<byte[]> kept = new ArrayList<>();
        final PipelineTimer timer = builder(ITEMS)
                .time(tickingClock())
                .entryPoint(Item.class, item -> item, item -> {
                    if (item.equals(ITEMS.getFirst())) {
                        for (int i = 0; i < fillArrays; i++) {
                            kept.add(new byte[1_000]);
                        }
                    }
                    output.accept(item);
                })
                .measureFrom(ITEMS.get(500)::equals)
                .build();

        timer.prepare();
        System.gc();
        timer.replay();

        assertThat(kept).hasSize(fillArrays);
        final Map<String, Double> results = results();
        assertThat(results).containsEntry("measured.gc.discardedPasses", 0.0);
        assertThat(results.get("alloc.norm") * results.get("latency.samples")).isLessThan(fillArrays * 1_000 / 2.0);
    }

    @Test
    void aPassWithAGarbageCollectionDuringTheFillIsStored() {
        final PipelineTimer timer = builder(ITEMS)
                .time(tickingClock())
                .entryPoint(Item.class, item -> item, item -> {
                    if (item.equals(ITEMS.getFirst())) {
                        System.gc();
                    }
                    output.accept(item);
                })
                .measureFrom(ITEMS.get(500)::equals)
                .build();

        timer.prepare();
        timer.replay();

        assertThat(results()).containsEntry("latency.samples", 500.0).containsEntry("measured.gc.discardedPasses", 0.0);
    }

    @Test
    void aFillValueThatLeavesDuringTheMeasuredPartIsIgnored() {
        final Item firstMeasured = ITEMS.get(500);
        final Queue<Item> held = new ArrayDeque<>();
        final PipelineTimer timer = builder(ITEMS)
                .entryPoint(Item.class, item -> item, item -> {
                    if (item.equals(ITEMS.getFirst())) {
                        // held until the first measured input arrives
                        held.add(item);
                        return;
                    }
                    if (item.equals(firstMeasured)) {
                        held.forEach(output);
                    }
                    output.accept(item);
                })
                .measureFrom(firstMeasured::equals)
                .build();

        timer.prepare();
        timer.replay();

        assertThat(held).hasSize(1);
        assertThat(timer.arrivals()).isEqualTo(500);
    }

    @Test
    void theLatencyIsMeasuredFromTheDueTime() {
        final FakeTime time = new FakeTime(Instant.EPOCH, Duration.ZERO);
        final PipelineTimer timer = builder(ITEMS)
                .time(time)
                .entryPoint(Item.class, item -> item, heldFor(time, HOLD_NANOS, new ArrayList<>()))
                .build();

        timer.prepare();
        timer.replay();

        assertThat(results())
                .containsEntry("latency.samples", (double) ITEMS.size())
                .containsEntry("latency.min", (double) HOLD_NANOS)
                .containsEntry("latency.max", (double) HOLD_NANOS);
    }

    @Test
    void aFillKeepsTheRecordedTimesOfTheMeasuredPart() {
        final FakeTime time = new FakeTime(Instant.EPOCH, Duration.ZERO);
        final List<Long> entries = new ArrayList<>();
        final PipelineTimer timer = builder(ITEMS)
                .time(time)
                .entryPoint(Item.class, item -> item, heldFor(time, HOLD_NANOS, entries))
                .measureFrom(ITEMS.get(500)::equals)
                .build();

        timer.prepare();
        timer.replay();

        // input 500 is due 500 spacings after the start of the pass, which is at 0 on the fake clock
        assertThat(entries.get(500)).isEqualTo(500 * SPACING_NANOS);
        assertThat(results())
                .containsEntry("latency.samples", 500.0)
                .containsEntry("latency.min", (double) HOLD_NANOS)
                .containsEntry("latency.max", (double) HOLD_NANOS);
    }

    @Test
    void aMeasurementThatStartsLateShiftsTheMeasuredPart() {
        final FakeTime time = new FakeTime(Instant.EPOCH, Duration.ZERO);
        final long late = 1_000_000;
        final List<Long> entries = new ArrayList<>();
        final Consumer<Item> held = heldFor(time, HOLD_NANOS, entries);
        final PipelineTimer timer = builder(ITEMS)
                .time(time)
                .entryPoint(Item.class, item -> item, item -> {
                    held.accept(item);
                    if (item.equals(ITEMS.get(499))) {
                        // the clock passes the first measured input's due time, as a slow startPass() would
                        time.tick(late);
                    }
                })
                .measureFrom(ITEMS.get(500)::equals)
                .build();

        timer.prepare();
        timer.replay();

        assertThat(entries.get(500)).isEqualTo(500 * SPACING_NANOS + late);
        assertThat(results())
                .containsEntry("latency.samples", 500.0)
                .containsEntry("latency.min", (double) HOLD_NANOS)
                .containsEntry("latency.max", (double) HOLD_NANOS);
    }

    /**
     * A clock that moves on by {@link #READ_NANOS} each time it is read. The timer reads it while it waits for an input's
     * due time, so the wait ends without real time passing, and a stall of the machine cannot delay an input and make
     * the pass look saturated.
     */
    @NonNull
    private static FakeTime tickingClock() {
        return new FakeTime(Instant.EPOCH, Duration.ofNanos(READ_NANOS));
    }

    /**
     * A pipeline on a fake clock: notes the time each item enters, holds it for the given time, hands it to the output,
     * and then moves the clock on to the next item's due time.
     */
    @NonNull
    private Consumer<Item> heldFor(
            @NonNull final FakeTime time, final long holdNanos, @NonNull final List<Long> entries) {
        return item -> {
            entries.add(time.nanoTime());
            time.tick(holdNanos);
            output.accept(item);
            time.tick(SPACING_NANOS - holdNanos);
        };
    }

    @Test
    void theLatencyGrowthIsTheRateAtWhichTheLatencyGrows() {
        // 1,001 inputs over 1 s: 250 in the first quarter, 251 in the last
        final long[] due = new long[1_001];
        for (int i = 0; i < due.length; i++) {
            due[i] = i * 1_000_000L;
        }

        assertThat(PipelineTimer.latencyGrowth(due, latencies(due, t -> 50_000), 0))
                .isZero();
        // within the growth over one input's spacing: a quarter with an even count takes the upper of its middle values
        assertThat(PipelineTimer.latencyGrowth(due, latencies(due, t -> 50_000 + t / 20), 0))
                .isCloseTo(0.05, within(1e-4));
        assertThat(PipelineTimer.latencyGrowth(due, latencies(due, t -> 50_000_000 - t / 50), 0))
                .isCloseTo(-0.02, within(1e-4));
        // a burst in the middle of the pass is no growth
        assertThat(PipelineTimer.latencyGrowth(
                        due, latencies(due, t -> t > 400_000_000 && t < 600_000_000 ? 10_000_000 : 50_000), 0))
                .isZero();
    }

    @Test
    void theLatencyGrowthIsThatOfTheMeasuredPart() {
        // 2,001 inputs over 2 s; the first 1,000 fill the pipeline and have no latency
        final long[] due = new long[2_001];
        for (int i = 0; i < due.length; i++) {
            due[i] = i * 1_000_000L;
        }
        final long[] latencies = latencies(due, t -> t < 1_000_000_000 ? -1 : 50_000 + (t - 1_000_000_000) / 20);

        assertThat(PipelineTimer.latencyGrowth(due, latencies, 1_000)).isCloseTo(0.05, within(1e-4));
        // over the whole pass, the first quarter has no latencies
        assertThat(PipelineTimer.latencyGrowth(due, latencies, 0)).isNaN();
    }

    @Test
    void theLatencyGrowthNeedsAPassWithEnoughLatenciesInBothQuarters() {
        final long[] due = new long[1_001];
        for (int i = 0; i < due.length; i++) {
            due[i] = i * 1_000_000L;
        }
        // only the inputs of the first 150 ms have a latency: 150 in the first quarter, none in the last
        assertThat(PipelineTimer.latencyGrowth(due, latencies(due, t -> t < 150_000_000 ? 50_000 : -1), 0))
                .isNaN();
        // 101 inputs: 25 in the first quarter
        assertThat(PipelineTimer.latencyGrowth(Arrays.copyOf(due, 101), new long[101], 0))
                .isNaN();
        // no length
        assertThat(PipelineTimer.latencyGrowth(new long[1_000], new long[1_000], 0))
                .isNaN();
        assertThat(PipelineTimer.latencyGrowth(new long[0], new long[0], 0)).isNaN();
    }

    /** Computes a latency for every due time. */
    @NonNull
    private static long[] latencies(@NonNull final long[] due, @NonNull final LongUnaryOperator latencyAt) {
        final long[] latencies = new long[due.length];
        for (int i = 0; i < due.length; i++) {
            latencies[i] = latencyAt.applyAsLong(due[i]);
        }
        return latencies;
    }

    @Test
    void theBuilderChecksItsConfiguration() {
        assertThatThrownBy(() -> PipelineTimer.builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .drain(() -> {})
                        .build())
                .hasMessageContaining("No exit point");
        assertThatThrownBy(() -> PipelineTimer.builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .exitPoint(this::attach)
                        .build())
                .hasMessageContaining("No drain");
        assertThatThrownBy(() -> builder(ITEMS).exitPoint(this::attach)).hasMessageContaining("one exit point");
        assertThatThrownBy(() -> builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .entryPoint(Item.class, item -> item, _ -> {}))
                .hasMessageContaining("has an entry point already");
        assertThatThrownBy(() -> builder(ITEMS).speedup(0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> builder(ITEMS).speedup(Double.NaN)).hasMessageContaining("positive");
        assertThatThrownBy(() -> builder(ITEMS).speedup(Double.POSITIVE_INFINITY))
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> builder(ITEMS).build()).hasMessageContaining("No input");
        assertThatThrownBy(() -> builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .measureFrom(_ -> false)
                        .build())
                .hasMessageContaining("accepts none of the replayed inputs");
        final Set<Item> firstHalf = new HashSet<>(ITEMS.subList(0, 500));
        assertThatThrownBy(() -> PipelineTimer.builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .exitPoint(this::attach, firstHalf::contains)
                        .measureFrom(ITEMS.get(500)::equals)
                        .drain(() -> {})
                        .build())
                .hasMessageContaining("No recorded input follows");
        assertThatThrownBy(() -> builder(List.of(ITEMS.get(1), ITEMS.get(0)))
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .build())
                .hasMessageContaining("Input 1 is due at");
        assertThatThrownBy(() -> PipelineTimer.<TimedInput>builder(ITEMS)
                        .entryPoint(Item.class, item -> item, _ -> {})
                        .entryPoint(TimedInput.class, input -> input, _ -> {})
                        .exitPoint(this::attach)
                        .drain(() -> {})
                        .build())
                .hasMessageContaining("which both have entry points");

        final PipelineTimer.Builder<Item> builder = builder(ITEMS).entryPoint(Item.class, item -> item, _ -> {});
        builder.build();
        assertThatThrownBy(builder::build).hasMessageContaining("built a timer already");
    }

    /** Starts a builder whose output is {@link #output} and whose drain does nothing. */
    @NonNull
    private <I extends TimedInput> PipelineTimer.Builder<I> builder(@NonNull final List<I> inputs) {
        return PipelineTimer.<I>builder(inputs).exitPoint(this::attach).drain(() -> {});
    }

    private void attach(@NonNull final Consumer<Object> consumer) {
        output = consumer;
    }

    /** Ends the iteration and returns its numeric results by label. */
    @NonNull
    private Map<String, Double> results() {
        final List<? extends Result<?>> results = profiler.afterIteration(null, null, null);
        profiler = null;
        return results.stream()
                .filter(result -> !Double.isNaN(result.getScore()))
                .collect(Collectors.toMap(Result::getLabel, Result::getScore));
    }

    private static void spin(final long nanos) {
        final long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /** An input whose {@link #pristine()} returns a new object every time, as a mutable input does. */
    private record Fresh(@NonNull Instant time) implements TimedInput {

        @Override
        @NonNull
        public Fresh pristine() {
            return new Fresh(time);
        }

        @NonNull
        static List<Fresh> evenlySpaced(final int count) {
            return Item.evenlySpaced(count, 100_000).stream()
                    .map(item -> new Fresh(item.time()))
                    .toList();
        }
    }

    /** An input wire that records how it is called and hands accepted values to the output. */
    private final class RecordingInputWire implements InputWire<Item> {

        private final boolean accepts;
        private final List<SolderType> calls = new ArrayList<>();

        RecordingInputWire(final boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        @NonNull
        public String getName() {
            return "recording";
        }

        @Override
        @NonNull
        public String getTaskSchedulerName() {
            return "recording";
        }

        @Override
        @NonNull
        public TaskSchedulerType getTaskSchedulerType() {
            return TaskSchedulerType.DIRECT;
        }

        @Override
        public void put(@NonNull final Item data) {
            calls.add(SolderType.PUT);
            output.accept(data);
        }

        @Override
        public boolean offer(@NonNull final Item data) {
            calls.add(SolderType.OFFER);
            if (accepts) {
                output.accept(data);
            }
            return accepts;
        }

        @Override
        public void inject(@NonNull final Item data) {
            calls.add(SolderType.INJECT);
            output.accept(data);
        }
    }
}
