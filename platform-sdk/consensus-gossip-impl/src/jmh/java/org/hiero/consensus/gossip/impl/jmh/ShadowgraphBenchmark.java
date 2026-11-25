// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.jmh;

import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.benchmark.tools.histogram.LappingLatencyHistogramRecorder;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.Shadowgraph;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
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

/**
 * Measures the latency of the {@link Shadowgraph}, the graph of recent events that gossip keeps to find out which
 * events a peer is missing: adding an event and moving the event window.
 *
 * <p>In production, a thread of the wiring framework adds every event once it is written to the pre-consensus event
 * stream, in topological order, and moves the event window after every consensus round. The benchmark makes these calls
 * on one thread, so the lock that every method of the shadowgraph takes is never contended: contention with the sync
 * threads is not measured.
 *
 * <p>{@link FalconRecorder} simulates a network of {@link #numNodes} nodes once per trial, on the ping times of
 * mainnet, and records one of its nodes. The benchmark replays what gossip gets on that node: the events its orphan
 * buffer released, its own included, in that order, and between them the event window of each consensus round.
 *
 * <p>The shadowgraph drops an event when its birth round falls below the expired threshold of the event window, 1000
 * rounds ({@code roundsExpired}) after the event's birth. Until the first events expire, the graph only grows, so each
 * invocation adds the events up to then to a new shadowgraph, outside the measured region. Each benchmark method
 * replays all the rest, where the graph keeps a steady size, and times one kind of call:
 * <ul>
 *   <li>{@link #addEvent()} times every call of {@link Shadowgraph#addEvent(PlatformEvent)};</li>
 *   <li>{@link #updateEventWindow()} times every call of {@link Shadowgraph#updateEventWindow(EventWindow)}, which
 *   also removes the expired events.</li>
 * </ul>
 * The replay runs in segments of consecutive calls of one kind. Each method times the segments of its kind, each as one
 * stretch of laps ({@link LappingLatencyHistogramRecorder#startLaps()}), and runs the other segments outside the laps,
 * so they count neither in its latencies nor in its allocation.
 *
 * <p>Each latency includes one clock read ({@link LappingLatencyHistogramRecorder#lap()}). Adding an event takes well
 * under a microsecond, so the clock read is a noticeable part of its latency. On an Apple M4 laptop, one more clock
 * read after each call added 11 to 12 ns: 4% to 5% of {@link #addEvent()} and under 0.5% of
 * {@link #updateEventWindow()}. It is nearly the same for every call, so it does not hide a regression, but it makes
 * one look slightly smaller.
 *
 * <p>The shadowgraph updates the production metrics implementation, {@link DefaultPlatformMetrics}, and the
 * production {@link DefaultIntakeEventCounter}, which counts the events from each peer by the sender they had in the
 * simulation. The metrics are not started: in production, their periodic updaters and snapshots run on other threads.
 *
 * <p>The recording runs the event creator and the consensus code, so a change to them can change the events, and the
 * latencies before and after it are not comparable.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per timed call. JMH's primary score
 * is the time of a whole invocation, the untimed calls included; the number of timed calls per invocation differs
 * between the parameter sets. Each invocation's setup ends with {@code System.gc()}; an invocation during whose
 * measured part a garbage collection runs is discarded and replaced by the next invocation. The heap is sized for the
 * recording of 28 nodes, which keeps more than 5 GB live while it runs: in a local heap sweep, it needed 27 full
 * collections at 6 GB and none at 8 GB.
 *
 * <p>The two benchmark methods differ in how many calls they time per second, so each has its own {@code @Fork},
 * {@code @Warmup}, and {@code @Measurement}, and its calibration block in its Javadoc. On the benchmark machine, each
 * fork settles at a latency level of its own: the forks differ by 2% to 4%, many times more than the iterations of one
 * fork, and that sets how small a regression one night detects. The cause is open (#27940).
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
public class ShadowgraphBenchmark {

    /** The seed of the recording. The same seed gives the same recording. */
    private static final long SEED = 0;

    /**
     * The number of events the recorded node releases from its orphan buffer, its own included, before the recording
     * ends. It covers the 1000 rounds until the first events expire and about 1000 rounds after them at 28 nodes.
     */
    private static final int EVENTS = 350_000;

    /** The number of nodes in the simulated network. */
    @Param({"10", "28"})
    public int numNodes;

    /** What one call of the replay does. */
    private enum Kind {
        /** Adds an event to the shadowgraph. */
        ADD_EVENT,
        /** Sets a new event window. */
        UPDATE_WINDOW
    }

    /**
     * One call of the replay.
     *
     * @param kind   what the call does
     * @param event  the event to add, for {@link Kind#ADD_EVENT}
     * @param window the event window to set, for {@link Kind#UPDATE_WINDOW}
     */
    private record Call(Kind kind, PlatformEvent event, EventWindow window) {}

    /**
     * Consecutive calls of the replay of one kind.
     *
     * @param kind  the kind of all calls
     * @param calls the calls, in order
     */
    private record Segment(Kind kind, Call[] calls) {}

    /** The configuration of the recorded node. */
    private Configuration configuration;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The ID of the node whose shadowgraph is replayed. */
    private NodeId nodeId;

    /** The calls of the replay, in order. */
    private Call[] calls;

    /** The number of calls that each invocation's setup makes: those before the first events expire. */
    private int numCallsBeforeFirstExpire;

    /** The calls after those of the setup, in segments of consecutive calls of one kind. */
    private Segment[] segments;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The intake event counter of the invocation. */
    private DefaultIntakeEventCounter intakeEventCounter;

    /** The shadowgraph of the invocation. */
    private Shadowgraph shadowgraph;

    /** The number of events added in the invocation after the fill. */
    private int addedEvents;

    /** The latency recorder of the invocation. */
    private LappingLatencyHistogramRecorder recorder;

    /**
     * Records the network and turns what the recorded node's gossip gets into the calls of the replay. This method is
     * called once per trial.
     */
    @Setup(Level.Trial)
    public void recordCalls() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, EVENTS).build());
        final NodeRecording node = recording.nodes().getFirst();
        configuration = node.configuration();
        roster = recording.roster();
        nodeId = node.nodeId();
        final List<Call> callList = new ArrayList<>();
        for (final Step step : node.steps()) {
            switch (step) {
                case Released released -> callList.add(new Call(Kind.ADD_EVENT, released.event(), null));
                case NewEventWindow window -> callList.add(new Call(Kind.UPDATE_WINDOW, null, window.window()));
                default -> {
                    // received events, creation attempts, and platform status changes do not reach the shadowgraph
                }
            }
        }
        calls = callList.toArray(new Call[0]);
        numCallsBeforeFirstExpire = firstExpiringWindow();
        segments = segmentsFrom(numCallsBeforeFirstExpire);
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
     * Creates a new shadowgraph and makes the calls up to the first expiring event window. This method is called once
     * per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                nodeId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        intakeEventCounter = new DefaultIntakeEventCounter(roster);
        for (final Call call : calls) {
            if (call.kind() == Kind.ADD_EVENT && call.event().getSenderId() != null) {
                intakeEventCounter.eventEnteredIntakePipeline(call.event().getSenderId());
            }
        }
        shadowgraph = new Shadowgraph(metrics, numNodes, intakeEventCounter);
        for (int i = 0; i < numCallsBeforeFirstExpire; i++) {
            execute(calls[i]);
        }
        addedEvents = 0;
        recorder = LatencyProfiler.lappingRecorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies the invocation: every event was added, the shadowgraph holds exactly the events that have not expired,
     * and every event left the intake pipeline. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        int expectedAdded = 0;
        EventWindow lastWindow = null;
        for (int i = 0; i < calls.length; i++) {
            if (calls[i].kind() == Kind.ADD_EVENT && i >= numCallsBeforeFirstExpire) {
                expectedAdded++;
            } else if (calls[i].kind() == Kind.UPDATE_WINDOW) {
                lastWindow = calls[i].window();
            }
        }
        if (addedEvents != expectedAdded) {
            throw new IllegalStateException(
                    "Expected %d added events, but got %d".formatted(expectedAdded, addedEvents));
        }
        if (shadowgraph.getEventWindow() != lastWindow) {
            throw new IllegalStateException(
                    "Expected the event window %s, but got %s".formatted(lastWindow, shadowgraph.getEventWindow()));
        }
        for (final Call call : calls) {
            if (call.kind() == Kind.ADD_EVENT) {
                final PlatformEvent event = call.event();
                final boolean isExpired = event.getBirthRound() < lastWindow.expiredThreshold();
                if ((shadowgraph.getEvent(event.getHash()) == null) != isExpired) {
                    throw new IllegalStateException("Expected event %s %s the shadowgraph"
                            .formatted(event.getDescriptor(), isExpired ? "not in" : "in"));
                }
            }
        }
        for (int i = 0; i < roster.size(); i++) {
            if (intakeEventCounter.hasUnprocessedEvents(roster.nodeIdAtIndex(i))) {
                throw new IllegalStateException("Node %d has events left in the intake pipeline"
                        .formatted(roster.nodeIdAtIndex(i).id()));
            }
        }
        shadowgraph = null;
        intakeEventCounter = null;
        recorder = null;
    }

    /**
     * Replays the calls after the fill and records the latency of each call of
     * {@link Shadowgraph#addEvent(PlatformEvent)}.
     *
     * <h2>Calibration</h2>
     * <ul>
     *   <li>Date / commit: 2026-11-18, e696b4b6f9</li>
     *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms8g -Xmx8g -XX:+AlwaysPreTouch</li>
     *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
     *   <li>Steady state after: 5 s / 5 s</li>
     *   <li>Noise (latency.mean, CV): 0.46% / 0.65% per 5 s iteration, 3.82% / 4.21% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
     *   <li>Expected detectable regression: 3.35% / 3.70% (95 % CI half-width of one run)</li>
     *   <li>Samples per iteration: 3.9 M / 2.3 M; expected stable: mean, p50, p90, p99, p99.9, p99.99 / mean, p90, p99, p99.9, p99.99</li>
     *   <li>Runtime: 4.8 / 21.8 min per parameter set</li>
     *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/44132468757, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/44280814515</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Fork(
            value = 5,
            jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
    @Warmup(iterations = 1, time = 10)
    @Measurement(iterations = 2, time = 5)
    public void addEvent() {
        replay(Kind.ADD_EVENT);
    }

    /**
     * Replays the calls after the fill and records the latency of each call of
     * {@link Shadowgraph#updateEventWindow(EventWindow)}.
     *
     * <p>With 28 nodes, the forks allocate either about 66 or about 93 B per call and differ by a few percent in latency;
     * the noise between forks in the block includes it.
     *
     * <p>With 28 nodes, about one fork in five runs 10% to 30% slower with the same allocation. In these forks, C2 does
     * not inline {@code HashMap.remove} into the removal of each expired event, because the JVM has already compiled it
     * for all its callers into code larger than {@code InlineSmallCode}. Whether it has depends on the order of
     * compilations, in production too. The noise between forks in the block includes it.
     *
     * <h2>Calibration</h2>
     * <ul>
     *   <li>Date / commit: 2026-11-18, e696b4b6f9</li>
     *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms8g -Xmx8g -XX:+AlwaysPreTouch</li>
     *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
     *   <li>Steady state after: 5 s / 5 s</li>
     *   <li>Noise (latency.mean, CV): 0.46% / 0.40% per 10 s iteration, 1.66% / 9.58% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
     *   <li>Expected detectable regression: 1.48% / 8.36% (95 % CI half-width of one run)</li>
     *   <li>Samples per iteration: 147 k / 27 k; expected stable: mean, p50, p90, p99, p99.9 / -; unmeasured: p99.99 / p99.9, p99.99</li>
     *   <li>Runtime: 5.6 / 22.7 min per parameter set</li>
     *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/44132491036, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/44280831984</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Fork(
            value = 5,
            jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
    @Warmup(iterations = 1, time = 10)
    @Measurement(iterations = 2, time = 10)
    public void updateEventWindow() {
        replay(Kind.UPDATE_WINDOW);
    }

    /**
     * Replays the calls after the fill, segment by segment, and times the calls of the measured kind.
     *
     * @param measured the kind of call to time
     */
    private void replay(final Kind measured) {
        recorder.prepareToMeasure();
        for (final Segment segment : segments) {
            if (segment.kind() == measured) {
                recorder.startLaps();
                for (final Call call : segment.calls()) {
                    execute(call);
                    recorder.lap();
                }
                recorder.stopLaps();
            } else {
                for (final Call call : segment.calls()) {
                    execute(call);
                }
            }
        }
        recorder.finalizeMeasurement();
    }

    /** Calls the shadowgraph as the recorded call does. */
    private void execute(final Call call) {
        switch (call.kind()) {
            case ADD_EVENT -> {
                if (shadowgraph.addEvent(call.event())) {
                    addedEvents++;
                }
            }
            case UPDATE_WINDOW -> shadowgraph.updateEventWindow(call.window());
        }
    }

    /** Splits the calls from the given index on into segments of consecutive calls of one kind. */
    private Segment[] segmentsFrom(final int from) {
        final List<Segment> result = new ArrayList<>();
        int start = from;
        while (start < calls.length) {
            final Kind kind = calls[start].kind();
            int end = start + 1;
            while (end < calls.length && calls[end].kind() == kind) {
                end++;
            }
            result.add(new Segment(kind, Arrays.copyOfRange(calls, start, end)));
            start = end;
        }
        return result.toArray(new Segment[0]);
    }

    /** Returns the index of the first event window that expires events. */
    private int firstExpiringWindow() {
        final long genesisExpiredThreshold = EventWindow.getGenesisEventWindow().expiredThreshold();
        for (int i = 0; i < calls.length; i++) {
            if (calls[i].kind() == Kind.UPDATE_WINDOW
                    && calls[i].window().expiredThreshold() > genesisExpiredThreshold) {
                return i;
            }
        }
        throw new IllegalStateException("No event window of the recording expires events");
    }
}
