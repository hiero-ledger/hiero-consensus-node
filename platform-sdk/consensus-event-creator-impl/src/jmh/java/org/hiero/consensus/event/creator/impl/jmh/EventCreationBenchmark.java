// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.tipset.DefaultTipsetTracker;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.CreationAttempt;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.PlatformStatusChange;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
import org.hiero.otter.fixtures.util.SecureRandomBuilder;
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
 * Measures the latency of event creation on one node, without signing: the calls of
 * {@link DefaultEventCreationManager}, with the {@link TipsetEventCreator} behind it, that create an event and that
 * register an event of another node. Most of their time goes to the tipset computation in {@link TipsetEventCreator}
 * and {@link DefaultTipsetTracker}.
 *
 * <p>In production, the two kinds of calls interleave: a node registers every event it receives and creates its own
 * events in between. The benchmark therefore replays what one node of a simulated network did. {@link FalconRecorder}
 * records it once per trial: a network of {@link #numNodes} nodes with the ping times of mainnet and transactions of
 * the shape of mainnet's, of which one node is recorded. The recording holds every call the node's event creation
 * manager got, at its simulated time, until the node has received 100,000 events, its own included. Each invocation
 * replays all calls, in order, into a new manager built like the recorded one, on a fake clock set to the time of
 * each call. The replay therefore creates exactly the events of the recording, with the same hashes, which the
 * recorded events of the other nodes refer to as parents.
 *
 * <p>Each benchmark method times one kind of call and runs the rest of the replay paused
 * ({@link LatencyRecorder#pause()}), so that the rest counts neither in its latencies nor in its allocation:
 * <ul>
 *   <li>{@link #createEvent()} times every call of {@link DefaultEventCreationManager#maybeCreateEvent()} that creates
 *   an event. The node's heartbeat makes the call every 10 ms, and the creation rules refuse most calls at once, mostly
 *   because of the maximum creation rate. These calls are replayed but not timed.</li>
 *   <li>{@link #registerEvent()} times every call of {@link DefaultEventCreationManager#registerEvent(PlatformEvent)}
 *   with an event of another node. Registering the node's own events is not timed.</li>
 * </ul>
 * Setting a new event window, about once per round, and a new platform status are part of neither.
 *
 * <p>Timing starts with the first event window that makes events ancient, in round 29 of both recordings. Before
 * it, every pass repeats what a node goes through once after it starts: its first creation takes many times as long as
 * the later ones, and the event creator's collections grow from empty. Timed, these calls would set the slowest
 * percentiles. They are replayed paused.
 *
 * <p>Each latency includes one clock read ({@link LatencyRecorder#lap()}). A registration takes well under a
 * microsecond, so the clock read is a few percent of its latency: in profiles on an Apple M4 laptop, 3% to 5%. It is
 * nearly the same for every call, so it does not hide a regression.
 *
 * <p>Each created event gets the transactions it got in the recording, prepared before the passes. In production, the
 * transaction pool hands them over; it is not part of this benchmark. Signing is replaced by an empty signature;
 * {@code EventSigningBenchmark} measures it. Nothing in the timed calls reads a signature: the hash of an event covers
 * the event without its signature, and registering an event reads only its descriptor and parents. Hashing the new
 * event is part of {@link DefaultEventCreationManager#maybeCreateEvent()} and is measured.
 *
 * <p>The manager updates the production metrics implementation, {@link DefaultPlatformMetrics}, so the benchmark
 * measures the cost of the metric updates. The metrics are not started: in production, their periodic updaters and
 * snapshots run on other threads. Their executor only announces each new metric, while the setup creates the manager.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per timed call. JMH's primary score
 * is the time of a whole pass, the paused calls included; the number of timed calls per pass differs between the
 * parameter sets. Each invocation's setup ends with {@code System.gc()}; a pass during which a garbage collection runs
 * is discarded and replaced by the next invocation.
 *
 * <p>The two benchmark methods differ in how many calls they time per second and in how much their forks differ, so
 * each has its own {@code @Fork}, {@code @Warmup}, and {@code @Measurement}, and its calibration block in its Javadoc.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
public class EventCreationBenchmark {

    /** The seed of the recording. The same seed gives the same recording. */
    private static final long SEED = 0;

    /** The number of events the recorded node receives, its own included, before the recording ends. */
    private static final int EVENTS = 100_000;

    /** Signs nothing. A separate benchmark measures signing. */
    private static final BytesSigner NO_SIGNATURE = _ -> Bytes.EMPTY;

    /** The number of nodes in the simulated network. */
    @Param({"10", "28"})
    public int numNodes;

    /** What one call of the recording does. */
    private enum Kind {
        /** A creation attempt that created an event. */
        CREATE,
        /** A creation attempt that created none. */
        ATTEMPT,
        /** The registration of an event of another node. */
        REGISTER_PEER,
        /** The registration of one of the node's own events, after it passed the node's intake. */
        REGISTER_SELF,
        /** A new event window. */
        SET_EVENT_WINDOW,
        /** A new platform status. */
        SET_PLATFORM_STATUS
    }

    /**
     * One call of the recording.
     *
     * @param kind what the call does
     * @param nanos the simulated time of the call, in nanoseconds since the node built its event creator
     * @param event the event the call registers, or the event it created in the recording; null otherwise
     * @param window the event window the call sets, or null
     * @param status the platform status the call sets, or null
     */
    private record Call(
            Kind kind,
            long nanos,
            @Nullable PlatformEvent event,
            @Nullable EventWindow window,
            @Nullable PlatformStatus status) {}

    /** The configuration of the recorded node. */
    private Configuration configuration;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The ID of the recorded node. */
    private NodeId nodeId;

    /** The simulated time at which the recorded node built its event creator. */
    private Instant startTime;

    /** The seed of the recorded node's event creator. */
    private long creatorSeed;

    /** The calls of the recording, in order. */
    private Call[] calls;

    /** The index in {@link #calls} of the first event window that makes events ancient, where timing starts. */
    private int firstTimedCall;

    /** The transactions of each created event of the recording, in order of creation. */
    private List<List<TimestampedTransaction>> transactions;

    /** The executor the metrics require. It only announces new metrics, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The fake clock of the replay. */
    private FakeTime time;

    /**
     * The time of {@link #time}, in nanoseconds since {@link #startTime}. Kept here, because reading it from the clock
     * allocates a {@link Duration}.
     */
    private long elapsedNanos;

    /** The event creation manager of the replay. */
    private DefaultEventCreationManager manager;

    /** The result of each creation attempt of the invocation, null where none was created. */
    private PlatformEvent[] results;

    /** The number of creation attempts in the invocation so far. */
    private int attempts;

    /** The index of the transactions that the next created event gets. */
    private int nextTransactions;

    /** The latency recorder of the invocation. */
    private LatencyRecorder recorder;

    /**
     * Records the calls of one node. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void recordCalls() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, EVENTS).build());
        final NodeRecording nodeRecording = recording.nodes().getFirst();
        configuration = nodeRecording.configuration();
        roster = recording.roster();
        nodeId = nodeRecording.nodeId();
        startTime = nodeRecording.startTime();
        creatorSeed = nodeRecording.creatorSeed();

        final List<Call> recorded = new ArrayList<>();
        transactions = new ArrayList<>();
        for (final Step step : nodeRecording.steps()) {
            final long nanos = Duration.between(startTime, step.time()).toNanos();
            switch (step) {
                case CreationAttempt a
                when a.created() != null -> {
                    recorded.add(new Call(Kind.CREATE, nanos, a.created(), null, null));
                    transactions.add(a.created().getGossipEvent().transactions().stream()
                            .map(transaction -> new TimestampedTransaction(transaction, a.time()))
                            .toList());
                }
                case CreationAttempt _ -> recorded.add(new Call(Kind.ATTEMPT, nanos, null, null, null));
                case Released r -> {
                    final Kind kind = r.event().getCreatorId().equals(nodeId) ? Kind.REGISTER_SELF : Kind.REGISTER_PEER;
                    recorded.add(new Call(kind, nanos, r.event(), null, null));
                }
                case NewEventWindow w -> recorded.add(new Call(Kind.SET_EVENT_WINDOW, nanos, null, w.window(), null));
                case PlatformStatusChange s ->
                    recorded.add(new Call(Kind.SET_PLATFORM_STATUS, nanos, null, null, s.status()));
                case Received _ -> {
                    // an input of the node's intake, not a call of its event creation manager
                }
            }
        }
        calls = recorded.toArray(new Call[0]);
        results = new PlatformEvent[count(Kind.CREATE) + count(Kind.ATTEMPT)];
        firstTimedCall = firstAncientWindow();

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
     * Builds a new event creation manager like the recorded node's, with the same seed. This method is called once per
     * invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        time = new FakeTime(startTime, Duration.ZERO);
        elapsedNanos = 0;
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                nodeId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final TipsetEventCreator creator = new TipsetEventCreator(
                configuration,
                metrics,
                time,
                new SecureRandomBuilder(creatorSeed).get(),
                NO_SIGNATURE,
                roster,
                nodeId,
                () -> transactions.get(nextTransactions++),
                new DefaultTipsetTracker(time, nodeId, roster));
        manager = new DefaultEventCreationManager(configuration, metrics, time, () -> false, creator, roster, nodeId);
        Arrays.fill(results, null);
        attempts = 0;
        nextTransactions = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies that every creation attempt of the invocation had the result of the recording, the same event by its
     * hash or none, and that the last created event is the expected one. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        int attempt = 0;
        Hash lastCreated = null;
        for (final Call call : calls) {
            if (call.kind() == Kind.CREATE || call.kind() == Kind.ATTEMPT) {
                final Hash expected = call.event() == null ? null : call.event().getHash();
                final Hash actual = results[attempt] == null ? null : results[attempt].getHash();
                if (!Objects.equals(expected, actual)) {
                    throw new IllegalStateException(
                            "Creation attempt %d: expected %s, but got %s".formatted(attempt, expected, actual));
                }
                if (actual != null) {
                    lastCreated = actual;
                }
                attempt++;
            }
        }
        if (attempt != attempts) {
            throw new IllegalStateException("Expected %d creation attempts, but got %d".formatted(attempt, attempts));
        }
        final String expectedLastCreated = expectedLastCreated();
        if (lastCreated == null || !lastCreated.toHex(16).equals(expectedLastCreated)) {
            throw new IllegalStateException("Expected the last created event %s, but got %s"
                    .formatted(expectedLastCreated, lastCreated == null ? "none" : lastCreated.toHex(16)));
        }
        manager = null;
        recorder = null;
    }

    /**
     * Returns the start of the hash of the last event the replay creates. It changes with any change of the recording
     * before the last creation, for example a change of the simulation; results before and after such a change are not
     * comparable.
     */
    private String expectedLastCreated() {
        return switch (numNodes) {
            case 10 -> "f24e81d747d505dd38d12b3495c48125";
            case 28 -> "976448271408d71c3f7ef40f4e048a76";
            default -> throw new IllegalArgumentException("No expected last event for numNodes=" + numNodes);
        };
    }

    /**
     * Replays the recording and records the latency of each call that creates an event.
     *
     * <h2>Calibration</h2>
     * <ul>
     *   <li>Date / commit: 2026-11-03, 541e76d535</li>
     *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms6g -Xmx6g -XX:+AlwaysPreTouch</li>
     *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
     *   <li>Steady state after: 5 s / 5 s</li>
     *   <li>Noise (latency.mean, CV): 0.32% / 0.20% per 10 s iteration, 1.45% / 1.22% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
     *   <li>Expected detectable regression: 1.66% / 1.39% (95 % CI half-width of one run)</li>
     *   <li>Samples per iteration: 518 k / 188 k; expected stable: mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9; unmeasured: p99.99 / p99.99</li>
     *   <li>Runtime: 2.4 / 6.6 min per parameter set</li>
     *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41704363692, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41720353542</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(
            value = 3,
            jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
    @Warmup(iterations = 1, time = 10)
    @Measurement(iterations = 2, time = 10)
    public void createEvent() {
        replay(Kind.CREATE);
    }

    /**
     * Replays the recording and records the latency of each registration of an event of another node.
     *
     * <h2>Calibration</h2>
     * <ul>
     *   <li>Date / commit: 2026-11-03, 541e76d535</li>
     *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms6g -Xmx6g -XX:+AlwaysPreTouch</li>
     *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
     *   <li>Steady state after: 5 s / 5 s</li>
     *   <li>Noise (latency.mean, CV): 0.30% / 1.07% per 5 s iteration, 2.70% / 6.23% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
     *   <li>Expected detectable regression: 1.67% / 3.85% (95 % CI half-width of one run)</li>
     *   <li>Samples per iteration: 2.3 M / 2.5 M; expected stable: mean, p50, p90, p99, p99.9, p99.99 / mean, p99, p99.99</li>
     *   <li>Runtime: 6.2 / 20.2 min per parameter set</li>
     *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41704363692, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/41720368042</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(
            value = 10,
            jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
    @Warmup(iterations = 1, time = 10)
    @Measurement(iterations = 2, time = 5)
    public void registerEvent() {
        replay(Kind.REGISTER_PEER);
    }

    /**
     * Replays the recording, times the calls of the measured kind from {@link #firstTimedCall} on, and pauses the
     * recorder for the others.
     *
     * @param measured the kind of call to time
     */
    private void replay(final Kind measured) {
        recorder.startMeasurement();
        recorder.pause();
        for (int i = 0; i < calls.length; i++) {
            final Call call = calls[i];
            if (i >= firstTimedCall && call.kind() == measured) {
                // While not paused, advancing the clock counts in the latency; it is a single addition.
                advanceTime(call);
                if (recorder.isPaused()) {
                    recorder.resume();
                }
                execute(call);
                recorder.lap();
            } else {
                if (!recorder.isPaused()) {
                    recorder.pause();
                }
                advanceTime(call);
                execute(call);
            }
        }
        if (recorder.isPaused()) {
            recorder.resume();
        }
        recorder.stopMeasurement();
    }

    /** Sets the fake clock to the time of the call. */
    private void advanceTime(final Call call) {
        final long delta = call.nanos() - elapsedNanos;
        if (delta != 0) {
            time.tick(delta);
            elapsedNanos = call.nanos();
        }
    }

    /** Calls the manager as the recorded node's manager was called. */
    private void execute(final Call call) {
        switch (call.kind()) {
            case CREATE, ATTEMPT -> results[attempts++] = manager.maybeCreateEvent();
            case REGISTER_PEER, REGISTER_SELF -> manager.registerEvent(requireNonNull(call.event()));
            case SET_EVENT_WINDOW -> manager.setEventWindow(requireNonNull(call.window()));
            case SET_PLATFORM_STATUS -> manager.updatePlatformStatus(requireNonNull(call.status()));
        }
    }

    /**
     * Returns the index of the first event window whose ancient threshold is above the genesis window's, so that events
     * have started to become ancient.
     */
    private int firstAncientWindow() {
        final long genesisThreshold = EventWindow.getGenesisEventWindow().ancientThreshold();
        for (int i = 0; i < calls.length; i++) {
            final EventWindow window = calls[i].window();
            if (window != null && window.ancientThreshold() > genesisThreshold) {
                return i;
            }
        }
        throw new IllegalStateException("No event window of the recording makes events ancient");
    }

    /** Returns the number of calls of a kind in the recording. */
    private int count(final Kind kind) {
        return (int) Arrays.stream(calls).filter(call -> call.kind() == kind).count();
    }
}
