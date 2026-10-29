// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
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
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
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
 * Measures the latency of {@link DefaultConsensusEngine#addEvent(PlatformEvent)} on a pre-generated event graph.
 *
 * <p>Besides the consensus algorithm, {@link ConsensusImpl}, which {@link ConsensusImplBenchmark} measures alone, the
 * engine links each event to its parents, buffers events whose birth round lies in the future, moves the event window
 * when a round reaches consensus, and reports stale events. The benchmark calls the engine directly on its own thread,
 * without the wiring framework that runs the engine on a separate thread in production.
 *
 * <p>The graph is generated once per trial. The engine mutates the events it processes, so every invocation works on
 * fresh copies and a new engine, both created outside the measured region.
 *
 * <p>The engine updates the production metrics implementation, {@link DefaultPlatformMetrics}, so the benchmark
 * measures the cost of the metric updates in {@code addEvent}. The metrics are not started: in production, their
 * periodic updaters and snapshots run on other threads, outside {@code addEvent}.
 *
 * <p>One invocation adds all events of the graph. The primary score is the mean time per event. A
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
 * discarded passes.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-22, dc745a5d0a</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms2g -Xmx2g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: numNodes=4 / numNodes=10</li>
 *   <li>Steady state after: 5 s / 5 s</li>
 *   <li>Noise (latency.mean, CV): 0.22% / 0.17% per 5 s iteration, 0.39% / 0.27% between forks, 0.00% / 0.03% between runs (3 of 3 / 3 of 3 runs quiet)</li>
 *   <li>Expected detectable regression: 0.35% / 0.26% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 2.4 M / 1.5 M; expected stable: mean, p50, p90, p99, p99.9, p99.99 / mean, p50, p90, p99, p99.9, p99.99</li>
 *   <li>Runtime: 4.0 / 4.2 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39554184858, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39554193381, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39554201377</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 5,
        jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 15)
@Measurement(iterations = 2, time = 15)
public class ConsensusEngineBenchmark {

    /** The seed of the random number generator. The same seed produces the same graph. */
    private static final long SEED = 0;

    /** The number of events in the generated graph. */
    private static final int NUMBER_OF_EVENTS = 100000;

    /** The maximum number of other parents of a generated event. */
    private static final int MAX_OTHER_PARENTS = 4;

    /** The number of nodes in the generated graph. */
    @Param({"4", "10"})
    public int numNodes;

    /** The roster of the generated graph. */
    private RosterWrapper roster;

    /** The generated events of the graph. */
    private List<PlatformEvent> generatedEvents;

    /** The expected number of consensus rounds in the graph. */
    private int expectedRounds;

    /** The expected number of stale events in the graph. */
    private int expectedStaleEvents;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The copies of the generated events that the engine processes in one invocation. */
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
     * Generates the event graph for the benchmark. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void generateEvents() {
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .seed(SEED)
                .maxOtherParents(MAX_OTHER_PARENTS)
                .realSignatures(false)
                .numNodes(numNodes)
                .populateNgen(true)
                .build();
        roster = generator.getRoster();
        generatedEvents = generator.nextEvents(NUMBER_OF_EVENTS);
        switch (numNodes) {
            case 4 -> {
                expectedRounds = 14960;
                expectedStaleEvents = 0;
            }
            case 10 -> {
                expectedRounds = 4041;
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
     * Prepares the benchmark invocation by copying the generated events and creating a new consensus engine. This
     * method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        events = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final PlatformEvent generatedEvent : generatedEvents) {
            events.add(copy(generatedEvent));
        }

        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final NodeId selfId = roster.nodeIdAtIndex(0);
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
     * Copies the parts of a generated event that exist before it reaches consensus. The copy drops nGen and the
     * sequence number, so they are copied separately.
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
