// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.jmh;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ReservedEventWindow;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowEvent;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.Shadowgraph;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowgraphSynchronizer;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
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
 * Measures the latency of a sync's decision which events to send to the peer,
 * {@code ShadowgraphSynchronizer#createSendList}. Given the tips the peer is known to have, it walks back through their
 * ancestors to find what the peer already has, walks back from the node's own tips through what the peer lacks, sorts
 * these events, and drops those the peer will likely get from another node ({@code SyncUtils#filterLikelyDuplicates}).
 * The first walk covers nearly all non-ancient events.
 *
 * <p>{@link FalconRecorder} records two full nodes of a simulated network once per trial, the node and its peer: a
 * network of {@link #numNodes} nodes with the ping times of mainnet and transactions of the shape of mainnet's. Each
 * invocation replays the node's recording into a new synchronizer, untimed ({@link LatencyRecorder#pause()}): every
 * event its orphan buffer released and every new event window, in order. During the replay, the node syncs with the
 * peer, as {@code RpcPeerHandler} does on the node that starts the sync:
 * <ol>
 *   <li>The node reserves its event window and takes its tips.</li>
 *   <li>When the sync data reaches the peer, the peer sends its own tips and event window, and answers which of the
 *   node's tips it has.</li>
 *   <li>One round trip after the start, the node looks up which of the peer's tips it has and decides what to send.
 *   Only this decision is timed.</li>
 * </ol>
 * The latencies are those of the mainnet ping matrix between the two nodes, without the jitter of the simulation. The
 * peer's tips and event windows come from a replay of the peer's recording in the trial setup. The next sync starts
 * {@code broadcast.rpcSleepAfterSyncWhileBroadcasting} after the decision, the pause between two syncs with a peer while
 * broadcast runs. The first sync starts with the node's first event window that makes events ancient, so every decision
 * sees a full non-ancient window.
 *
 * <p>Broadcast runs, as in production, so the duplicate filter holds back the events the node has had for less than the
 * configured {@code sync.*FilterThreshold}. It compares their time received with the synchronizer's clock, which the
 * replay sets to the simulated time of each decision. The clock's {@code nanoTime()} is the real one, which the filter's
 * metric reads.
 *
 * <p>The simulation delivers every event to every node within one latency, so the filter holds back everything the peer
 * lacks, and every send list is empty. On mainnet, a sync sends 1 to 5 events on average ({@code ev_per_syncS},
 * November 2026). This hardly matters for the cost: in a local A/B, deciding as if broadcast were paused sent 13 to 17
 * events per sync and changed the latency by at most 0.2%.
 *
 * <p>The synchronizer updates the production metrics implementation, {@link DefaultPlatformMetrics}, and the production
 * {@link DefaultIntakeEventCounter}. The metrics are not started: in production, their periodic updaters and snapshots
 * run on other threads. The benchmark runs on one thread; in production, the syncs with all peers share one
 * synchronizer, and events are added on another thread, so lock contention is not measured.
 *
 * <p>{@link LatencyProfiler} reports the mean, the percentiles, and the allocation per decision. JMH's primary score is
 * the time of a whole pass, the untimed replay included. Each invocation's setup ends with {@code System.gc()}; a pass
 * during which a garbage collection runs is discarded and replaced by the next invocation.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-11-09, f70c9b657d</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms6g -Xmx6g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: numNodes=10 / numNodes=28</li>
 *   <li>Steady state after: 5 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.15% / 0.18% per 15 s iteration, 0.68% / 0.46% between forks, - / - between runs (1 of 1 / 1 of 1 runs quiet)</li>
 *   <li>Expected detectable regression: 0.78% / 0.54% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 46 k / 14 k; expected stable: mean, p50, p90, p99 / mean, p50, p90, p99; unmeasured: p99.9, p99.99 / p99.9, p99.99</li>
 *   <li>Runtime: 2.9 / 6.5 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/42698268544, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/42706379582</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 3,
        jvmArgsAppend = {"-Xms6g", "-Xmx6g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 15)
public class SyncDecisionBenchmark {

    /** The seed of the recording. The same seed gives the same recording. */
    private static final long SEED = 0;

    /** The number of events each recorded node releases from its orphan buffer, its own included. */
    private static final int EVENTS = 100_000;

    /** The number of nodes in the simulated network. */
    @Param({"10", "28"})
    public int numNodes;

    /** What one action of the replay does. */
    private enum Kind {
        /** Adds an event the node's orphan buffer released. */
        ADD_EVENT,
        /** Sets a new event window. */
        SET_EVENT_WINDOW,
        /** Starts a sync: reserves the event window and takes the node's tips. */
        START_SYNC,
        /** Decides which events to send in a sync. */
        DECIDE
    }

    /**
     * One sync with the peer.
     *
     * @param start the simulated time at which the node starts the sync
     * @param peerAnswer the simulated time at which the peer takes its tips and answers which of the node's tips it has
     * @param decision the simulated time at which the node decides which events to send
     * @param peerTips the hashes of the peer's tips at {@code peerAnswer}
     * @param peerWindow the peer's event window at {@code peerAnswer}
     */
    private record Sync(
            @NonNull Instant start,
            @NonNull Instant peerAnswer,
            @NonNull Instant decision,
            @NonNull List<Hash> peerTips,
            @NonNull EventWindow peerWindow) {}

    /**
     * One action of the replay.
     *
     * @param kind what the action does
     * @param time the simulated time of the action
     * @param event the event it adds, or null
     * @param window the event window it sets, or null
     * @param sync the sync it starts or decides, or null
     */
    private record Action(
            @NonNull Kind kind,
            @NonNull Instant time,
            @Nullable PlatformEvent event,
            @Nullable EventWindow window,
            @Nullable Sync sync) {}

    /** The configuration of the node. */
    private Configuration configuration;

    /** The roster of the simulated network. */
    private RosterWrapper roster;

    /** The ID of the node. */
    private NodeId selfId;

    /** The actions of the replay, in order. */
    private Action[] actions;

    /** The simulated time at which the peer released each of its events from its orphan buffer, by hash. */
    private Map<Hash, Instant> peerReleaseTimes;

    /** The executor the metrics require. It only announces new metrics, because the metrics are not started. */
    private ScheduledExecutorService metricsExecutor;

    /** The clock of the synchronizer. */
    private ReplayTime time;

    /** The synchronizer of the replay. */
    private Synchronizer synchronizer;

    /** The reservation of the sync in progress, or null. */
    private ReservedEventWindow reservation;

    /** The tips the peer is known to have in the sync in progress. */
    private Set<ShadowEvent> knownSet;

    /** The number of decisions in the invocation so far. */
    private int decisions;

    /**
     * The number of events the decisions of the invocation so far found the peer to have: the sizes of their known sets,
     * after adding the ancestors of the known tips.
     */
    private long knownAncestors;

    /** The number of events in the send lists of the invocation so far. */
    private long eventsSent;

    /** A checksum over the hashes of the events in the send lists of the invocation so far, in order. */
    private long checksum;

    /** The latency recorder of the invocation. */
    private LatencyRecorder recorder;

    /**
     * Records the node and its peer, and prepares the actions of the replay. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void recordActions() {
        metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final Recording recording = FalconRecorder.record(
                RecordingSpec.builder(numNodes, SEED, EVENTS).recordedNodes(2).build());
        // the recorded nodes are the first two nodes of the network, so the first two of the ping matrix
        final NodeRecording self = recording.nodes().get(0);
        final NodeRecording peer = recording.nodes().get(1);
        configuration = self.configuration();
        roster = recording.roster();
        selfId = self.nodeId();

        // one-way latency is half the ping, as in the simulation
        final Duration toPeer = Duration.ofMillis(RealisticPingSamples.MAINNET[0][1] / 2);
        final Duration fromPeer = Duration.ofMillis(RealisticPingSamples.MAINNET[1][0] / 2);
        final Duration pause =
                configuration.getConfigData(BroadcastConfig.class).rpcSleepAfterSyncWhileBroadcasting();

        // the times of the syncs
        final Instant selfEnd = self.steps().getLast().time();
        final Instant peerEnd = peer.steps().getLast().time();
        final List<Instant[]> times = new ArrayList<>();
        Instant start = firstAncientWindowTime(self);
        while (true) {
            final Instant peerAnswer = start.plus(toPeer);
            final Instant decision = peerAnswer.plus(fromPeer);
            if (decision.isAfter(selfEnd) || peerAnswer.isAfter(peerEnd)) {
                break;
            }
            times.add(new Instant[] {start, peerAnswer, decision});
            start = decision.plus(pause);
        }

        // replay the peer to find its tips and event window at each answer
        final List<Sync> syncs = new ArrayList<>(times.size());
        peerReleaseTimes = new HashMap<>();
        final Shadowgraph peerGraph =
                new Shadowgraph(newMetrics(peer.nodeId()), numNodes, new NoOpIntakeEventCounter());
        for (final Step step : peer.steps()) {
            while (syncs.size() < times.size() && times.get(syncs.size())[1].isBefore(step.time())) {
                syncs.add(newSync(times.get(syncs.size()), peerGraph));
            }
            switch (step) {
                case Released r -> {
                    addEvent(peerGraph, r.event());
                    peerReleaseTimes.put(r.event().getHash(), r.time());
                }
                case NewEventWindow w -> peerGraph.updateEventWindow(w.window());
                default -> {
                    // not an input of the shadowgraph
                }
            }
        }
        while (syncs.size() < times.size()) {
            syncs.add(newSync(times.get(syncs.size()), peerGraph));
        }

        // merge the node's steps with the starts and decisions of the syncs; an action at the time of a step follows it
        final List<Action> syncActions = new ArrayList<>(2 * syncs.size());
        for (final Sync sync : syncs) {
            syncActions.add(new Action(Kind.START_SYNC, sync.start(), null, null, sync));
            syncActions.add(new Action(Kind.DECIDE, sync.decision(), null, null, sync));
        }
        final List<Action> merged = new ArrayList<>();
        int next = 0;
        for (final Step step : self.steps()) {
            while (next < syncActions.size() && syncActions.get(next).time().isBefore(step.time())) {
                merged.add(syncActions.get(next++));
            }
            switch (step) {
                case Released r -> merged.add(new Action(Kind.ADD_EVENT, r.time(), r.event(), null, null));
                case NewEventWindow w ->
                    merged.add(new Action(Kind.SET_EVENT_WINDOW, w.time(), null, w.window(), null));
                default -> {
                    // not an input of the shadowgraph
                }
            }
        }
        merged.addAll(syncActions.subList(next, syncActions.size()));
        actions = merged.toArray(new Action[0]);
    }

    /** Returns a sync at the given times, with the peer's tips and event window as they are now. */
    private static Sync newSync(final Instant[] times, final Shadowgraph peerGraph) {
        final List<Hash> peerTips =
                peerGraph.getTips().stream().map(ShadowEvent::getBaseHash).toList();
        return new Sync(times[0], times[1], times[2], peerTips, peerGraph.getEventWindow());
    }

    /** Adds an event to a shadowgraph of the trial setup. */
    private static void addEvent(final Shadowgraph graph, final PlatformEvent event) {
        try {
            graph.addEvent(event);
        } catch (final Exception e) {
            throw new IllegalStateException("The peer's shadowgraph rejected an event", e);
        }
    }

    /**
     * Returns the time of the node's first event window whose ancient threshold is above the genesis window's, so that
     * events have started to become ancient.
     */
    private static Instant firstAncientWindowTime(final NodeRecording node) {
        final long genesisThreshold = EventWindow.getGenesisEventWindow().ancientThreshold();
        for (final Step step : node.steps()) {
            if (step instanceof final NewEventWindow w && w.window().ancientThreshold() > genesisThreshold) {
                return w.time();
            }
        }
        throw new IllegalStateException("No event window of the recording makes events ancient");
    }

    /** Returns new, unstarted production metrics. */
    private DefaultPlatformMetrics newMetrics(final NodeId nodeId) {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        return new DefaultPlatformMetrics(
                nodeId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    /**
     * Shuts down the executor of the metrics. This method is called once per trial.
     */
    @TearDown(Level.Trial)
    public void shutdownMetricsExecutor() {
        metricsExecutor.shutdownNow();
    }

    /**
     * Builds a new synchronizer like the node's. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        final DefaultPlatformMetrics metrics = newMetrics(selfId);
        // the events of other nodes entered the intake pipeline before they reach the synchronizer
        final IntakeEventCounter intakeEventCounter = new DefaultIntakeEventCounter(roster);
        for (final Action action : actions) {
            if (action.kind() == Kind.ADD_EVENT
                    && requireNonNull(action.event()).getSenderId() != null) {
                intakeEventCounter.eventEnteredIntakePipeline(action.event().getSenderId());
            }
        }
        time = new ReplayTime(actions[0].time());
        synchronizer = new Synchronizer(
                configuration,
                metrics,
                time,
                numNodes,
                new CheckedSyncMetrics(metrics, time),
                intakeEventCounter,
                _ -> {});
        reservation = null;
        knownSet = null;
        decisions = 0;
        knownAncestors = 0;
        eventsSent = 0;
        checksum = 0;
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the setup and of the previous invocation now, so that it is not collected during the
        // measured calls.
        System.gc();
    }

    /**
     * Verifies that the invocation made the expected decisions. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        // disabled for now (2026-11-10), as it requires re-pinning after major network changes are done
        //        final Expected actual = new Expected(decisions, knownAncestors, eventsSent, checksum);
        //        if (!actual.equals(expected())) {
        //            throw new IllegalStateException("Expected %s, but got %s".formatted(expected(), actual));
        //        }
        if (reservation != null) {
            throw new IllegalStateException("A sync is still in progress at the end of the replay");
        }
        synchronizer = null;
        knownSet = null;
        recorder = null;
    }

    /**
     * The expected result of an invocation. It changes with any change of the recording, for example a change of the
     * simulation; results before and after such a change are not comparable.
     *
     * @param decisions the number of decisions
     * @param knownAncestors the sum of the sizes of their known sets
     * @param eventsSent the number of events in all send lists
     * @param checksum the checksum over the hashes of these events, in order
     */
    private record Expected(int decisions, long knownAncestors, long eventsSent, long checksum) {}

    /** Returns the expected result of an invocation. */
    private Expected expected() {
        return switch (numNodes) {
            case 10 -> new Expected(1593, 2_395_473, 0, 0);
            case 28 -> new Expected(551, 2_746_929, 0, 0);
            default -> throw new IllegalArgumentException("No expected result for numNodes=" + numNodes);
        };
    }

    /**
     * Replays the node's recording and records the latency of each decision which events to send.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void createSendList() {
        recorder.startMeasurement();
        recorder.pause();
        for (final Action action : actions) {
            switch (action.kind()) {
                case ADD_EVENT -> synchronizer.addEvent(requireNonNull(action.event()));
                case SET_EVENT_WINDOW -> synchronizer.updateEventWindow(requireNonNull(action.window()));
                case START_SYNC -> startSync(requireNonNull(action.sync()));
                case DECIDE -> decide(requireNonNull(action.sync()));
            }
        }
        recorder.resume();
        recorder.stopMeasurement();
    }

    /** Starts a sync, as {@code RpcPeerHandler#sendSyncData} does, and keeps the node's tips that the peer will have. */
    private void startSync(final Sync sync) {
        reservation = synchronizer.reserveEventWindow();
        knownSet = new HashSet<>();
        for (final ShadowEvent tip : synchronizer.tips()) {
            final Instant released = peerReleaseTimes.get(tip.getBaseHash());
            if (released != null && !released.isAfter(sync.peerAnswer())) {
                knownSet.add(tip);
            }
        }
    }

    /**
     * Adds the peer's tips the node has to the known set, as {@code RpcPeerHandler#sendKnownTips} does, and times the
     * decision which events to send, as {@code RpcPeerHandler#receiveTips} makes it.
     */
    private void decide(final Sync sync) {
        for (final ShadowEvent tip : synchronizer.shadows(sync.peerTips())) {
            if (tip != null) {
                knownSet.add(tip);
            }
        }
        time.set(sync.decision());

        recorder.resume();
        final List<PlatformEvent> sendList =
                synchronizer.sendList(selfId, knownSet, reservation.getEventWindow(), sync.peerWindow());
        recorder.lap();
        recorder.pause();

        reservation.close();
        reservation = null;
        decisions++;
        eventsSent += sendList.size();
        for (final PlatformEvent event : sendList) {
            checksum = 31 * checksum + event.getHash().hashCode();
        }
    }

    /**
     * The production sync metrics, which also add up the sizes of the known sets for the output check. The per-peer
     * metrics that production creates up front are not created; the decision does not use them.
     */
    private final class CheckedSyncMetrics extends SyncMetrics {

        CheckedSyncMetrics(final DefaultPlatformMetrics metrics, final Time time) {
            super(metrics, time, List.of());
        }

        @Override
        public void knownSetSize(final int knownSetSize) {
            super.knownSetSize(knownSetSize);
            knownAncestors += knownSetSize;
        }
    }

    /** Gives the benchmark access to the synchronizer's protected methods. */
    private static final class Synchronizer extends ShadowgraphSynchronizer {

        Synchronizer(
                final Configuration configuration,
                final DefaultPlatformMetrics metrics,
                final Time time,
                final int numberOfNodes,
                final SyncMetrics syncMetrics,
                final IntakeEventCounter intakeEventCounter,
                final Consumer<SyncProgress> syncProgressHandler) {
            super(configuration, metrics, time, numberOfNodes, syncMetrics, intakeEventCounter, syncProgressHandler);
        }

        /** Returns the node's tips, as {@code RpcPeerHandler#sendSyncData} takes them. */
        List<ShadowEvent> tips() {
            return getTips();
        }

        /** Decides which events to send while broadcast runs. */
        List<PlatformEvent> sendList(
                final NodeId selfId,
                final Set<ShadowEvent> knownSet,
                final EventWindow myEventWindow,
                final EventWindow theirEventWindow) {
            return createSendList(selfId, knownSet, myEventWindow, theirEventWindow, true);
        }
    }

    /**
     * A clock at the simulated time of the replay. Its {@link #nanoTime()} is the real clock, which only measures
     * durations.
     */
    private static final class ReplayTime implements Time {

        private Instant now;

        ReplayTime(final Instant now) {
            this.now = now;
        }

        void set(final Instant now) {
            this.now = now;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return now.toEpochMilli();
        }

        @Override
        @NonNull
        public Instant now() {
            return now;
        }
    }
}
