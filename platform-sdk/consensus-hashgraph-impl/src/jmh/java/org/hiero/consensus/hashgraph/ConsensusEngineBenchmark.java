// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.hashgraph.impl.ConsensusEngine;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the latency of {@link DefaultConsensusEngine#addEvent(PlatformEvent)} on the events of a simulated network.
 *
 * <p>Besides the consensus algorithm, {@link ConsensusImpl}, which {@link ConsensusImplBenchmark} measures alone, the
 * engine links each event to its parents, buffers events whose birth round lies in the future, moves the event window
 * when a round reaches consensus, and reports stale events. The benchmark calls the engine directly on its own thread,
 * without the wiring framework that runs the engine on a separate thread in production.
 *
 * <p>{@link FalconRecorder} simulates the network once per trial: one full node and cheap stand-ins for the other
 * nodes, on the ping times of mainnet, with the transactions of mainnet and without signatures, which the engine does
 * not read. The benchmark takes the events the full node's orphan buffer released, in that order. The engine buffers
 * none of these events as future events and finds none of them stale, so those two paths only pass through here. The
 * engine mutates the events it processes, so every invocation works on fresh copies and a new engine, both created
 * outside the measured region.
 *
 * <p>The recording runs the event creator and the consensus code, so a change to them can change the events. The check
 * of the engine's output then fails, and the expected numbers change in the same pull request.
 *
 * <p>The engine updates the production metrics implementation, {@link DefaultPlatformMetrics}, so the benchmark
 * measures the cost of the metric updates in {@code addEvent}. The metrics are not started: in production, their
 * periodic updaters and snapshots run on other threads, outside {@code addEvent}.
 *
 * <p>One invocation adds all recorded events. The primary score is the mean time per event. A
 * {@link LatencyRecorder} also times every call, with one timestamp per call ({@link LatencyRecorder#lap()}), so each
 * latency also contains the output counting and the blackhole of the previous call. {@link LatencyProfiler} reports
 * the percentiles and the allocation per call. The allocation is measured around the calls only, because
 * {@code -prof gc} also counts the copying in the setup.
 *
 * <p>As in {@link ConsensusImplBenchmark}, the mean matters as much as the percentiles: it is the time to add all
 * events, divided by the number of events. Most calls are cheap and the few that decide a round are expensive, so the
 * median reflects only the cheap calls.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation, so the latency results and
 * {@code latency.mean} exclude garbage collection. JMH's primary score, measured by JMH itself, still includes
 * discarded passes. As in {@link ConsensusImplBenchmark}, the heap is sized for the recording of 28 nodes. The
 * calibration on the benchmark machine discarded no pass.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-11-03, b75af7793f</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms6g -Xmx6g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
 *   <li>Steady state after: 5 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.15% / 0.16% per 40 s iteration, 0.55% / 0.38% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 0.64% / 0.45% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 3.4 M / 1.3 M; expected stable: mean, p50, p90, p99, p99.9, p99.99 / mean, p50, p90, p99, p99.9, p99.99, max</li>
 *   <li>Runtime: 5.4 / 9.7 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41653565379, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41663394283</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 3,
        jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 40)
public class ConsensusEngineBenchmark {

    /** The seed of the simulation. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of recorded events. */
    private static final int NUMBER_OF_EVENTS = 100_000;

    /** The number of nodes in the simulated network. */
    @Param({"10", "28"})
    public int numNodes;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The configuration of the recorded node. */
    private Configuration configuration;

    /** The ID of the recorded node. */
    private NodeId selfId;

    /** The recorded events. */
    private List<PlatformEvent> recordedEvents;

    /** The expected number of consensus rounds of the recorded events. */
    private int expectedRounds;

    /** The expected number of stale events of the recorded events. */
    private int expectedStaleEvents;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The copies of the recorded events that the engine processes in one invocation. */
    private List<PlatformEvent> events;

    /** The consensus engine used in the benchmark. */
    private ConsensusEngine engine;

    /** The number of consensus rounds the engine returned in the invocation. */
    private int decidedRounds;

    /** The number of pre-consensus events the engine returned in the invocation. */
    private int preConsensusEvents;

    /** The number of stale events the engine returned in the invocation. */
    private int staleEvents;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Records the events for the benchmark. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void recordEvents() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, NUMBER_OF_EVENTS).build());
        final NodeRecording node = recording.nodes().getFirst();
        roster = recording.roster();
        configuration = node.configuration();
        selfId = node.nodeId();
        recordedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final Step step : node.steps()) {
            if (step instanceof final Released released) {
                recordedEvents.add(released.event());
            }
        }
        switch (numNodes) {
            case 10 -> {
                expectedRounds = 1934;
                expectedStaleEvents = 0;
            }
            case 28 -> {
                expectedRounds = 601;
                expectedStaleEvents = 0;
            }
            default -> throw new IllegalArgumentException("No expected output for numNodes=" + numNodes);
        }
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Prepares the benchmark invocation by copying the recorded events and creating a new consensus engine. This
     * method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final PlatformEvent recordedEvent : recordedEvents) {
            events.add(copy(recordedEvent));
        }

        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                selfId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        engine = new DefaultConsensusEngine(
                configuration, metrics, Time.getCurrent(), roster, selfId, timestamp -> false, 0L);
        decidedRounds = 0;
        preConsensusEvents = 0;
        staleEvents = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies the engine's output after the benchmark invocation: every event comes out as a pre-consensus event, and
     * the numbers of consensus rounds and stale events match the expected numbers. This method is called once per
     * invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        if (decidedRounds != expectedRounds
                || preConsensusEvents != NUMBER_OF_EVENTS
                || staleEvents != expectedStaleEvents) {
            throw new IllegalStateException(
                    ("Expected %d consensus rounds, %d pre-consensus events, and %d stale events,"
                                    + " but got %d, %d, and %d")
                            .formatted(
                                    expectedRounds,
                                    NUMBER_OF_EVENTS,
                                    expectedStaleEvents,
                                    decidedRounds,
                                    preConsensusEvents,
                                    staleEvents));
        }
        events = null;
        engine = null;
        recorder = null;
    }

    /**
     * Copies the parts of a recorded event that exist before it reaches consensus. The copy drops nGen and the
     * sequence number, so they are copied separately. It also drops the simulated time received: only the metrics read
     * that time, and they compare it with the wall clock.
     */
    private static PlatformEvent copy(final PlatformEvent event) {
        final PlatformEvent copy = event.copyGossipedData();
        copy.setNGen(event.getNGen());
        copy.setSequenceNumber(event.getSequenceNumber());
        return copy;
    }

    /**
     * Adds all events to the consensus engine and records the latency of each call.
     *
     * @param bh the blackhole to consume the results and prevent dead code elimination
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void addEvents(final Blackhole bh) {
        recorder.startMeasurement();
        for (final PlatformEvent event : events) {
            final ConsensusEngineOutput output = engine.addEvent(event);
            // One timestamp per call: each latency also contains the bookkeeping of the previous call.
            recorder.lap();
            decidedRounds += output.consensusRounds().size();
            preConsensusEvents += output.preConsensusEvents().size();
            staleEvents += output.staleEvents().size();
            bh.consume(output);
        }
        recorder.stopMeasurement();
    }
}
