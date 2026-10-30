// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.Consensus;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
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
 * Measures the latency of {@link ConsensusImpl#addEvent(EventImpl)} on the events of a simulated network.
 *
 * <p>{@link FalconRecorder} simulates the network once per trial: one full node and cheap stand-ins for the other
 * nodes, on the ping times of mainnet, with the transactions of mainnet and without signatures, which consensus does
 * not read. The benchmark takes the events the full node's orphan buffer released, in that order. In production, a
 * future event buffer sits between the orphan buffer and consensus; it would hold back none of these events.
 * {@link ConsensusImpl} mutates the events it processes, so every invocation works on fresh copies, linked outside the
 * measured region.
 *
 * <p>The recording runs the event creator and the consensus code, so a change to them can change the events. The check
 * of the decided rounds then fails, and the expected rounds change in the same pull request.
 *
 * <p>One invocation adds all recorded events. The primary score is the mean time per event. A
 * {@link LatencyRecorder} also times every call, with one timestamp per call ({@link LatencyRecorder#lap()}), so each
 * latency also contains the round counting and the blackhole of the previous call. {@link LatencyProfiler} reports the
 * percentiles and the allocation per call. The allocation is measured around the calls only, because
 * {@code -prof gc} also counts the copying and linking in the setup.
 *
 * <p>In latency benchmarks we usually ignore the mean and look at the percentiles. Here the mean matters as much. The
 * benchmark exists to give an overall impression of how fast the consensus algorithm is: the time to add all events,
 * divided by the number of events, so that graphs of different sizes are comparable. The mean is exactly this number.
 * The percentiles show how this cost is distributed. Most calls are cheap and the few that decide a round are
 * expensive, so the median reflects only the cheap calls.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation, so the latency results and
 * {@code latency.mean} exclude garbage collection. JMH's primary score, measured by JMH itself, still includes
 * discarded passes. The heap is sized for the recording of 32 nodes: in a local heap sweep, it did not finish within
 * three minutes at 3 GB and filled 4 GB. The configured 6 GB adds a buffer, and no collection fell into the measured
 * calls. The calibration on the benchmark machine discarded no pass.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-07, 686fbf3e8f</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254, JDK 25.0.2, -Xms2g -Xmx2g</li>
 *   <li>Parameter sets: numNodes=4 / numNodes=10</li>
 *   <li>Steady state after: 5 s / 5 s</li>
 *   <li>Noise (latency.mean, CV): 0.25% / 0.18% per 5 s iteration, 0.79% / 0.52% between forks, 0.00% / 0.00%
 *       between runs (3 / 4 of 14 runs quiet)</li>
 *   <li>Expected detectable regression: 0.70% / 0.46% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 3.0 M / 1.8 M; expected stable: mean, p50–p99.99</li>
 *   <li>Runtime: 4.0 / 4.0 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/37584078436, attempts 1–14</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 5,
        jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 15)
@Measurement(iterations = 2, time = 15)
public class ConsensusImplBenchmark {

    /** The seed of the simulation. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of recorded events. */
    private static final int NUMBER_OF_EVENTS = 100_000;

    /** The number of nodes in the simulated network. */
    @Param({"10", "32"})
    public int numNodes;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The configuration of the recorded node. */
    private Configuration configuration;

    /** The recorded events. */
    private List<PlatformEvent> recordedEvents;

    /** The expected number of consensus rounds of the recorded events. */
    private int expectedRounds;

    /** The linked events of the graph. */
    private List<EventImpl> linkedEvents;

    /** The consensus instance used in the benchmark. */
    private Consensus consensus;

    /** The number of decided rounds in the benchmark. */
    private int decidedRounds;

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
        recordedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final Step step : node.steps()) {
            if (step instanceof final Released released) {
                recordedEvents.add(released.event());
            }
        }
        expectedRounds = switch (numNodes) {
            case 10 -> 1786;
            case 32 -> 496;
            default -> throw new IllegalArgumentException("No expected round count for numNodes=" + numNodes);
        };
    }

    /**
     * Prepares the benchmark invocation by linking the recorded events and creating a new consensus instance.
     * This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        linkedEvents = new ArrayList<>(NUMBER_OF_EVENTS);
        for (final PlatformEvent recordedEvent : recordedEvents) {
            final EventImpl linkedEvent = consensusLinker.linkEvent(copy(recordedEvent));
            if (linkedEvent == null) {
                throw new IllegalStateException("Linker should always link each event in this benchmark");
            }
            linkedEvents.add(linkedEvent);
        }

        final Time time = Time.getCurrent();

        consensus = new ConsensusImpl(configuration, time, new NoOpConsensusMetrics(), roster, 0L);
        decidedRounds = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies that the number of decided rounds matches the expected number after the benchmark invocation.
     * This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        if (decidedRounds != expectedRounds) {
            throw new IllegalStateException(
                    "Expected " + expectedRounds + " consensus rounds, but got " + decidedRounds);
        }
        linkedEvents = null;
        consensus = null;
        recorder = null;
    }

    /**
     * Copies the parts of a recorded event that exist before it reaches consensus. The copy drops nGen and the
     * sequence number, so they are copied separately.
     */
    private static PlatformEvent copy(final PlatformEvent event) {
        final PlatformEvent copy = event.copyGossipedData();
        copy.setNGen(event.getNGen());
        copy.setSequenceNumber(event.getSequenceNumber());
        return copy;
    }

    /**
     * Adds all linked events to the consensus instance and records the latency of each call.
     *
     * @param bh the blackhole to consume the results and prevent dead code elimination
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_EVENTS)
    public void calculateConsensus(final Blackhole bh) {
        recorder.startMeasurement();
        for (final EventImpl event : linkedEvents) {
            final List<ConsensusRound> rounds = consensus.addEvent(event);
            // One timestamp per call: each latency also contains the bookkeeping of the previous call.
            recorder.lap();
            decidedRounds += rounds.size();
            bh.consume(rounds);
        }
        recorder.stopMeasurement();
    }
}
