// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;
import static org.hiero.consensus.wiring.framework.wires.SolderType.INJECT;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.pipeline.PipelineTimer;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.TransactionLimits;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
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
 * <p>{@link RecordedNode} records a simulated network of 28 nodes once per trial. The benchmark replays what the first
 * node's intake got, at the recorded times: the events received from other nodes, the events the node created, and the
 * event windows from its consensus engine. A {@link PipelineTimer} hands each of them to the intake at its due time,
 * without waiting for earlier events to finish, so the latency includes the time an event waits in the intake's queues
 * and for its parents. A replay at a higher rate would shorten that waiting.
 *
 * <p>One invocation, a pass, replays the whole recording. The pass starts with an intake as a new node has it: the
 * components are cleared and get the genesis event window, as after a reconnect. The events before the first event
 * window that moves the ancient threshold fill the intake's components, so that the intake is measured in a state like
 * that of a long run; their latencies are not recorded. From that window on, the latency of every received event is
 * recorded. The node's own events enter after the hasher; they are replayed, but their latencies are not recorded.
 *
 * <p>The intake runs on a {@link NodeContext}, as in production, with the event pipeline tracker and its latency
 * gauges. In production, gossip counts every event it hands to the intake in the {@link DefaultIntakeEventCounter},
 * the intake takes out the events it drops, and the shadowgraph the rest. The benchmark has neither gossip nor a
 * shadowgraph, so at the start of a pass the counter holds just the events the intake is expected to drop.
 *
 * <p>After every pass, the benchmark checks that no pipeline thread threw, that the expected number of received
 * events left the intake, and that the intake dropped the expected number of events from each node. The recording
 * runs the event creator and the consensus code, so a change to them can change the events and these numbers; the
 * expected numbers then change in the same pull request.
 *
 * <p>{@link LatencyProfiler} reports the latencies and the bytes allocated per recorded event: run the benchmark with
 * {@code -prof} and the profiler's class name. JMH's primary score is the time of a pass, which the recording fixes.
 *
 * <p>A pass takes about 3.5 minutes, longer than the 1 s of an iteration, so every iteration is one pass. A pass records
 * just over the 100,000 latencies that p99.9 needs. The heap holds what a pass allocates in the young generation, so
 * that no garbage collection falls into a pass; the calibration discarded none.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-11-27, 50fcaa19d3</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms8g -Xmx8g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: IntakePipelineBenchmark.intake</li>
 *   <li>Steady state after: 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.65% per 1 s iteration, 0.00% between forks, - between runs (1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 0.61% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 105 k; expected stable: mean, p90, p99, p99.9, max; unmeasured: p99.99</li>
 *   <li>Runtime: 34.0 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/45962879422, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/45985427770</li>
 * </ul>
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

    /** The number of received events whose latencies a pass records. */
    private static final int EXPECTED_ARRIVALS = 104_902;

    /**
     * The number of received events the intake drops in a pass, by the node that sent them. If the intake drops more
     * events from a node, the counter throws on a pipeline thread, which fails the pass.
     */
    private static final Map<NodeId, Integer> EXPECTED_DROPS = Map.of();

    /**
     * The transaction limits of the execution layer: Hedera's defaults of {@code nodeTransaction.maxBytes} and
     * {@code transaction.maxTransactionBytesPerEvent}.
     */
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(33_554_432, 33_554_432);

    /** The ancient threshold of the genesis event window; the first window above it starts the measurement. */
    private static final long GENESIS_ANCIENT_THRESHOLD =
            EventWindow.getGenesisEventWindow().ancientThreshold();

    /** The recorded node whose intake the benchmark replays. */
    private RecordedNode recordedNode;

    /** The context the event intake runs on. */
    private NodeContext context;

    /** The event intake module under test. */
    private DefaultEventIntakeModule intake;

    /** The intake event counter. It holds only the events the intake is expected to drop. */
    private DefaultIntakeEventCounter intakeEventCounter;

    /** Replays the recording into the intake and records the latencies. */
    private PipelineTimer timer;

    /**
     * Records the network, builds the intake as in production, and builds the timer. This method is called once per
     * trial.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        recordedNode = RecordedNode.record();
        context = NodeContext.create(recordedNode.nodeId());

        intakeEventCounter = new DefaultIntakeEventCounter(recordedNode.roster());
        intake = new DefaultEventIntakeModule();
        intake.initialize(
                context.model(),
                context.configuration(),
                context.metrics(),
                context.time(),
                createRosterWrapperHistory(ROUND_FIRST, recordedNode.roster()),
                intakeEventCounter,
                TRANSACTION_LIMITS,
                EventPipelineTracker.create(context.metrics(), context.configuration(), context.time()));

        timer = PipelineTimer.builder(recordedNode.steps())
                .entryPoint(Received.class, Received::event, intake.unhashedEventsInputWire())
                .entryPoint(
                        CreationAttempt.class, CreationAttempt::created, intake.nonValidatedEventsInputWire(), INJECT)
                .entryPoint(NewEventWindow.class, NewEventWindow::window, intake.initialEventWindowInputWire(), INJECT)
                .exitPoint(intake.validatedEventsOutputWire(), step -> step instanceof Received)
                .measureFrom(step -> step instanceof final NewEventWindow newWindow
                        && newWindow.window().ancientThreshold() > GENESIS_ANCIENT_THRESHOLD)
                .drain(intake::flush)
                .build();
        context.start();
    }

    /**
     * Stops the wiring model and its threads. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        context.stop();
    }

    /**
     * Resets the intake to the state of a new node, counts the events it is expected to drop, and prepares fresh copies
     * of the events. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        intake.clearComponentsInputWire().inject(NoInput.getInstance());
        intake.initialEventWindowInputWire().inject(EventWindow.getGenesisEventWindow());
        intake.flush();

        intakeEventCounter.reset();
        EXPECTED_DROPS.forEach((sender, drops) -> {
            for (int i = 0; i < drops; i++) {
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
     * intake dropped no fewer events from each node than expected. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        context.exceptions().throwIfAny();
        if (timer.arrivals() != EXPECTED_ARRIVALS) {
            throw new IllegalStateException("Expected " + EXPECTED_ARRIVALS + " arrivals, but got " + timer.arrivals());
        }
        for (final NodeId nodeId : recordedNode.roster().nodeIds()) {
            if (intakeEventCounter.hasUnprocessedEvents(nodeId)) {
                throw new IllegalStateException("The intake dropped fewer events from " + nodeId + " than expected");
            }
        }
    }
}
