// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.jmh;

import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ReservedEventWindow;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowEvent;
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
 * events a peer is missing: adding an event, moving the event window, and the lookups at the start of a sync.
 *
 * <p>In production, a thread of the wiring framework adds every event once it is written to the pre-consensus event
 * stream, in topological order, and moves the event window after every consensus round. Every sync, on a thread per
 * peer, reserves the event window, reads the tips (the events without a self child), and looks up the peer's tips.
 * The benchmark makes all these calls on one thread, so the lock that every method of the shadowgraph takes is never
 * contended: contention between the sync threads and the wiring thread is not measured. Finding the ancestors of
 * events, most of a sync's decision which events to send, is left to the benchmark of that decision.
 *
 * <p>{@link FalconRecorder} simulates a network of {@link #numNodes} nodes once per trial, on the ping times of
 * mainnet, and records two of its nodes. The benchmark replays what gossip gets on the first of them: the events its
 * orphan buffer released, its own included, in that order, and between them the event window of each consensus round.
 * After each event, the replay makes one sync lookup with the second node as the peer. The peer's tips are the newest
 * event of each creator that it had released at that time. Whether the first node already has them therefore follows
 * from the simulated network, as in a real sync: it has about half of them.
 *
 * <p>The shadowgraph drops an event when its birth round falls below the expired threshold of the event window, 1000
 * rounds ({@code roundsExpired}) after the event's birth. Until the first events expire, the graph only grows, so each
 * invocation adds the events up to then to a new shadowgraph, outside the measured region. Each benchmark method
 * replays all the rest, where the graph keeps a steady size, and times one kind of call:
 * <ul>
 *   <li>{@link #addEvent()} times every call of {@link Shadowgraph#addEvent(PlatformEvent)};</li>
 *   <li>{@link #updateEventWindow()} times every call of {@link Shadowgraph#updateEventWindow(EventWindow)}, which
 *   also removes the expired events;</li>
 *   <li>{@link #syncLookup()} times every sync lookup: {@link Shadowgraph#reserve()}, {@link Shadowgraph#getTips()},
 *   {@link Shadowgraph#shadows(List)}, and closing the reservation.</li>
 * </ul>
 * Each method runs the other calls of the replay paused ({@link LatencyRecorder#pause()}), so they count neither in
 * its latencies nor in its allocation.
 *
 * <p>Each latency includes one clock read ({@link LatencyRecorder#lap()}). Adding an event and a sync lookup take
 * well under a microsecond, so the clock read is a noticeable part of their latency. On an Apple M4 laptop, one more
 * clock read after each call added 11 to 12 ns: 4% to 5% of {@link #addEvent()}, 2% to 5% of {@link #syncLookup()},
 * and under 0.5% of {@link #updateEventWindow()}. It is nearly the same for every call, so it does not hide a
 * regression, but it makes one look slightly smaller.
 *
 * <p>The shadowgraph updates the production metrics implementation, {@link DefaultPlatformMetrics}, and the
 * production {@link DefaultIntakeEventCounter}, which counts the events from each peer by the sender they had in the
 * simulation. The metrics are not started: in production, their periodic updaters and snapshots run on other threads.
 *
 * <p>The recording runs the event creator and the consensus code, so a change to them can change the events. The
 * check of the peer's tips that the lookups find then fails, and the expected number changes in the same pull request.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per timed call. JMH's primary score
 * is the time of a whole pass, the paused calls included; the number of timed calls per pass differs between the
 * parameter sets. Each invocation's setup ends with {@code System.gc()}; a pass during which a garbage collection runs
 * is discarded and replaced by the next invocation. The heap is sized for the recording of 28 nodes, which keeps up to
 * about 6.7 GB live while it runs: in a local heap sweep, it needed 5 full collections at 8 GB and none at 10 GB.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <p>THROWAWAY PROBE for #27670, never merged: a copy of {@code ShadowgraphBenchmark#addEvent} at 10 nodes that tests
 * whether the per-fork latency levels come from where the recorded events lie in memory. With {@link #copyPerPass},
 * every pass adds fresh copies of the recorded events, allocated in replay order before the pass. Fourth test (the
 * second, huge pages, and the third, the inlining of two methods, changed nothing): each fork writes a compile log
 * ({@code compile-pid<process id>.log}) into the results directory of the calibration workflow, to compare all
 * compilations below {@code Shadowgraph#addEvent} between fast and slow forks, as for {@code EventHashingBenchmark}
 * in <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/27940">#27940</a>. Only the recorded events
 * are added, without copies. Fifth test (the fourth found two compilations of {@code addEvent}, both at all
 * levels): the forks run with {@code -Xbatch}, without background compilation. The recording and the benchmark run
 * on one thread, so every fork then compiles in the same order with the same profiles; the compile logs show whether
 * the code is the same. If the levels stay, the compiled code does not set them.
 */
@State(Scope.Thread)
@Fork(
        value = 10,
        jvmArgsAppend = {
            "-Xms10g",
            "-Xmx10g",
            "-XX:+AlwaysPreTouch",
            "-Xbatch",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+LogCompilation",
            "-XX:LogFile=build/benchmark-calibration/compile-%p.log"
        })
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 5)
public class ShadowgraphLayoutProbe {

    /** The seed of the recording. The same seed gives the same recording. */
    private static final long SEED = 0;

    /**
     * The number of events each recorded node releases from its orphan buffer, its own included, before the recording
     * ends. It covers the 1000 rounds until the first events expire and about 1000 rounds after them at 28 nodes.
     */
    private static final int EVENTS = 350_000;

    /** The number of nodes in the simulated network. */
    @Param({"10"})
    public int numNodes;

    /** Whether every pass adds fresh copies of the recorded events instead of the recorded events themselves. */
    @Param({"false"})
    public boolean copyPerPass;

    /** The calls of the current pass: {@link #calls}, or calls with fresh copies of the events. */
    private Call[] passCalls;

    /** What one call of the replay does. */
    private enum Kind {
        /** Adds an event to the shadowgraph. */
        ADD_EVENT,
        /** Sets a new event window. */
        UPDATE_WINDOW,
        /** Makes the lookups of a sync. */
        SYNC_LOOKUP
    }

    /**
     * One call of the replay.
     *
     * @param kind          what the call does
     * @param event         the event to add, for {@link Kind#ADD_EVENT}
     * @param window        the event window to set, for {@link Kind#UPDATE_WINDOW}
     * @param peerTips      the hashes of the peer's tips, for {@link Kind#SYNC_LOOKUP}
     * @param knownPeerTips the number of the peer's tips in the shadowgraph at this call, for {@link Kind#SYNC_LOOKUP}
     */
    private record Call(Kind kind, PlatformEvent event, EventWindow window, List<Hash> peerTips, int knownPeerTips) {}

    /** The configuration of the recorded node. */
    private Configuration configuration;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The ID of the node whose shadowgraph is replayed. */
    private NodeId nodeId;

    /** The calls of the replay, in order. */
    private Call[] calls;

    /** The number of calls that each invocation's setup makes: those before the first events expire. */
    private int fillCalls;

    /** The executor the metrics require. It stays idle, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The intake event counter of the invocation. */
    private DefaultIntakeEventCounter intakeEventCounter;

    /** The shadowgraph of the invocation. */
    private Shadowgraph shadowgraph;

    /** The event window set last. */
    private EventWindow currentWindow;

    /** The number of events added in the invocation after the fill. */
    private int addedEvents;

    /** The event window reserved by the latest sync lookup. */
    private EventWindow lookupWindow;

    /** The tips returned to the latest sync lookup. */
    private List<ShadowEvent> lookupTips;

    /** The shadow events of the peer's tips returned to the latest sync lookup, null for those not in the graph. */
    private List<ShadowEvent> lookupPeerTips;

    /** The number of the peer's tips that the sync lookups of the invocation found in the shadowgraph. */
    private long foundPeerTips;

    /** The number of sync lookups in the invocation whose result differed from the recording. */
    private int lookupMismatches;

    /** The latency recorder of the invocation. */
    private LatencyRecorder recorder;

    /**
     * Records the network and turns what the first recorded node's gossip gets into the calls of the replay. This
     * method is called once per trial.
     */
    @Setup(Level.Trial)
    public void recordCalls() {
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, EVENTS).recordedNodes(2).build());
        final NodeRecording node = recording.nodes().get(0);
        final NodeRecording peer = recording.nodes().get(1);
        configuration = node.configuration();
        roster = recording.roster();
        nodeId = node.nodeId();
        calls = buildCalls(node, peer).toArray(new Call[0]);
        fillCalls = firstExpiringWindow();
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Turns the node's released events and event windows into calls, with a sync lookup after each event. The peer's
     * tips at a lookup are the newest event of each creator that the peer had released by the time of the event.
     *
     * @param node the node whose shadowgraph is replayed
     * @param peer the peer it syncs with
     * @return the calls, in order
     */
    private static List<Call> buildCalls(final NodeRecording node, final NodeRecording peer) {
        final List<Released> peerReleases = new ArrayList<>();
        for (final Step step : peer.steps()) {
            if (step instanceof final Released released) {
                peerReleases.add(released);
            }
        }
        final Map<NodeId, Hash> peerTips = new LinkedHashMap<>();
        final Set<Hash> releasedByNode = new HashSet<>();
        int nextPeerRelease = 0;
        final List<Call> result = new ArrayList<>();
        for (final Step step : node.steps()) {
            switch (step) {
                case Released released -> {
                    final PlatformEvent event = released.event();
                    result.add(new Call(Kind.ADD_EVENT, event, null, null, 0));
                    releasedByNode.add(event.getHash());
                    while (nextPeerRelease < peerReleases.size()
                            && !peerReleases.get(nextPeerRelease).time().isAfter(released.time())) {
                        final PlatformEvent peerEvent =
                                peerReleases.get(nextPeerRelease++).event();
                        peerTips.put(peerEvent.getCreatorId(), peerEvent.getHash());
                    }
                    int known = 0;
                    for (final Hash tip : peerTips.values()) {
                        if (releasedByNode.contains(tip)) {
                            known++;
                        }
                    }
                    result.add(new Call(Kind.SYNC_LOOKUP, null, null, List.copyOf(peerTips.values()), known));
                }
                case NewEventWindow window -> result.add(new Call(Kind.UPDATE_WINDOW, null, window.window(), null, 0));
                default -> {
                    // received events, creation attempts, and platform status changes do not reach the shadowgraph
                }
            }
        }
        return result;
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Creates a new shadowgraph and makes the calls up to the first expiring event window, without the sync lookups.
     * This method is called once per invocation.
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
        passCalls = calls;
        if (copyPerPass) {
            passCalls = new Call[calls.length];
            for (int i = 0; i < calls.length; i++) {
                final Call call = calls[i];
                passCalls[i] = call.kind() == Kind.ADD_EVENT
                        ? new Call(Kind.ADD_EVENT, copyEvent(call.event()), null, null, 0)
                        : call;
            }
        }
        shadowgraph = new Shadowgraph(metrics, numNodes, intakeEventCounter);
        currentWindow = shadowgraph.getEventWindow();
        for (int i = 0; i < fillCalls; i++) {
            if (passCalls[i].kind() != Kind.SYNC_LOOKUP) {
                execute(passCalls[i]);
            }
        }
        addedEvents = 0;
        foundPeerTips = 0;
        lookupMismatches = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies the invocation: every event was added, every sync lookup found what the recording expects, the lookups
     * found the expected number of the peer's tips, the shadowgraph holds exactly the events that have not expired, and
     * every event left the intake pipeline. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        int expectedAdded = 0;
        EventWindow lastWindow = null;
        for (int i = 0; i < calls.length; i++) {
            if (calls[i].kind() == Kind.ADD_EVENT && i >= fillCalls) {
                expectedAdded++;
            } else if (calls[i].kind() == Kind.UPDATE_WINDOW) {
                lastWindow = calls[i].window();
            }
        }
        if (addedEvents != expectedAdded) {
            throw new IllegalStateException(
                    "Expected %d added events, but got %d".formatted(expectedAdded, addedEvents));
        }
        if (lookupMismatches != 0) {
            throw new IllegalStateException("%d sync lookups differed from the recording".formatted(lookupMismatches));
        }
        if (foundPeerTips != expectedFoundPeerTips()) {
            throw new IllegalStateException("Expected the sync lookups to find %d of the peer's tips, but they found %d"
                    .formatted(expectedFoundPeerTips(), foundPeerTips));
        }
        if (shadowgraph.getEventWindow() != lastWindow) {
            throw new IllegalStateException(
                    "Expected the event window %s, but got %s".formatted(lastWindow, shadowgraph.getEventWindow()));
        }
        for (final Call call : calls) {
            if (call.kind() == Kind.ADD_EVENT) {
                final PlatformEvent event = call.event();
                final boolean expected = event.getBirthRound() >= lastWindow.expiredThreshold();
                if ((shadowgraph.getEvent(event.getHash()) != null) != expected) {
                    throw new IllegalStateException("Expected event %s %s the shadowgraph"
                            .formatted(event.getDescriptor(), expected ? "in" : "not in"));
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
     * The number of the peer's tips that the sync lookups of one pass find in the shadowgraph. It depends on the
     * recording, on the expiry of events, and on the lookups.
     */
    private long expectedFoundPeerTips() {
        return switch (numNodes) {
            case 10 -> 1_547_179;
            case 28 -> 2_213_188;
            default -> throw new IllegalArgumentException("No expected peer tips for numNodes=" + numNodes);
        };
    }

    /**
     * Replays the calls after the fill and records the latency of each call of
     * {@link Shadowgraph#addEvent(PlatformEvent)}.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void addEvent() {
        replay(Kind.ADD_EVENT);
    }

    /**
     * Copies an event as gossip delivers it, with a new hash object and the sender of the original, so that none of
     * the objects the shadowgraph reads is one of the recording's.
     */
    private static PlatformEvent copyEvent(final PlatformEvent event) {
        final PlatformEvent copy = EventStreamGenerator.receiveUnhashed(event);
        copy.setHash(new Hash(event.getHash().copyToByteArray(), event.getHash().getDigestType()));
        if (event.getSenderId() != null) {
            copy.setSenderId(event.getSenderId());
        }
        // built lazily; in production, and for the recorded events after the first pass, it exists already
        copy.getDescriptor();
        return copy;
    }

    /**
     * Replays the calls after the fill, times the calls of the measured kind, and pauses the recorder for the others.
     *
     * @param measured the kind of call to time
     */
    private void replay(final Kind measured) {
        recorder.startMeasurement();
        recorder.pause();
        for (int i = fillCalls; i < calls.length; i++) {
            final Call call = passCalls[i];
            if (call.kind() == measured) {
                if (recorder.isPaused()) {
                    recorder.resume();
                }
                execute(call);
                recorder.lap();
            } else {
                if (!recorder.isPaused()) {
                    recorder.pause();
                }
                execute(call);
            }
            // after the lap, and a sync lookup is never followed by another one, so the check counts in no latency
            if (call.kind() == Kind.SYNC_LOOKUP) {
                checkLookup(call);
            }
        }
        if (recorder.isPaused()) {
            recorder.resume();
        }
        recorder.stopMeasurement();
    }

    /** Calls the shadowgraph as the recorded call does. */
    private void execute(final Call call) {
        switch (call.kind()) {
            case ADD_EVENT -> {
                if (shadowgraph.addEvent(call.event())) {
                    addedEvents++;
                }
            }
            case UPDATE_WINDOW -> {
                shadowgraph.updateEventWindow(call.window());
                currentWindow = call.window();
            }
            case SYNC_LOOKUP -> lookUpForSync(call);
        }
    }

    /**
     * Makes the shadowgraph calls of a sync before its decision which events to send, as
     * {@code RpcPeerHandler#sendSyncData} and {@code RpcPeerHandler#sendKnownTips} do, and keeps the results for
     * {@link #checkLookup(Call)}.
     */
    private void lookUpForSync(final Call call) {
        try (final ReservedEventWindow reservation = shadowgraph.reserve()) {
            lookupWindow = reservation.getEventWindow();
            lookupTips = shadowgraph.getTips();
            lookupPeerTips = shadowgraph.shadows(call.peerTips());
        }
    }

    /**
     * Counts the peer's tips that a sync lookup found, and a lookup whose results differ from the recording. Called
     * after the lookup's latency is recorded, so that the check is not part of it.
     */
    private void checkLookup(final Call call) {
        int known = 0;
        for (final ShadowEvent peerTip : lookupPeerTips) {
            if (peerTip != null) {
                known++;
            }
        }
        foundPeerTips += known;
        if (lookupWindow != currentWindow || lookupTips.size() != numNodes || known != call.knownPeerTips()) {
            lookupMismatches++;
        }
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
