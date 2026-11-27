// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;
import static org.hiero.consensus.wiring.framework.wires.SolderType.INJECT;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer;
import org.hiero.consensus.benchmark.tools.pipeline.UncaughtExceptionCollector;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.TransactionLimits;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.wiring.framework.WiringConfig;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.CreationAttempt;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the latency of an event through the event intake, {@link DefaultEventIntakeModule}: from the time gossip
 * hands the event over until the orphan buffer releases it. On the way, the event is hashed, its fields are validated,
 * duplicates are dropped, its signature is verified, and it waits in the orphan buffer until its parents are released.
 * The branch detector and the branch reporter come after the orphan buffer, so they are not part of the latency.
 *
 * <p>{@link FalconRecorder} simulates a network of 28 nodes once per trial, on the ping times of mainnet, with the
 * transactions of mainnet and RSA signatures, as on mainnet. The benchmark replays what the first node's intake got,
 * at the recorded times: the events received from other nodes, the events the node created, and the event windows
 * from its consensus engine. A {@link PipelineTimer} hands each of them to the intake at its due time, without waiting
 * for earlier events to finish, so the latency includes the time an event waits in the intake's queues and for its
 * parents. A replay at a higher rate would shorten that waiting.
 *
 * <p>The simulated network sends a node its own events back, which a real network does not, so the benchmark leaves
 * these copies out.
 *
 * <p>One invocation, a pass, replays the whole recording. The pass starts with an intake as a new node has it: the
 * components are cleared and get the genesis event window, as after a reconnect. The events before the first event
 * window that moves the ancient threshold fill the intake's components, so that the intake is measured in a state like
 * that of a long run; their latencies are not recorded. From that window on, the latency of every received event is
 * recorded. The node's own events enter after the hasher; they are replayed, but their latencies are not recorded.
 *
 * <p>The intake is configured and wired as in production: the default configuration, a thread pool of the production
 * size, the production metrics implementation ({@link DefaultPlatformMetrics}, not started), the event pipeline
 * tracker with its latency gauges, and the system clock. The {@link DefaultIntakeEventCounter} counts every received
 * event of the pass before the pass starts, as gossip would count it when it hands the event over, so that the
 * intake's decrements for the events it drops are real and the increments, which are gossip's work, are not measured.
 *
 * <p>After every pass, the benchmark checks that no pipeline thread threw, that the expected number of received
 * events left the intake, and that the intake dropped the expected number of events from each node. The recording
 * runs the event creator and the consensus code, so a change to them can change the events and these numbers; the
 * expected numbers then change in the same pull request.
 *
 * <p>{@link LatencyProfiler} reports the latencies and the bytes allocated per recorded event: run the benchmark with
 * {@code -prof} and the profiler's class name. JMH's primary score is the time of a pass, which the recording fixes.
 */
@State(Scope.Thread)
@Fork(
        value = 2,
        jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IntakePipelineBenchmark {

    /** The seed of the simulation. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of nodes in the simulated network. */
    private static final int NUMBER_OF_NODES = 28;

    /** The number of events the recorded node releases from its orphan buffer, its own included. */
    private static final int NUMBER_OF_EVENTS = 115_000;

    /** The number of received events whose latencies a pass records. */
    private static final int EXPECTED_ARRIVALS = 104_902;

    /** The number of received events the intake drops in a pass, by the node that sent them. */
    private static final Map<NodeId, Integer> EXPECTED_DROPS = Map.of();

    /**
     * The transaction limits of the execution layer: Hedera's defaults of {@code nodeTransaction.maxBytes} and
     * {@code transaction.maxTransactionBytesPerEvent}.
     */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(33_554_432, 33_554_432);

    /** The ancient threshold of the genesis event window; the first window above it starts the measurement. */
    private static final long GENESIS_ANCIENT_THRESHOLD =
            EventWindow.getGenesisEventWindow().ancientThreshold();

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The thread pool of the wiring model. */
    private ForkJoinPool pool;

    /** The wiring model the intake runs on. */
    private WiringModel model;

    /** Collects the exceptions thrown on the pipeline's threads. */
    private UncaughtExceptionCollector exceptions;

    /** The intake under test. */
    private DefaultEventIntakeModule intake;

    /** The counter of the events in the intake, by the node that sent them. */
    private DefaultIntakeEventCounter intakeEventCounter;

    /** The nodes of the network. */
    private RosterWrapper roster;

    /** The number of received events a pass replays, by the node that sent them. */
    private Map<NodeId, Integer> receivedEventsBySender;

    /** Replays the recording into the intake and records the latencies. */
    private PipelineTimer timer;

    /**
     * Records the network, builds the intake on a wiring model as in production, and builds the timer. This method is
     * called once per trial.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        final Recording recording = FalconRecorder.record(RecordingSpec.builder(NUMBER_OF_NODES, SEED, NUMBER_OF_EVENTS)
                .signingSchema(SigningSchema.RSA)
                .build());
        final NodeRecording node = recording.nodes().getFirst();
        final NodeId selfId = node.nodeId();
        roster = recording.roster();

        receivedEventsBySender = new HashMap<>();
        for (final Step step : node.steps()) {
            if (step instanceof final Received received && !isOwnEvent(received, selfId)) {
                receivedEventsBySender.merge(received.sender(), 1, Integer::sum);
            }
        }

        // The recorded node's configuration runs some intake components on direct schedulers; production runs them
        // on their own threads, so the intake gets the default configuration.
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                selfId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final Time time = Time.getCurrent();

        exceptions = new UncaughtExceptionCollector();
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);
        final int parallelism = (int) Math.max(
                1,
                wiringConfig.defaultPoolMultiplier() * Runtime.getRuntime().availableProcessors()
                        + wiringConfig.defaultPoolConstant());
        pool = ExecutorFactory.create("IntakePipelineBenchmark", exceptions).createForkJoinPool(parallelism);
        model = WiringModelBuilder.create(metrics, time)
                .withDefaultPool(pool)
                .withWiringConfig(wiringConfig)
                .withUncaughtExceptionHandler(exceptions)
                .build();

        intakeEventCounter = new DefaultIntakeEventCounter(roster);
        intake = new DefaultEventIntakeModule();
        intake.initialize(
                model,
                configuration,
                metrics,
                time,
                createRosterWrapperHistory(ROUND_FIRST, roster),
                intakeEventCounter,
                TRANSACTION_LIMITS,
                EventPipelineTracker.create(metrics, configuration, time));

        timer = PipelineTimer.builder(node.steps())
                .entryPoint(
                        Received.class,
                        received -> isOwnEvent(received, selfId) ? null : received.event(),
                        intake.unhashedEventsInputWire())
                .entryPoint(
                        CreationAttempt.class, CreationAttempt::created, intake.nonValidatedEventsInputWire(), INJECT)
                .entryPoint(NewEventWindow.class, NewEventWindow::window, intake.initialEventWindowInputWire(), INJECT)
                .exitPoint(intake.validatedEventsOutputWire(), step -> step instanceof Received)
                .measureFrom(step -> step instanceof final NewEventWindow newWindow
                        && newWindow.window().ancientThreshold() > GENESIS_ANCIENT_THRESHOLD)
                .drain(intake::flush)
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
     * Resets the intake to the state of a new node, counts the received events of the pass, and prepares fresh copies
     * of the events. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        intake.clearComponentsInputWire().inject(NoInput.getInstance());
        intake.initialEventWindowInputWire().inject(EventWindow.getGenesisEventWindow());
        intake.flush();

        intakeEventCounter.reset();
        receivedEventsBySender.forEach((sender, count) -> {
            for (int i = 0; i < count; i++) {
                intakeEventCounter.eventEnteredIntakePipeline(sender);
            }
        });

        timer.prepare();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // pass.
        System.gc();
    }

    /**
     * Replays the recording into the intake, each step at its due time.
     */
    @Benchmark
    public void intake() {
        timer.replay();
    }

    /**
     * Checks that no pipeline thread threw, that the expected number of received events left the intake, and that the
     * intake dropped the expected number of events from each node. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        exceptions.throwIfAny();
        if (timer.arrivals() != EXPECTED_ARRIVALS) {
            throw new IllegalStateException("Expected " + EXPECTED_ARRIVALS + " arrivals, but got " + timer.arrivals());
        }
        verifyDrops();
    }

    /**
     * Checks that the intake dropped the expected number of events from each node. The intake decrements the counter
     * only for the events it drops, so after the pass every node's count is its received events minus its drops. The
     * check takes that many events out of the counter, as the shadowgraph would for the events it gets: one more drop
     * makes the counter throw, one fewer leaves an event in it.
     */
    private void verifyDrops() {
        receivedEventsBySender.forEach((sender, count) -> {
            final int expectedInCounter = count - EXPECTED_DROPS.getOrDefault(sender, 0);
            try {
                for (int i = 0; i < expectedInCounter; i++) {
                    intakeEventCounter.eventExitedIntakePipeline(sender);
                }
            } catch (final IllegalStateException e) {
                throw new IllegalStateException("The intake dropped more events from " + sender + " than expected", e);
            }
        });
        for (final NodeId nodeId : roster.nodeIds()) {
            if (intakeEventCounter.hasUnprocessedEvents(nodeId)) {
                throw new IllegalStateException("The intake dropped fewer events from " + nodeId + " than expected");
            }
        }
    }

    /**
     * Tells whether a received event is one of the node's own events, which the simulated network sends back to it.
     */
    private static boolean isOwnEvent(final Received received, final NodeId selfId) {
        return received.event().getCreatorId().equals(selfId);
    }
}
