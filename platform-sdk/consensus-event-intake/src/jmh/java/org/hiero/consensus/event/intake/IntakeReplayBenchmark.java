// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake;

import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer;
import org.hiero.consensus.benchmark.tools.pipeline.UncaughtExceptionCollector;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.config.MetricsConfig_;
import org.hiero.consensus.metrics.platform.DefaultMetricsProvider;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.transaction.TransactionLimits;
import org.hiero.consensus.wiring.framework.WiringConfig;
import org.hiero.consensus.wiring.framework.model.TraceableWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.wires.SolderType;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.CreationAttempt;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
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
 * TEMPORARY: the experiment of #27832, never merged. Replays a Falcon recording through the default or the concurrent
 * intake with the {@link PipelineTimer}, and prints, for every pass, what the experiment compares: garbage
 * collections, allocation over all threads, and three measures of saturation (the latency trend, the queue trend, and
 * the health monitor). Each pass prints a line starting with {@code PASS}, each iteration one starting with
 * {@code ITER}, and the trial its recording's rate profile in lines starting with {@code TRIAL}.
 */
@State(Scope.Thread)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 20)
@Measurement(iterations = 2, time = 20)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public abstract class IntakeReplayBenchmark {

    /** The default intake; select it with the pattern {@code IntakeReplayBenchmark.Default}. */
    public static class Default extends IntakeReplayBenchmark {
        public Default() {
            super("DefaultEventIntakeModule");
        }
    }

    /** The concurrent intake; select it with the pattern {@code IntakeReplayBenchmark.Concurrent}. */
    public static class Concurrent extends IntakeReplayBenchmark {
        public Concurrent() {
            super("ConcurrentEventIntakeModule");
        }
    }

    /** The simple class name of the intake module. */
    private final String intake;

    protected IntakeReplayBenchmark(final String intake) {
        this.intake = intake;
    }

    private static final long SEED = 0xBE7C_4A11L;
    private static final int NUM_NODES = 10;
    private static final int EVENTS = 100_000;

    /** The node's transaction limits, as {@code EventFieldValidatorBenchmark} uses them. */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(6_144, 33_554_432);

    /** Room for the queue samples of a pass of up to about 17 minutes, at one per ms. */
    private static final int MAX_QUEUE_SAMPLES = 1 << 20;

    @Param({"500", "600", "700"})
    public double speedup;

    /** Whether the recorded event windows are replayed; {@code false} only for diagnosis. */
    @Param({"true"})
    public boolean windows;

    private ForkJoinPool pool;
    private DefaultMetricsProvider metricsProvider;
    private WiringModel model;
    private EventIntakeModule intakeModule;
    private UncaughtExceptionCollector exceptions;
    private PipelineTimer<Step> timer;
    private List<TaskScheduler<?>> schedulers;
    private int expectedArrivals;
    private long[] dueNanos;
    private boolean[] counted;
    private InputWire<Object> clearInput;
    private InputWire<EventWindow> initialEventWindowInput;

    private final long[] sampleNanos = new long[MAX_QUEUE_SAMPLES];
    private final long[] sampleQueued = new long[MAX_QUEUE_SAMPLES];
    private int samples;
    private long passStart;

    /** The longest unhealthy duration the health monitor reported during the pass, in ns. */
    private volatile long maxUnhealthyNanos;

    private String iterationType;
    private int iteration;
    private int pass;
    private int passes;
    private int gcPasses;
    private final LatencyHistogram iterationAll = LatencyRecorder.newHistogram();
    private final LatencyHistogram iterationWithoutGc = LatencyRecorder.newHistogram();

    @Setup(Level.Trial)
    public void setUpTrial() throws ReflectiveOperationException {
        final long recordingStart = System.nanoTime();
        final Recording recording = FalconRecorder.record(RecordingSpec.builder(NUM_NODES, SEED, EVENTS)
                .signingSchema(SigningSchema.RSA)
                .build());
        final NodeRecording node = recording.nodes().getFirst();
        final RosterWrapper roster = recording.roster();
        printRecording(node, (System.nanoTime() - recordingStart) / 1_000_000);
        for (final Step step : node.steps()) {
            if (step instanceof final Released released
                    && !released.event().getCreatorId().equals(node.nodeId())) {
                expectedArrivals++;
            }
        }

        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.DISABLE_METRICS_OUTPUT, true)
                .getOrCreateConfig();
        final Time time = Time.getCurrent();
        metricsProvider = new DefaultMetricsProvider(configuration);
        final Metrics metrics = metricsProvider.createPlatformMetrics(node.nodeId());
        exceptions = new UncaughtExceptionCollector();
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);
        final int parallelism = (int) Math.max(
                1,
                wiringConfig.defaultPoolMultiplier() * Runtime.getRuntime().availableProcessors()
                        + wiringConfig.defaultPoolConstant());
        pool = ExecutorFactory.create("replay", exceptions).createForkJoinPool(parallelism);
        model = WiringModelBuilder.create(metrics, time)
                .withDefaultPool(pool)
                .withWiringConfig(wiringConfig)
                .withUncaughtExceptionHandler(exceptions)
                .build();

        intakeModule = createIntakeModule(intake);
        final IntakeEventCounter intakeEventCounter = new DefaultIntakeEventCounter(roster);
        intakeModule.initialize(
                model,
                configuration,
                metrics,
                time,
                createRosterWrapperHistory(0L, roster),
                intakeEventCounter,
                TRANSACTION_LIMITS,
                EventPipelineTracker.create(metrics, configuration, time));

        // a module may build its input wires when they are first asked for, which it cannot once the model runs
        final InputWire<PlatformEvent> unhashedEvents = intakeModule.unhashedEventsInputWire();
        clearInput = intakeModule.clearComponentsInputWire();
        initialEventWindowInput = intakeModule.initialEventWindowInputWire();
        timer = PipelineTimer.builder(node.steps())
                .input(Received.class, Received::event, (PlatformEvent event) -> {
                    // as gossip does before it hands an event to the intake
                    intakeEventCounter.eventEnteredIntakePipeline(event.getSenderId());
                    unhashedEvents.put(event);
                })
                .input(
                        CreationAttempt.class,
                        CreationAttempt::created,
                        intakeModule.nonValidatedEventsInputWire(),
                        SolderType.INJECT)
                .input(
                        NewEventWindow.class,
                        w -> windows
                                ? new ConsensusRound(
                                        roster, List.of(), w.window(), ConsensusSnapshot.DEFAULT, false, Instant.EPOCH)
                                : null,
                        intakeModule.consensusRoundInputWire(),
                        SolderType.INJECT)
                .output(intakeModule.validatedEventsOutputWire(), step -> step instanceof Received)
                .speedup(speedup)
                .drain(intakeModule::flush)
                .experimentSampler(this::sampleQueues)
                .build();
        dueNanos = timer.experimentDueNanos();
        // the replayed inputs in the timer's order, and which of them count
        final List<Boolean> replayed = new ArrayList<>();
        for (final Step step : node.steps()) {
            if (step instanceof Received
                    || (windows && step instanceof NewEventWindow)
                    || (step instanceof CreationAttempt a && a.created() != null)) {
                replayed.add(step instanceof Received);
            }
        }
        if (replayed.size() != dueNanos.length) {
            throw new IllegalStateException(
                    "The timer replays " + dueNanos.length + " inputs, but " + replayed.size() + " were expected");
        }
        counted = new boolean[replayed.size()];
        for (int i = 0; i < counted.length; i++) {
            counted[i] = replayed.get(i);
        }

        model.getHealthMonitorWire().solderForMonitoring(duration -> {
            final long nanos = duration.toNanos();
            if (nanos > maxUnhealthyNanos) {
                maxUnhealthyNanos = nanos;
            }
        });

        final Field field = TraceableWiringModel.class.getDeclaredField("schedulers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<TaskScheduler<?>> all = (List<TaskScheduler<?>>) field.get(model);
        schedulers = List.copyOf(all);
        for (final TaskScheduler<?> scheduler : schedulers) {
            System.out.printf(
                    "TRIAL scheduler %s type=%s capacity=%d%n",
                    scheduler.getName(), scheduler.getType(), scheduler.getCapacity());
        }
        System.out.printf(
                "TRIAL intake=%s speedup=%s windows=%s parallelism=%d replayed=%d expectedArrivals=%d"
                        + " passSeconds=%.2f%n",
                intake,
                fmt(speedup),
                windows,
                parallelism,
                dueNanos.length,
                expectedArrivals,
                dueNanos[dueNanos.length - 1] / 1e9);

        model.start();
        metricsProvider.start();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        model.stop();
        pool.shutdownNow();
        metricsProvider.stop();
    }

    @Setup(Level.Iteration)
    public void startIteration(final IterationParams params) {
        iterationType = params.getType().name();
        iteration++;
        pass = 0;
        passes = 0;
        gcPasses = 0;
        iterationAll.reset();
        iterationWithoutGc.reset();
    }

    @TearDown(Level.Iteration)
    public void endIteration() {
        System.out.printf(
                "ITER intake=%s speedup=%s windows=%s pid=%d iteration=%d type=%s passes=%d gcPasses=%d all=[%s]"
                        + " withoutGc=[%s]%n",
                intake,
                fmt(speedup),
                windows,
                ProcessHandle.current().pid(),
                iteration,
                iterationType,
                passes,
                gcPasses,
                summary(iterationAll),
                summary(iterationWithoutGc));
    }

    @Setup(Level.Invocation)
    public void prepareInvocation() {
        // as reconnect resets the intake: clear, then a new initial event window, which the signature validator,
        // having no clear, needs to forget the window of the previous pass
        clearInput.inject(NoInput.getInstance());
        initialEventWindowInput.inject(EventWindow.getGenesisEventWindow());
        intakeModule.flush();
        timer.prepare();
        samples = 0;
        maxUnhealthyNanos = 0;
        System.gc();
    }

    @Benchmark
    public void replay() {
        final long[] gcBefore = gcCounts();
        passStart = System.nanoTime();
        timer.replay();
        final long passNanos = System.nanoTime() - passStart;
        final long[] gcAfter = gcCounts();
        report(passNanos, gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1], gcAfter[2] - gcBefore[2]);
    }

    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
    }

    /** Samples the queued tasks of all schedulers; the timer runs it once per ms during a pass. */
    private void sampleQueues() {
        final long now = System.nanoTime();
        if (samples == MAX_QUEUE_SAMPLES) {
            return;
        }
        long queued = 0;
        for (final TaskScheduler<?> scheduler : schedulers) {
            queued += Math.max(0, scheduler.getUnprocessedTaskCount());
        }
        sampleNanos[samples] = now - passStart;
        sampleQueued[samples] = queued;
        samples++;
    }

    /** Prints the pass's line and adds its latencies to the iteration's histograms. */
    private void report(final long passNanos, final long gcPauses, final long gcPauseMillis, final long gcConcurrent) {
        final long[] latencies = timer.experimentPassLatencies();
        final LatencyHistogram passHistogram = LatencyRecorder.newHistogram();
        final long span = dueNanos[dueNanos.length - 1];
        final List<Long> first = new ArrayList<>();
        final List<Long> last = new ArrayList<>();
        // the counted inputs that did not arrive: how many, and the due times of the first and the last
        int missing = 0;
        long firstMissingDue = -1;
        long lastMissingDue = -1;
        for (int i = 0; i < latencies.length; i++) {
            if (latencies[i] < 0 && counted[i]) {
                missing++;
                if (firstMissingDue < 0) {
                    firstMissingDue = dueNanos[i];
                }
                lastMissingDue = dueNanos[i];
            }
            if (latencies[i] >= 0) {
                passHistogram.record(latencies[i]);
                if (dueNanos[i] < span / 4) {
                    first.add(latencies[i]);
                } else if (dueNanos[i] >= span - span / 4) {
                    last.add(latencies[i]);
                }
            }
        }
        // the quarters' centers are 3/4 of the span apart
        final double quarterDistanceSeconds = 0.75 * span / 1e9;
        final double firstMedian = median(first);
        final double lastMedian = median(last);

        double queueFirst = 0;
        double queueLast = 0;
        int queueFirstCount = 0;
        int queueLastCount = 0;
        long queueMax = 0;
        for (int i = 0; i < samples; i++) {
            queueMax = Math.max(queueMax, sampleQueued[i]);
            if (sampleNanos[i] < span / 4) {
                queueFirst += sampleQueued[i];
                queueFirstCount++;
            } else if (sampleNanos[i] >= span - span / 4) {
                queueLast += sampleQueued[i];
                queueLastCount++;
            }
        }
        queueFirst = queueFirstCount == 0 ? Double.NaN : queueFirst / queueFirstCount;
        queueLast = queueLastCount == 0 ? Double.NaN : queueLast / queueLastCount;

        pass++;
        passes++;
        iterationAll.add(passHistogram);
        if (gcPauses == 0) {
            iterationWithoutGc.add(passHistogram);
        } else {
            gcPasses++;
        }
        System.out.printf(
                "PASS intake=%s speedup=%s windows=%s pid=%d iteration=%d type=%s pass=%d passMs=%.1f inputs=%d"
                        + " arrivals=%d"
                        + " expected=%d gcPauses=%d gcPauseMs=%d gcConcurrent=%d allocBytes=%d allocPerInput=%.1f"
                        + " %s latFirstMedianUs=%.1f latLastMedianUs=%.1f latTrendUsPerS=%.2f"
                        + " queueFirst=%.1f queueLast=%.1f queueMax=%d queueTrendPerS=%.2f queueSamples=%d"
                        + " maxUnhealthyMs=%.3f missing=%d missingDueMs=%.1f..%.1f%n",
                intake,
                fmt(speedup),
                windows,
                ProcessHandle.current().pid(),
                iteration,
                iterationType,
                pass,
                passNanos / 1e6,
                latencies.length,
                timer.arrivals(),
                expectedArrivals,
                gcPauses,
                gcPauseMillis,
                gcConcurrent,
                timer.experimentPassAllocatedBytes(),
                (double) timer.experimentPassAllocatedBytes() / latencies.length,
                summary(passHistogram),
                firstMedian / 1e3,
                lastMedian / 1e3,
                (lastMedian - firstMedian) / 1e3 / quarterDistanceSeconds,
                queueFirst,
                queueLast,
                queueMax,
                (queueLast - queueFirst) / quarterDistanceSeconds,
                samples,
                maxUnhealthyNanos / 1e6,
                missing,
                firstMissingDue / 1e6,
                lastMissingDue / 1e6);
    }

    /** Returns the pause collections, their total time in ms, and the concurrent cycles, of all collectors. */
    private static long[] gcCounts() {
        long pauses = 0;
        long pauseMillis = 0;
        long concurrent = 0;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getName().contains("Concurrent")) {
                concurrent += Math.max(0, bean.getCollectionCount());
            } else {
                pauses += Math.max(0, bean.getCollectionCount());
                pauseMillis += Math.max(0, bean.getCollectionTime());
            }
        }
        return new long[] {pauses, pauseMillis, concurrent};
    }

    private static String summary(final LatencyHistogram histogram) {
        if (histogram.totalCount() == 0) {
            return "n=0";
        }
        return String.format(
                Locale.ROOT,
                "n=%d meanUs=%.1f p50Us=%.1f p90Us=%.1f p99Us=%.1f p999Us=%.1f maxUs=%.1f",
                histogram.totalCount(),
                histogram.mean() / 1e3,
                histogram.valueAtPercentile(50) / 1e3,
                histogram.valueAtPercentile(90) / 1e3,
                histogram.valueAtPercentile(99) / 1e3,
                histogram.valueAtPercentile(99.9) / 1e3,
                histogram.max() / 1e3);
    }

    private static double median(final List<Long> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        final long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String fmt(final double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** Prints the recording's size and the rate of its replayed inputs per quarter and per 100 ms. */
    private static void printRecording(final NodeRecording node, final long recordingMillis) {
        final List<Instant> times = new ArrayList<>();
        for (final Step step : node.steps()) {
            if (step instanceof Received
                    || step instanceof NewEventWindow
                    || (step instanceof CreationAttempt a && a.created() != null)) {
                times.add(step.time());
            }
        }
        final Instant start = times.getFirst();
        final double spanSeconds = Duration.between(start, times.getLast()).toNanos() / 1e9;
        final int[] per100ms = new int[(int) Math.ceil(spanSeconds * 10) + 1];
        final int[] perQuarter = new int[4];
        for (final Instant time : times) {
            final long nanos = Duration.between(start, time).toNanos();
            per100ms[(int) (nanos / 100_000_000L)]++;
            perQuarter[(int) Math.min(3, nanos / 1e9 / spanSeconds * 4)]++;
        }
        final int[] sorted = Arrays.copyOf(per100ms, per100ms.length - 1);
        Arrays.sort(sorted);
        System.out.printf(
                "TRIAL recording: %d ms, %d steps, %d replayed inputs over %.1f s (%.1f/s); per quarter %s;"
                        + " per 100 ms p1=%d p50=%d p99=%d max=%d%n",
                recordingMillis,
                node.steps().size(),
                times.size(),
                spanSeconds,
                times.size() / spanSeconds,
                Arrays.toString(perQuarter),
                sorted[sorted.length / 100],
                sorted[sorted.length / 2],
                sorted[sorted.length * 99 / 100],
                sorted[sorted.length - 1]);
    }

    private static EventIntakeModule createIntakeModule(final String simpleClassName) {
        for (final ServiceLoader.Provider<EventIntakeModule> provider :
                ServiceLoader.load(EventIntakeModule.class).stream().toList()) {
            if (provider.type().getSimpleName().equals(simpleClassName)) {
                return provider.get();
            }
        }
        throw new IllegalArgumentException("No EventIntakeModule named " + simpleClassName);
    }
}
