// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.benchmark.tools.histogram.PipelineRecorder;
import org.hiero.consensus.wiring.framework.wires.SolderType;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

/**
 * Measures how long inputs take through a pipeline of components that hand them on between threads, such as the event
 * intake. It replays timed inputs, for example the steps of a node recorded by Falcon, at the times they were recorded,
 * and records each input's latency from the time it was due until it reaches the pipeline's output.
 *
 * <h2>What it is for</h2>
 *
 * <p>{@link LatencyRecorder} times calls on one thread. It cannot time an input that enters on one thread and leaves on
 * another. This timer hands every input to the pipeline at its due time, whether or not the pipeline has finished the
 * earlier ones, so that the latency includes the time an input waits in the pipeline's queues. A benchmark that hands
 * on the next input only when the previous one is done would leave that waiting out.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * @Setup(Level.Trial)
 * public void setUpTrial() {
 *     // build the pipeline on a wiring model, with an UncaughtExceptionCollector as its uncaught exception handler
 *     timer = PipelineTimer.builder(node.steps())
 *             .entryPoint(Received.class, Received::event, intake.unhashedEventsInputWire())
 *             .entryPoint(
 *                     CreationAttempt.class, CreationAttempt::created, intake.nonValidatedEventsInputWire(), INJECT)
 *             .exitPoint(intake.validatedEventsOutputWire(), step -> step instanceof Received)
 *             .speedup(SPEEDUP)
 *             .drain(intake::flush)
 *             .build();
 *     model.start();
 * }
 *
 * @Setup(Level.Invocation)
 * public void prepareInvocation() {
 *     timer.prepare();
 *     System.gc();
 * }
 *
 * @Benchmark
 * public void replay() {
 *     timer.replay();
 * }
 *
 * @TearDown(Level.Invocation)
 * public void verifyInvocation() {
 *     exceptions.throwIfAny();
 *     // check timer.arrivals() against its exact expected value
 * }
 * }</pre>
 *
 * <p>Run the benchmark with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}. The timer
 * records every pass with the profiler's {@link PipelineRecorder}, and the profiler reports the latencies as
 * {@code latency.*}.
 *
 * <h2>Inputs</h2>
 *
 * <p>{@link Builder#entryPoint} maps every input of a type to an entry point of the pipeline, a {@link Consumer} or an
 * {@link InputWire}. Its value function takes from the input what enters the pipeline; inputs whose value is
 * {@code null}, and inputs of a type without an entry point, are not replayed. The inputs must be in the order of their
 * times. {@link Builder#speedup} replays the times faster.
 *
 * <h2>Exit point</h2>
 *
 * <p>A timer has one exit point, attached when {@link Builder#exitPoint} is called, so the builder is used before the
 * wiring model starts. The exit point recognizes a value by its identity: the pipeline must hand on the object it got.
 * An optional predicate on the inputs selects the values that count; the others are ignored. A value that did not enter
 * in the running pass, or a counted value that arrives twice, is an error, which {@link #replay()} throws after the
 * drain. Latencies to intermediate points are not supported, because percentiles of parts do not add up to percentiles
 * of the whole; a benchmark that needs a second exit point uses a second benchmark method.
 *
 * <h2>One pass</h2>
 *
 * <ol>
 *   <li>{@link #prepare()}, in a per-invocation setup: calls {@link TimedInput#pristine()} on every replayed input and
 *       applies its value function, outside the measured time. The pipeline must be idle.</li>
 *   <li>{@link #replay()}, the benchmark method: hands each input to its entry point at its due time, on the calling
 *       thread, busy-waiting until then. The latency counts from the due time, so an entry point that holds the timer
 *       up counts. After the last input, it runs the drain, for example the pipeline's {@code flush()}.</li>
 *   <li>Once the drain returns, {@code replay()} records the latency of every counted value that arrived during the
 *       pass with {@link LatencyProfiler#pipelineRecorder()}. Values that never arrived are skipped;
 *       {@link #arrivals()} returns how many did, for the benchmark's exact check.</li>
 *   <li>If the pipeline did not keep up, {@code replay()} discards the pass (see below).</li>
 * </ol>
 *
 * <h2>Saturation</h2>
 *
 * <p>A pipeline that gets its inputs faster than it can process them falls behind: every input waits longer than the
 * one before, so the latencies depend on the length of the pass and say nothing about the pipeline. If the inputs come
 * a fraction ε faster than the pipeline's capacity, the latency grows by ε seconds per second of the pass. The timer
 * compares the median latency of the inputs due in the last quarter of the pass with that of the first quarter, and
 * divides the difference by the time between the quarters' centers. If that growth exceeds
 * {@value #MAX_LATENCY_GROWTH} (1%), {@code replay()} discards the pass, which the profiler counts in
 * {@code measured.saturation.discardedPasses}, and {@link #saturated()} returns true. Passes discarded regularly mean
 * that the speedup is too high. A pass with fewer than
 * {@value #MIN_LATENCIES_PER_QUARTER} latencies in either quarter is not judged. Below capacity, the event intake and
 * the self-check {@code PipelineTimerBenchmark} grew by less than 0.13%, even in passes with short bursts. Above
 * capacity, the self-check's growth matched its overload.
 *
 * <p>A pipeline that drops inputs it cannot process in time, such as the intake, which drops events that turn ancient
 * while they wait, shows saturation as missing arrivals rather than as growing latencies. The benchmark's exact check
 * of {@link #arrivals()} catches it.
 *
 * <h2>Allocation and garbage collection</h2>
 *
 * <p>A pipeline allocates on its own threads, so {@link PipelineRecorder} counts the bytes all threads of the JVM
 * allocate during the pass, and {@code LatencyProfiler} reports them per counted value as {@code alloc.norm}. A pass
 * during which a garbage collection ran is discarded, as with {@link LatencyRecorder}.
 *
 * <h2>Threads</h2>
 *
 * <p>{@code prepare()}, {@code replay()}, {@code arrivals()}, and {@code saturated()} run on the benchmark thread, and
 * a pipeline benchmark has only one (see {@link PipelineRecorder}). The handler of the configured pipeline exit is
 * called on the pipeline's threads, possibly by several at the same time: the handler reads the clock, looks up the
 * value's position in a map that {@code prepare()} filled, and writes the time into an {@link AtomicLongArray},
 * without allocating.
 */
public final class PipelineTimer {

    /** The arrival time of a value that has not arrived. */
    private static final long NOT_ARRIVED = Long.MIN_VALUE;

    /**
     * The largest growth of the latency, in seconds per second of the pass, of a pipeline that keeps up (see the class
     * description).
     */
    static final double MAX_LATENCY_GROWTH = 0.01;

    /** The fewest latencies in each of the two quarters for the saturation check to judge a pass. */
    static final int MIN_LATENCIES_PER_QUARTER = 100;

    /** The replayed inputs, in their order. */
    private final List<TimedInput> inputs;

    /** The value function of each replayed input. */
    private final Function<TimedInput, Object>[] valueFunctions;

    /** The entry point of each replayed input. */
    private final Entry[] entries;

    /** The time each replayed input is due, from the start of the pass. */
    private final long[] dueNanos;

    /** Whether the value of each replayed input counts at the exit point. */
    private final boolean[] counted;

    /** Runs after the last input, until the pipeline is done. */
    private final Runnable drain;

    /** The values of the current pass. */
    private final Object[] passValues;

    /** Receives the values at the exit point. */
    private final Output output;

    /** The latency of each replayed input in the latest pass, or -1 for one that does not count or did not arrive. */
    private final long[] passLatencies;

    /** The recorder of the current iteration, or {@code null} before the first {@link #prepare()}. */
    @Nullable
    private PipelineRecorder recorder;

    /** Whether {@link #prepare()} ran since the latest pass. */
    private boolean prepared;

    /** The counted values that arrived in the latest pass, or -1 before the first pass. */
    private int arrivals = -1;

    /** Whether the latest pass failed the saturation check. */
    private boolean saturated;

    @SuppressWarnings("unchecked")
    private PipelineTimer(
            @NonNull final List<TimedInput> inputs,
            @NonNull final List<Function<TimedInput, Object>> valueFunctions,
            @NonNull final List<Entry> entries,
            @NonNull final long[] dueNanos,
            @NonNull final boolean[] counted,
            @NonNull final Runnable drain,
            @NonNull final Output output) {
        this.inputs = List.copyOf(inputs);
        this.valueFunctions = valueFunctions.toArray(new Function[0]);
        this.entries = entries.toArray(new Entry[0]);
        this.dueNanos = dueNanos;
        this.counted = counted;
        this.drain = drain;
        this.output = output;
        this.passValues = new Object[dueNanos.length];
        this.passLatencies = new long[dueNanos.length];
        output.configure(counted);
    }

    /**
     * Starts a timer of the given inputs.
     *
     * @param inputs the inputs, in the order of their times
     * @param <I> the type of the inputs
     * @return the builder
     */
    @NonNull
    public static <I extends TimedInput> Builder<I> builder(@NonNull final List<? extends I> inputs) {
        return new Builder<>(inputs);
    }

    /**
     * Prepares the next pass: gets an untouched instance of every replayed input ({@link TimedInput#pristine()}) and
     * applies its value function. Call it before every pass, in a per-invocation setup, while the pipeline is idle; end
     * that setup with {@link System#gc()}, so that the garbage of the preparation is not collected during the pass.
     *
     * @throws IllegalStateException if a value is {@code null} although it was not when the timer was built, or if two
     * inputs have the same value object
     */
    public void prepare() {
        output.positions.clear();
        for (int i = 0; i < passValues.length; i++) {
            final Object value = valueFunctions[i].apply(inputs.get(i).pristine());
            if (value == null) {
                throw new IllegalStateException("The value of input " + i + " is null in this pass, but was not when"
                        + " the timer was built; the value function must be pure");
            }
            final Integer previous = output.positions.put(value, i);
            if (previous != null) {
                throw new IllegalStateException("Inputs " + previous + " and " + i
                        + " have the same value object; each input must enter as an object of its own");
            }
            passValues[i] = value;
            output.arrivalNanos.setPlain(i, NOT_ARRIVED);
        }
        recorder = LatencyProfiler.pipelineRecorder();
        prepared = true;
    }

    /**
     * Replays the inputs prepared by {@link #prepare()} into the pipeline at their due times, runs the drain, and
     * records the latencies of the counted values with the profiler's {@link PipelineRecorder}. Discards the pass if
     * the pipeline did not keep up (see the class description).
     *
     * @throws IllegalStateException if {@link #prepare()} did not run since the latest pass, or if the exit point saw a
     * value that did not enter in this pass, or a counted value twice
     */
    public void replay() {
        if (!prepared) {
            throw new IllegalStateException("prepare() must run before every pass");
        }
        prepared = false;
        final PipelineRecorder passRecorder = requireNonNull(recorder);
        output.replaying = true;
        passRecorder.startPass();
        final long start = System.nanoTime();
        try {
            for (int i = 0; i < passValues.length; i++) {
                final long due = start + dueNanos[i];
                while (due - System.nanoTime() > 0) {
                    Thread.onSpinWait();
                }
                entries[i].enter(passValues[i]);
            }
            drain.run();
        } finally {
            output.replaying = false;
        }
        // the pass's copies can be collected before the next pass
        output.positions.clear();
        Arrays.fill(passValues, null);
        output.throwIfErrors();

        int arrived = 0;
        for (int i = 0; i < passValues.length; i++) {
            final long arrival = output.arrivalNanos.get(i);
            if (counted[i] && arrival != NOT_ARRIVED) {
                final long latency = arrival - (start + dueNanos[i]);
                passRecorder.record(latency);
                passLatencies[i] = latency;
                arrived++;
            } else {
                passLatencies[i] = -1;
            }
        }
        // before the saturation check, which allocates
        passRecorder.endPass();
        arrivals = arrived;

        saturated = latencyGrowth(dueNanos, passLatencies) > MAX_LATENCY_GROWTH;
        if (saturated) {
            passRecorder.discardSaturatedPass();
        }
    }

    /**
     * Computes how fast the latency grew during a pass: the median latency of the inputs due in the last quarter of the
     * pass minus that of the first quarter, divided by the time between the quarters' centers.
     *
     * @param dueNanos the time each input was due, from the start of the pass, in ascending order
     * @param latencies the latency of each input, or a negative value for one without a latency
     * @return the growth in seconds per second, or {@link Double#NaN} if the pass has no length or a quarter has fewer
     * than {@link #MIN_LATENCIES_PER_QUARTER} latencies
     */
    static double latencyGrowth(@NonNull final long[] dueNanos, @NonNull final long[] latencies) {
        if (dueNanos.length == 0) {
            return Double.NaN;
        }
        final long span = dueNanos[dueNanos.length - 1] - dueNanos[0];
        if (span <= 0) {
            return Double.NaN;
        }
        final long firstQuarterEnd = dueNanos[0] + span / 4;
        final long lastQuarterStart = dueNanos[dueNanos.length - 1] - span / 4;
        final long[] first = new long[latencies.length];
        final long[] last = new long[latencies.length];
        int firstCount = 0;
        int lastCount = 0;
        for (int i = 0; i < latencies.length; i++) {
            if (latencies[i] >= 0) {
                if (dueNanos[i] < firstQuarterEnd) {
                    first[firstCount++] = latencies[i];
                } else if (dueNanos[i] >= lastQuarterStart) {
                    last[lastCount++] = latencies[i];
                }
            }
        }
        if (firstCount < MIN_LATENCIES_PER_QUARTER || lastCount < MIN_LATENCIES_PER_QUARTER) {
            return Double.NaN;
        }
        Arrays.sort(first, 0, firstCount);
        Arrays.sort(last, 0, lastCount);
        // the quarters' centers are 3/4 of the span apart
        return (last[lastCount / 2] - first[firstCount / 2]) / (0.75 * span);
    }

    /**
     * Returns how many counted values arrived at the exit point in the latest pass.
     *
     * @return the number of counted values that arrived
     * @throws IllegalStateException if no pass has run
     */
    public int arrivals() {
        if (arrivals < 0) {
            throw new IllegalStateException("No pass has run");
        }
        return arrivals;
    }

    /**
     * Returns whether the latest pass failed the saturation check, so that {@link #replay()} discarded it.
     *
     * @return true if the pipeline did not keep up in the latest pass
     * @throws IllegalStateException if no pass has run
     */
    public boolean saturated() {
        if (arrivals < 0) {
            throw new IllegalStateException("No pass has run");
        }
        return saturated;
    }

    /** Hands a value to the pipeline. */
    @FunctionalInterface
    private interface Entry {
        void enter(@NonNull Object value);
    }

    /** Receives the values at the pipeline's output, on the pipeline's threads. */
    private static final class Output {

        /**
         * Whether a pass is running. Written before and after a pass on the benchmark thread, and read first by every
         * arrival, which makes the positions that {@link #prepare()} wrote visible to the pipeline's threads.
         */
        private volatile boolean replaying;

        /** The position of each value of the current pass. */
        private final Map<Object, Integer> positions = new IdentityHashMap<>();

        /** Whether the value at each position counts; set when the timer is built. */
        private boolean[] counted = new boolean[0];

        /** The arrival time of the value at each position, or {@link #NOT_ARRIVED}. */
        private AtomicLongArray arrivalNanos = new AtomicLongArray(0);

        /** The first error. */
        private final AtomicReference<String> firstError = new AtomicReference<>();

        /** The number of errors. */
        private final AtomicLong errors = new AtomicLong();

        void configure(@NonNull final boolean[] counted) {
            this.counted = counted;
            this.arrivalNanos = new AtomicLongArray(counted.length);
        }

        /** Notes the arrival of a value; runs on a pipeline thread. */
        void arrive(@NonNull final Object value) {
            final long now = System.nanoTime();
            if (!replaying) {
                error("A value arrived outside a pass: " + describe(value));
                return;
            }
            final Integer position = positions.get(value);
            if (position == null) {
                error("A value arrived that did not enter the pipeline in this pass: " + describe(value));
                return;
            }
            if (counted[position] && !arrivalNanos.compareAndSet(position, NOT_ARRIVED, now)) {
                error("The value of input " + position + " arrived twice");
            }
        }

        private void error(@NonNull final String message) {
            firstError.compareAndSet(null, message);
            errors.incrementAndGet();
        }

        /** Throws the errors noted so far, and forgets them. */
        void throwIfErrors() {
            final long count = errors.getAndSet(0);
            final String first = firstError.getAndSet(null);
            if (count > 0) {
                throw new IllegalStateException(
                        first + (count > 1 ? " (and " + (count - 1) + " more errors at the exit point)" : ""));
            }
        }

        @NonNull
        private static String describe(@NonNull final Object value) {
            return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
        }
    }

    /**
     * Configures a {@link PipelineTimer}: its entry points, its output, its speedup, and its drain.
     *
     * @param <I> the type of the inputs
     */
    public static final class Builder<I extends TimedInput> {

        private final List<I> inputs;
        private final Map<Class<?>, Registration> registrations = new LinkedHashMap<>();

        @Nullable
        private Output output;

        @NonNull
        private Predicate<? super I> counts = _ -> true;

        private double speedup = 1;

        @Nullable
        private Runnable drain;

        private boolean built;

        private record Registration(
                @NonNull Class<?> type,
                @NonNull Function<TimedInput, Object> value,
                @NonNull Entry entry) {}

        private Builder(@NonNull final List<? extends I> inputs) {
            this.inputs = List.copyOf(inputs);
        }

        /**
         * Hands the value of every input of a type to a consumer.
         *
         * @param type the type of the inputs
         * @param value takes from an input what enters the pipeline; {@code null} means nothing enters
         * @param entry the entry point
         * @param <S> the type of the inputs
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalArgumentException if the type has an entry point already
         */
        @NonNull
        public <S extends I, T> Builder<I> entryPoint(
                @NonNull final Class<S> type,
                @NonNull final Function<? super S, ? extends T> value,
                @NonNull final Consumer<? super T> entry) {
            requireNonNull(entry);
            return register(type, value, v -> entry.accept(cast(v)));
        }

        /**
         * Puts the value of every input of a type into an input wire, as {@code solderTo} with {@link SolderType#PUT}.
         *
         * @param type the type of the inputs
         * @param value takes from an input what enters the pipeline; {@code null} means nothing enters
         * @param entry the entry point
         * @param <S> the type of the inputs
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalArgumentException if the type has an entry point already
         */
        @NonNull
        public <S extends I, T> Builder<I> entryPoint(
                @NonNull final Class<S> type,
                @NonNull final Function<? super S, ? extends T> value,
                @NonNull final InputWire<T> entry) {
            return entryPoint(type, value, entry, SolderType.PUT);
        }

        /**
         * Hands the value of every input of a type to an input wire, as {@code solderTo} with the given solder type. A
         * value that the wire drops with {@link SolderType#OFFER} is not expected at the exit point.
         *
         * @param type the type of the inputs
         * @param value takes from an input what enters the pipeline; {@code null} means nothing enters
         * @param entry the entry point
         * @param solderType how the value is handed to the wire
         * @param <S> the type of the inputs
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalArgumentException if the type has an entry point already
         */
        @NonNull
        public <S extends I, T> Builder<I> entryPoint(
                @NonNull final Class<S> type,
                @NonNull final Function<? super S, ? extends T> value,
                @NonNull final InputWire<T> entry,
                @NonNull final SolderType solderType) {
            requireNonNull(entry);
            final Entry handOn = switch (requireNonNull(solderType)) {
                case PUT -> v -> entry.put(cast(v));
                case INJECT -> v -> entry.inject(cast(v));
                case OFFER -> v -> entry.offer(cast(v));
            };
            return register(type, value, handOn);
        }

        /**
         * Attaches the exit point to a source of values, for example {@code wire::solderForMonitoring}; every value
         * counts.
         *
         * @param source attaches the consumer of the values that leave the pipeline
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalStateException if the timer has an exit point already
         */
        @NonNull
        public <T> Builder<I> exitPoint(@NonNull final Consumer<Consumer<T>> source) {
            return exitPoint(source, _ -> true);
        }

        /**
         * Attaches the exit point to a source of values, for example {@code wire::solderForMonitoring}; only the values
         * of the inputs the predicate accepts count.
         *
         * @param source attaches the consumer of the values that leave the pipeline
         * @param counts selects the inputs whose values count
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalStateException if the timer has an exit point already
         */
        @NonNull
        public <T> Builder<I> exitPoint(
                @NonNull final Consumer<Consumer<T>> source, @NonNull final Predicate<? super I> counts) {
            requireNonNull(source);
            requireNonNull(counts);
            if (output != null) {
                throw new IllegalStateException(
                        "A timer has one exit point; a benchmark that needs a second one uses a second benchmark"
                                + " method");
            }
            final Output newOutput = new Output();
            source.accept(newOutput::arrive);
            this.output = newOutput;
            this.counts = counts;
            return this;
        }

        /**
         * Attaches the exit point to an output wire, with {@link OutputWire#solderForMonitoring}; every value counts.
         *
         * @param wire the output wire
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalStateException if the timer has an exit point already
         */
        @NonNull
        public <T> Builder<I> exitPoint(@NonNull final OutputWire<T> wire) {
            return exitPoint(wire::solderForMonitoring);
        }

        /**
         * Attaches the exit point to an output wire, with {@link OutputWire#solderForMonitoring}; only the values of
         * the inputs the predicate accepts count.
         *
         * @param wire the output wire
         * @param counts selects the inputs whose values count
         * @param <T> the type of the values
         * @return this builder
         * @throws IllegalStateException if the timer has an exit point already
         */
        @NonNull
        public <T> Builder<I> exitPoint(@NonNull final OutputWire<T> wire, @NonNull final Predicate<? super I> counts) {
            return exitPoint(wire::solderForMonitoring, counts);
        }

        /**
         * Replays the times of the inputs this many times faster; 1 if not called.
         *
         * @param speedup the factor
         * @return this builder
         * @throws IllegalArgumentException if the factor is not positive and finite
         */
        @NonNull
        public Builder<I> speedup(final double speedup) {
            if (!(speedup > 0) || Double.isInfinite(speedup)) {
                throw new IllegalArgumentException("The speedup must be positive and finite, but is " + speedup);
            }
            this.speedup = speedup;
            return this;
        }

        /**
         * Sets what runs after the last input until the pipeline is done, for example the components' {@code flush()}
         * in the order the data flows. Required: a pipeline that is done when its last entry point returns registers
         * {@code () -> {}}.
         *
         * @param drain runs after the last input
         * @return this builder
         */
        @NonNull
        public Builder<I> drain(@NonNull final Runnable drain) {
            this.drain = requireNonNull(drain);
            return this;
        }

        /**
         * Ends the configuration: keeps the inputs that have an entry point and a non-null value, and computes their
         * due times and whether their values count.
         *
         * @return the timer
         * @throws IllegalStateException if the timer has no exit point or no drain, if this builder built a timer
         * already, or if no input is replayed
         * @throws IllegalArgumentException if the inputs are not in the order of their times, or if an input has more
         * than one entry point
         */
        @NonNull
        public PipelineTimer build() {
            if (built) {
                throw new IllegalStateException("This builder built a timer already");
            }
            if (output == null) {
                throw new IllegalStateException("No exit point; call exitPoint(...)");
            }
            if (drain == null) {
                throw new IllegalStateException(
                        "No drain; call drain(...), with () -> {} for a pipeline that is done when its last entry"
                                + " point returns");
            }
            final List<TimedInput> kept = new ArrayList<>();
            final List<Function<TimedInput, Object>> keptValues = new ArrayList<>();
            final List<Entry> keptEntries = new ArrayList<>();
            final List<Boolean> keptCounted = new ArrayList<>();
            Instant previous = null;
            for (int i = 0; i < inputs.size(); i++) {
                final I input = requireNonNull(inputs.get(i), "Input " + i + " is null");
                final Instant time = requireNonNull(input.time(), "The time of input " + i + " is null");
                if (previous != null && time.isBefore(previous)) {
                    throw new IllegalArgumentException(
                            "Input " + i + " is due at " + time + ", before the input before it, at " + previous);
                }
                previous = time;
                final Registration registration = registrationOf(input, i);
                if (registration != null && registration.value().apply(input) != null) {
                    kept.add(input);
                    keptValues.add(registration.value());
                    keptEntries.add(registration.entry());
                    keptCounted.add(counts.test(input));
                }
            }
            if (kept.isEmpty()) {
                throw new IllegalStateException("No input has an entry point and a non-null value");
            }
            final Instant first = kept.getFirst().time();
            final long[] dueNanos = new long[kept.size()];
            final boolean[] counted = new boolean[kept.size()];
            for (int i = 0; i < kept.size(); i++) {
                dueNanos[i] =
                        Math.round(Duration.between(first, kept.get(i).time()).toNanos() / speedup);
                counted[i] = keptCounted.get(i);
            }
            built = true;
            return new PipelineTimer(kept, keptValues, keptEntries, dueNanos, counted, drain, output);
        }

        @NonNull
        private <S extends I, T> Builder<I> register(
                @NonNull final Class<S> type,
                @NonNull final Function<? super S, ? extends T> value,
                @NonNull final Entry entry) {
            requireNonNull(type);
            requireNonNull(value);
            if (registrations.containsKey(type)) {
                throw new IllegalArgumentException(type.getName() + " has an entry point already");
            }
            registrations.put(type, new Registration(type, input -> value.apply(type.cast(input)), entry));
            return this;
        }

        @Nullable
        private Registration registrationOf(@NonNull final TimedInput input, final int index) {
            Registration found = null;
            for (final Registration registration : registrations.values()) {
                if (registration.type().isInstance(input)) {
                    if (found != null) {
                        throw new IllegalArgumentException(
                                "Input " + index + " is a " + found.type().getName() + " and a "
                                        + registration.type().getName() + ", which both have entry points");
                    }
                    found = registration;
                }
            }
            return found;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(@NonNull final Object value) {
            return (T) value;
        }
    }
}
