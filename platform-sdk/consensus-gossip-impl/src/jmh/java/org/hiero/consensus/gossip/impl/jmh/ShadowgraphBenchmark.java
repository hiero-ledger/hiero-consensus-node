// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.jmh;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ReservedEventWindow;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowEvent;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.Shadowgraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.TestIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
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

/**
 * Measures the latency of the {@link Shadowgraph}, the graph of recent events that gossip keeps to find out which
 * events a peer is missing: adding an event, moving the event window, and the lookups at the start of a sync.
 *
 * <p>In production, a thread of the wiring framework adds every event once it is written to the pre-consensus event
 * stream, in topological order, and moves the event window after every consensus round. Every sync, on a thread per
 * peer, reserves the event window, reads the tips (the events without a self child), and looks up the peer's tips.
 * The benchmark makes all these calls on one thread, so the lock that every method of the shadowgraph takes is never
 * contended: contention between the sync threads and the wiring thread is not measured. Finding the ancestors of
 * events, most of a sync's decision which events to send, is left to the benchmark of that decision.
 *
 * <p>The calls are replayed from a recording made once per trial: the generated events of a network of
 * {@link #numNodes} nodes in the order generated, and after each event the event windows of the rounds it decided,
 * which a {@link TestIntake} computes by running the production consensus engine on copies of the events. After each
 * event the replay makes one sync lookup. The peer's tips are the latest event of each creator up to
 * {@code numNodes / 2} events later, so that, as in a real sync, some of them are not yet in the shadowgraph.
 *
 * <p>The shadowgraph drops an event when its birth round falls below the expired threshold of the event window, 1000
 * rounds ({@code roundsExpired}) after the event's birth. Until the first events expire, the graph only grows, so each
 * invocation adds the events up to then to a new shadowgraph, outside the measured region. The benchmark methods
 * replay the rest, where the graph keeps a steady size:
 * <ul>
 *   <li>{@link #addEvent()} times every call of {@link Shadowgraph#addEvent(PlatformEvent)};</li>
 *   <li>{@link #updateEventWindow()} times every call of {@link Shadowgraph#updateEventWindow(EventWindow)}, which
 *   also removes the expired events;</li>
 *   <li>{@link #syncLookup()} times every sync lookup: {@link Shadowgraph#reserve()}, {@link Shadowgraph#getTips()},
 *   {@link Shadowgraph#shadows(List)}, and closing the reservation.</li>
 * </ul>
 * Each method runs the other steps of the replay paused ({@link LatencyRecorder#pause()}), so they count neither in
 * its latencies nor in its allocation.
 *
 * <p>Each latency includes one clock read ({@link LatencyRecorder#lap()}). Adding an event and a sync lookup take
 * well under a microsecond, so the clock read is a noticeable part of their latency: on an Apple M4 laptop, 9% to 10%
 * for {@link #addEvent()}, 4% to 7% for {@link #syncLookup()}, and about 1% for {@link #updateEventWindow()}. It is
 * nearly the same for every call, so it does not hide a regression, but it makes one look slightly smaller.
 *
 * <p>The shadowgraph updates the production metrics implementation, {@link DefaultPlatformMetrics}, and the
 * production {@link DefaultIntakeEventCounter}, which counts the events from each peer. The metrics are not started:
 * in production, their periodic updaters and snapshots run on other threads.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per measured call. JMH's primary
 * score divides the time of the whole pass, paused steps included, by the number of measured calls, so it is not the
 * time per call; {@code latency.mean} is. Each invocation's setup ends with {@code System.gc()}; a pass during which a
 * garbage collection runs is discarded and replaced by the next invocation.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 */
@State(Scope.Thread)
@Fork(
        value = 5,
        jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 15)
@Measurement(iterations = 2, time = 15)
public class ShadowgraphBenchmark {

    /** The seed of the event generator. The same seed produces the same recording. */
    private static final long SEED = 0;

    /** The maximum number of other parents of a generated event. */
    private static final int MAX_OTHER_PARENTS = 4;

    /** The number of calls that {@link #addEvent()} measures per invocation. */
    private static final int ADDED_EVENTS = 100000;

    /** The number of calls that {@link #updateEventWindow()} measures per invocation. */
    private static final int WINDOW_UPDATES = 1000;

    /** The number of sync lookups that {@link #syncLookup()} measures per invocation. */
    private static final int SYNC_LOOKUPS = 100000;

    /** The number of nodes in the generated network. */
    @Param({"10", "32"})
    public int numNodes;

    /** The kinds of steps in the recording. */
    private enum Kind {
        ADD_EVENT,
        UPDATE_WINDOW,
        SYNC_LOOKUP
    }

    /**
     * One step of the recording.
     *
     * @param kind          the kind of step
     * @param event         the event to add, for {@link Kind#ADD_EVENT}
     * @param window        the event window to set, for {@link Kind#UPDATE_WINDOW}
     * @param peerTips      the hashes of the peer's tips, for {@link Kind#SYNC_LOOKUP}
     * @param knownPeerTips the number of the peer's tips in the shadowgraph at this step, for {@link Kind#SYNC_LOOKUP}
     */
    private record Step(Kind kind, PlatformEvent event, EventWindow window, List<Hash> peerTips, int knownPeerTips) {}

    /** The configuration of the shadowgraph's metrics. */
    private Configuration configuration;

    /** The roster of the generated network. */
    private RosterWrapper roster;

    /** The recorded steps. */
    private Step[] steps;

    /** The number of steps that each invocation's setup replays: those before the first events expire. */
    private int fillSteps;

    /** The number of steps after the fill that {@link #addEvent()} replays: up to its last measured call. */
    private int addEventSteps;

    /** The number of steps after the fill that {@link #updateEventWindow()} replays: up to its last measured call. */
    private int updateWindowSteps;

    /** The number of steps after the fill that {@link #syncLookup()} replays: up to its last measured call. */
    private int syncLookupSteps;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The intake event counter of the invocation. */
    private DefaultIntakeEventCounter intakeEventCounter;

    /** The shadowgraph of the invocation. */
    private Shadowgraph shadowgraph;

    /** The event window set last. */
    private EventWindow currentWindow;

    /** The number of steps after the fill replayed in the invocation. */
    private int replayedSteps;

    /** The number of events added in the invocation after the fill. */
    private int addedEvents;

    /** The event window reserved by the latest sync lookup. */
    private EventWindow lookupWindow;

    /** The tips returned to the latest sync lookup. */
    private List<ShadowEvent> lookupTips;

    /** The shadow events of the peer's tips returned to the latest sync lookup, null for those not in the graph. */
    private List<ShadowEvent> lookupPeerTips;

    /** The number of sync lookups in the invocation whose result differed from the recording. */
    private int lookupMismatches;

    /** The latency recorder of the invocation. */
    private LatencyRecorder recorder;

    /**
     * Records the steps and checks that the recording is the expected one. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void recordSteps() {
        configuration = new TestConfigBuilder().getOrCreateConfig();
        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .seed(SEED)
                .maxOtherParents(MAX_OTHER_PARENTS)
                .realSignatures(false)
                .numNodes(numNodes)
                .build();
        roster = generator.getRoster();
        steps = record(generator).toArray(new Step[0]);
        fillSteps = firstExpiringWindow();
        addEventSteps = stepsUpTo(Kind.ADD_EVENT, ADDED_EVENTS);
        updateWindowSteps = stepsUpTo(Kind.UPDATE_WINDOW, WINDOW_UPDATES);
        syncLookupSteps = stepsUpTo(Kind.SYNC_LOOKUP, SYNC_LOOKUPS);

        final String summary = summarize();
        final String expected =
                switch (numNodes) {
                    case 10 ->
                        "254657 steps, 124799 events, 5059 event windows, fill 50590 steps (24794 events),"
                                + " 736569 known peer tips, last expired threshold 4058, last e80c38ad6d238e0a4b17e76f2337f568";
                    case 32 ->
                        "442687 steps, 220342 events, 2003 event windows, fill 222158 steps (110578 events),"
                                + " 4240491 known peer tips, last expired threshold 1001, last 1ea651e1b362f20123ee4b1fa8722e14";
                    default -> throw new IllegalArgumentException("No expected recording for numNodes=" + numNodes);
                };
        if (!summary.equals(expected)) {
            throw new IllegalStateException("Expected the recording %s, but got %s".formatted(expected, summary));
        }
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Generates events until the part after the fill holds every measured call, and turns them into steps.
     *
     * @param generator the event generator
     * @return the steps
     */
    private List<Step> record(final GeneratorEventGraphSource generator) {
        final NodeId selfId = roster.nodeIdAtIndex(0);
        final long genesisExpiredThreshold = EventWindow.getGenesisEventWindow().expiredThreshold();
        final int lookahead = numNodes / 2;
        final TestIntake intake = new TestIntake(configuration, roster);
        final List<PlatformEvent> events = new ArrayList<>();
        final List<List<EventWindow>> windows = new ArrayList<>();
        boolean filled = false;
        int eventsAfterFill = 0;
        int windowsAfterFill = 0;
        while (eventsAfterFill <= Math.max(ADDED_EVENTS, SYNC_LOOKUPS) + lookahead
                || windowsAfterFill < WINDOW_UPDATES) {
            final PlatformEvent event = generator.next();
            if (!event.getCreatorId().equals(selfId)) {
                // in production, events from peers carry the peer that sent them; here, their creator
                event.setSenderId(event.getCreatorId());
            }
            events.add(event);
            final int roundsBefore = intake.getConsensusRounds().size();
            intake.addEvent(event.copyGossipedData());
            final List<ConsensusRound> rounds = intake.getConsensusRounds();
            final List<EventWindow> decided = new ArrayList<>();
            for (int i = roundsBefore; i < rounds.size(); i++) {
                final EventWindow window = rounds.get(i).getEventWindow();
                filled |= window.expiredThreshold() > genesisExpiredThreshold;
                if (filled) {
                    windowsAfterFill++;
                }
                decided.add(window);
            }
            windows.add(decided);
            if (filled) {
                eventsAfterFill++;
            }
        }

        final Map<NodeId, Integer> creatorIndex = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            creatorIndex.put(roster.nodeIdAtIndex(i), i);
        }
        final Hash[] peerLatest = new Hash[numNodes];
        final int[] peerLatestIndex = new int[numNodes];
        int peerSeen = -1;
        final List<Step> recording = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            recording.add(new Step(Kind.ADD_EVENT, events.get(i), null, null, 0));
            for (final EventWindow window : windows.get(i)) {
                recording.add(new Step(Kind.UPDATE_WINDOW, null, window, null, 0));
            }
            while (peerSeen < Math.min(i + lookahead, events.size() - 1)) {
                peerSeen++;
                final PlatformEvent peerEvent = events.get(peerSeen);
                final int creator = creatorIndex.get(peerEvent.getCreatorId());
                peerLatest[creator] = peerEvent.getHash();
                peerLatestIndex[creator] = peerSeen;
            }
            final List<Hash> peerTips = new ArrayList<>(numNodes);
            int known = 0;
            for (int creator = 0; creator < numNodes; creator++) {
                if (peerLatest[creator] != null) {
                    peerTips.add(peerLatest[creator]);
                    if (peerLatestIndex[creator] <= i) {
                        known++;
                    }
                }
            }
            recording.add(new Step(Kind.SYNC_LOOKUP, null, null, List.copyOf(peerTips), known));
        }
        return recording;
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Creates a new shadowgraph and adds the events and event windows up to the first expiring event window. This
     * method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                roster.nodeIdAtIndex(0),
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        intakeEventCounter = new DefaultIntakeEventCounter(roster);
        for (final Step step : steps) {
            if (step.kind() == Kind.ADD_EVENT && step.event().getSenderId() != null) {
                intakeEventCounter.eventEnteredIntakePipeline(step.event().getSenderId());
            }
        }
        shadowgraph = new Shadowgraph(metrics, numNodes, intakeEventCounter);
        currentWindow = shadowgraph.getEventWindow();
        for (int i = 0; i < fillSteps; i++) {
            if (steps[i].kind() != Kind.SYNC_LOOKUP) {
                execute(steps[i]);
            }
        }
        replayedSteps = 0;
        addedEvents = 0;
        lookupMismatches = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies the invocation: every event was added, every sync lookup found what the recording expects, and the
     * shadowgraph holds exactly the added events that have not expired. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        final int end = fillSteps + replayedSteps;
        int expectedAdded = 0;
        EventWindow lastWindow = null;
        for (int i = 0; i < end; i++) {
            if (steps[i].kind() == Kind.ADD_EVENT && i >= fillSteps) {
                expectedAdded++;
            } else if (steps[i].kind() == Kind.UPDATE_WINDOW) {
                lastWindow = steps[i].window();
            }
        }
        if (addedEvents != expectedAdded) {
            throw new IllegalStateException(
                    "Expected %d added events, but got %d".formatted(expectedAdded, addedEvents));
        }
        if (lookupMismatches != 0) {
            throw new IllegalStateException("%d sync lookups differed from the recording".formatted(lookupMismatches));
        }
        if (shadowgraph.getEventWindow() != lastWindow) {
            throw new IllegalStateException(
                    "Expected the event window %s, but got %s".formatted(lastWindow, shadowgraph.getEventWindow()));
        }
        for (int i = 0; i < end; i++) {
            if (steps[i].kind() == Kind.ADD_EVENT) {
                final PlatformEvent event = steps[i].event();
                final boolean expected = event.getBirthRound() >= lastWindow.expiredThreshold();
                if ((shadowgraph.getEvent(event.getHash()) != null) != expected) {
                    throw new IllegalStateException("Expected event %s %s the shadowgraph"
                            .formatted(event.getDescriptor(), expected ? "in" : "not in"));
                }
            }
        }
        // the events after the replay did not leave the intake pipeline; then no peer may have one left
        for (int i = end; i < steps.length; i++) {
            if (steps[i].kind() == Kind.ADD_EVENT && steps[i].event().getSenderId() != null) {
                intakeEventCounter.eventExitedIntakePipeline(steps[i].event().getSenderId());
            }
        }
        for (int i = 0; i < numNodes; i++) {
            if (intakeEventCounter.hasUnprocessedEvents(roster.nodeIdAtIndex(i))) {
                throw new IllegalStateException("Node %d has events left in the intake pipeline".formatted(i));
            }
        }
        shadowgraph = null;
        intakeEventCounter = null;
        recorder = null;
    }

    /**
     * Replays the recording after the fill up to the last measured call of {@link Shadowgraph#addEvent(PlatformEvent)}
     * and records the latency of each of these calls.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(ADDED_EVENTS)
    public void addEvent() {
        replay(Kind.ADD_EVENT, addEventSteps);
    }

    /**
     * Replays the recording after the fill up to the last measured call of
     * {@link Shadowgraph#updateEventWindow(EventWindow)} and records the latency of each of these calls.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(WINDOW_UPDATES)
    public void updateEventWindow() {
        replay(Kind.UPDATE_WINDOW, updateWindowSteps);
    }

    /**
     * Replays the recording after the fill up to the last measured sync lookup and records the latency of each sync
     * lookup.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(SYNC_LOOKUPS)
    public void syncLookup() {
        replay(Kind.SYNC_LOOKUP, syncLookupSteps);
    }

    /**
     * Replays the steps after the fill, times the steps of the measured kind, and pauses the recorder for the others.
     *
     * @param measured  the kind of step to time
     * @param stepCount the number of steps to replay
     */
    private void replay(final Kind measured, final int stepCount) {
        recorder.startMeasurement();
        recorder.pause();
        boolean paused = true;
        for (int i = fillSteps; i < fillSteps + stepCount; i++) {
            final Step step = steps[i];
            if (step.kind() == measured) {
                if (paused) {
                    recorder.resume();
                    paused = false;
                }
                execute(step);
                recorder.lap();
                // the next step is never a measured one, so the check counts in no latency
                if (step.kind() == Kind.SYNC_LOOKUP) {
                    checkLookup(step);
                }
            } else {
                if (!paused) {
                    recorder.pause();
                    paused = true;
                }
                execute(step);
                if (step.kind() == Kind.SYNC_LOOKUP) {
                    checkLookup(step);
                }
            }
        }
        if (paused) {
            recorder.resume();
        }
        recorder.stopMeasurement();
        replayedSteps = stepCount;
    }

    /** Calls the shadowgraph as the recorded step does. */
    private void execute(final Step step) {
        switch (step.kind()) {
            case ADD_EVENT -> {
                if (shadowgraph.addEvent(step.event())) {
                    addedEvents++;
                }
            }
            case UPDATE_WINDOW -> {
                shadowgraph.updateEventWindow(step.window());
                currentWindow = step.window();
            }
            case SYNC_LOOKUP -> lookUpForSync(step);
        }
    }

    /**
     * Makes the shadowgraph calls of a sync before its decision which events to send, as
     * {@code RpcPeerHandler#sendSyncData} and {@code RpcPeerHandler#sendKnownTips} do, and keeps the results for
     * {@link #checkLookup(Step)}.
     */
    private void lookUpForSync(final Step step) {
        try (final ReservedEventWindow reservation = shadowgraph.reserve()) {
            lookupWindow = reservation.getEventWindow();
            lookupTips = shadowgraph.getTips();
            lookupPeerTips = shadowgraph.shadows(step.peerTips());
        }
    }

    /**
     * Counts a sync lookup whose results differ from the recording. Called after the lookup's latency is recorded, so
     * that the check is not part of it.
     */
    private void checkLookup(final Step step) {
        int known = 0;
        for (final ShadowEvent peerTip : lookupPeerTips) {
            if (peerTip != null) {
                known++;
            }
        }
        if (lookupWindow != currentWindow || lookupTips.size() != numNodes || known != step.knownPeerTips()) {
            lookupMismatches++;
        }
    }

    /** Returns the index of the first event window that expires events. */
    private int firstExpiringWindow() {
        final long genesisExpiredThreshold = EventWindow.getGenesisEventWindow().expiredThreshold();
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].kind() == Kind.UPDATE_WINDOW
                    && steps[i].window().expiredThreshold() > genesisExpiredThreshold) {
                return i;
            }
        }
        throw new IllegalStateException("No event window of the recording expires events");
    }

    /** Returns the number of steps after the fill up to and including the given occurrence of a kind of step. */
    private int stepsUpTo(final Kind kind, final int occurrence) {
        int seen = 0;
        for (int i = fillSteps; i < steps.length; i++) {
            if (steps[i].kind() == kind && ++seen == occurrence) {
                return i + 1 - fillSteps;
            }
        }
        throw new IllegalStateException(
                "The recording has only %d steps of kind %s after the fill".formatted(seen, kind));
    }

    /** Summarizes the recording in a line that changes with every change of the recorded steps. */
    private String summarize() {
        int events = 0;
        int windows = 0;
        int fillEvents = 0;
        int knownPeerTips = 0;
        Hash lastEvent = null;
        EventWindow lastWindow = null;
        for (int i = 0; i < steps.length; i++) {
            switch (steps[i].kind()) {
                case ADD_EVENT -> {
                    events++;
                    fillEvents += i < fillSteps ? 1 : 0;
                    lastEvent = steps[i].event().getHash();
                }
                case UPDATE_WINDOW -> {
                    windows++;
                    lastWindow = steps[i].window();
                }
                case SYNC_LOOKUP -> knownPeerTips += steps[i].knownPeerTips();
            }
        }
        return ("%d steps, %d events, %d event windows, fill %d steps (%d events), %d known peer tips,"
                        + " last expired threshold %d, last %s")
                .formatted(
                        steps.length,
                        events,
                        windows,
                        fillSteps,
                        fillEvents,
                        knownPeerTips,
                        lastWindow.expiredThreshold(),
                        lastEvent.toHex(16));
    }
}
