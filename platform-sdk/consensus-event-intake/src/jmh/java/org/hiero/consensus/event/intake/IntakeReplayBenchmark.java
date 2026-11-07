// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake;

import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.pipeline.ExperimentProbe;
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
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
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
 * intake with the {@link PipelineTimer}, and prints, for every pass, what the experiment compares
 * ({@link ExperimentProbe}). The trial prints its recording's rate profile in lines starting with {@code TRIAL}.
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

        /** The last speedup of the sweeps at which this intake kept every event. */
        @Param({"500"})
        public double speedup;

        public Default() {
            super("DefaultEventIntakeModule");
        }

        @Override
        protected double speedup() {
            return speedup;
        }
    }

    /** The concurrent intake; select it with the pattern {@code IntakeReplayBenchmark.Concurrent}. */
    public static class Concurrent extends IntakeReplayBenchmark {

        /** The last speedup of the sweeps at which this intake kept every event. */
        @Param({"600"})
        public double speedup;

        public Concurrent() {
            super("ConcurrentEventIntakeModule");
        }

        @Override
        protected double speedup() {
            return speedup;
        }
    }

    private static final long SEED = 0xBE7C_4A11L;
    private static final int NUM_NODES = 10;
    private static final int EVENTS = 100_000;

    /** The node's transaction limits, as {@code EventFieldValidatorBenchmark} uses them. */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(6_144, 33_554_432);

    /** Whether the recorded event windows are replayed; {@code false} only for diagnosis. */
    @Param({"true"})
    public boolean windows;

    /** The simple class name of the intake module. */
    private final String intake;

    private ForkJoinPool pool;
    private DefaultMetricsProvider metricsProvider;
    private WiringModel model;
    private EventIntakeModule intakeModule;
    private UncaughtExceptionCollector exceptions;
    private PipelineTimer<Step> timer;
    private ExperimentProbe probe;
    private InputWire<Object> clearInput;
    private InputWire<EventWindow> initialEventWindowInput;

    protected IntakeReplayBenchmark(final String intake) {
        this.intake = intake;
    }

    /** Returns the speedup of this intake's parameter set. */
    protected abstract double speedup();

    @Setup(Level.Trial)
    public void setUpTrial() {
        final long recordingStart = System.nanoTime();
        final Recording recording = FalconRecorder.record(RecordingSpec.builder(NUM_NODES, SEED, EVENTS)
                .signingSchema(SigningSchema.RSA)
                .build());
        final NodeRecording node = recording.nodes().getFirst();
        final RosterWrapper roster = recording.roster();
        printRecording(node, (System.nanoTime() - recordingStart) / 1_000_000);
        int expectedArrivals = 0;
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
                .speedup(speedup())
                .drain(intakeModule::flush)
                .experimentSampler(() -> probe.sample())
                .build();
        probe = new ExperimentProbe(
                "intake=" + intake + " speedup=" + fmt(speedup()) + " windows=" + windows,
                timer,
                expectedArrivals,
                ExperimentProbe.schedulersOf(model),
                model);
        System.out.printf(
                "TRIAL intake=%s speedup=%s windows=%s parallelism=%d replayed=%d expectedArrivals=%d%n",
                intake, fmt(speedup()), windows, parallelism, timer.experimentDueNanos().length, expectedArrivals);

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
        probe.startIteration(params.getType().name());
    }

    @TearDown(Level.Iteration)
    public void endIteration() {
        probe.endIteration();
    }

    @Setup(Level.Invocation)
    public void prepareInvocation() {
        // as reconnect resets the intake: clear, then a new initial event window, which the signature validator,
        // having no clear, needs to forget the window of the previous pass
        clearInput.inject(NoInput.getInstance());
        initialEventWindowInput.inject(EventWindow.getGenesisEventWindow());
        intakeModule.flush();
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
