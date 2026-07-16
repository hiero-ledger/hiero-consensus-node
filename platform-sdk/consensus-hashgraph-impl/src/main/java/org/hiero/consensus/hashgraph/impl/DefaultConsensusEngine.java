// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Supplier;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.consensus.Consensus;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.DefaultLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusEngineMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetricsImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * POC: {@link DefaultConsensusEngine} extended with an in-process <b>consensus version switch</b>.
 *
 * <h2>What this demonstrates</h2>
 * The engine can be told (via {@link #scheduleVersionSwitch(long)}) that at some future round {@code X}
 * the consensus algorithm should be replaced by a new version. Rounds {@code < X} are decided entirely by
 * the {@code active} {@link Consensus}; rounds {@code >= X} are decided entirely by the {@code incoming}
 * {@link Consensus}. Because the boundary is a deterministic round number that every node crosses in the
 * same place, no round is ever decided by a mix of versions — the same ISS-free argument as the execution
 * switch, with the {@link ConsensusSnapshot} as the version-invariant seam.
 *
 * <h2>Mechanism</h2>
 * <ol>
 *   <li>On arming, the engine starts a {@link SwitchCaptureBuffer}. Every event is appended to the buffer
 *       <b>before</b> it is handed to the active consensus (the {@link #addEvent} chokepoint guarantees
 *       "in the buffer" and "seen by consensus" are the same insertion point).</li>
 *   <li>The active (old) consensus keeps deciding rounds up to and including {@code X-1}. When it publishes
 *       the round-{@code X-1} {@link ConsensusRound}, the engine grabs that round's {@link ConsensusSnapshot}
 *       — the version-invariant handoff artifact that already crosses every restart.</li>
 *   <li>The engine builds the incoming (new) consensus, calls {@code loadSnapshot(snapshot(X-1))} on it, and
 *       <b>replays the captured events</b> into it. {@code loadSnapshot} marks everything {@code <= X-1} as
 *       already-consensus (via the init judges), so the incoming consensus re-derives its own graph and emits
 *       only rounds {@code >= X}.</li>
 *   <li>The engine swaps the pointers; the incoming consensus becomes active; the buffer is dropped.</li>
 * </ol>
 *
 * <h2>What is deliberately NOT carried across the boundary</h2>
 * Only version-invariant inputs seed the incoming consensus: the {@link ConsensusSnapshot} and the raw
 * {@link PlatformEvent} cores. The engine never copies {@link EventImpl}-level derived metadata (round-created,
 * strongly-see, judge flags) from the old graph into the new one — that memoized state is the old version's
 * opinion and is exactly what an incompatible new version is allowed to compute differently. The incoming
 * consensus gets its own {@link ConsensusLinker} and {@link FutureEventBuffer} so it rebuilds everything itself.
 *
 * <h2>Assumptions (see the numbered caveats inline)</h2>
 * <ul>
 *   <li>The switch round is known far enough in advance that capture is armed before any event that will still
 *       be non-ancient at {@code X-1} is admitted. This is checked loudly at swap time; a real implementation
 *       falls back to PCES replay when the check fails.</li>
 *   <li>Both versions are the same {@link ConsensusImpl} here; how the new version is actually constructed is a
 *       separate concern, injected as a {@code Supplier<Consensus>}.</li>
 *   <li>The switch signal arrives out-of-band through {@link #scheduleVersionSwitch(long)}.</li>
 *   <li>A version switch and a freeze are not armed simultaneously.</li>
 * </ul>
 */
public class DefaultConsensusEngine implements ConsensusEngine {

    /** Sentinel meaning "no switch is armed". */
    private static final long NO_SWITCH = Long.MIN_VALUE;

    // ---- Construction dependencies (retained so the incoming pipeline can be built) --------------------

    private final Configuration configuration;
    private final Metrics metrics;
    private final Time time;
    private final NodeId selfId;
    private final FreezePeriodChecker freezeChecker;
    private final int roundsNonAncient;

    /**
     * Builds a fresh {@link Consensus} instance. For the POC this returns a {@link ConsensusImpl}; in production
     * this is where the classloader picks the correct new-version implementation. Deciding what this returns is a
     * separate concern, per the task framing.
     */
    private final Supplier<Consensus> consensusFactory;

    private final ConsensusEngineMetrics consensusEngineMetrics;

    // ---- Live state --------------------------------------------------------------------------------------

    /** The authoritative pipeline. Everything below the switch round is decided here. */
    private ConsensusPipeline active;

    /** The round at which the incoming version takes over, or {@link #NO_SWITCH} when nothing is armed. */
    private long switchRound = NO_SWITCH;

    /** Non-null only while a switch is armed and not yet performed. */
    private SwitchCaptureBuffer captureBuffer;

    /** Snapshot of round {@code switchRound - 1}, captured as soon as the active consensus publishes it. */
    private ConsensusSnapshot snapshotAtBoundary;

    /** Ancient threshold observed at the moment the switch was armed (used for the completeness check). */
    private long armAncientThreshold;

    /** Latest event window seen from the active pipeline; drives capture-buffer pruning. */
    private EventWindow latestEventWindow;

    /** Mirrors platform status so a newly-built incoming pipeline can be put in the right PCES mode. */
    private boolean pcesMode;

    /**
     * Convenience constructor matching the original {@link DefaultConsensusEngine} signature. Uses a
     * {@link ConsensusImpl} factory for both the initial and the incoming consensus (POC: same code for both).
     */
    public DefaultConsensusEngine(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezeChecker,
            final long transactionOffsetNanos) {
        this(
                configuration,
                metrics,
                time,
                selfId,
                freezeChecker,
                defaultConsensusFactory(configuration, metrics, time, roster, selfId, transactionOffsetNanos));
    }

    /**
     * Full constructor taking an explicit {@link Consensus} factory. The factory is invoked once to build the
     * initial active consensus, and again whenever a version switch is performed.
     */
    public DefaultConsensusEngine(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final FreezePeriodChecker freezeChecker,
            @NonNull final Supplier<Consensus> consensusFactory) {

        this.configuration = Objects.requireNonNull(configuration);
        this.metrics = Objects.requireNonNull(metrics);
        this.time = Objects.requireNonNull(time);
        this.selfId = Objects.requireNonNull(selfId);
        this.freezeChecker = Objects.requireNonNull(freezeChecker);
        this.consensusFactory = Objects.requireNonNull(consensusFactory);

        this.roundsNonAncient =
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
        this.consensusEngineMetrics = new ConsensusEngineMetrics(selfId, metrics);

        this.active = newPipeline("consensus");
    }

    private static Supplier<Consensus> defaultConsensusFactory(
            final Configuration configuration,
            final Metrics metrics,
            final Time time,
            final Roster roster,
            final NodeId selfId,
            final long transactionOffsetNanos) {
        // NOTE: constructing two ConsensusImpl instances also builds two ConsensusMetricsImpl. In a real
        // implementation give the shadow pipeline distinct metric names (or a no-op metrics facade) to avoid
        // double-registration. Elided here for POC clarity.
        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(selfId, metrics);
        return () -> new ConsensusImpl(configuration, time, consensusMetrics, roster, transactionOffsetNanos);
    }

    // =====================================================================================================
    //  External switch signal
    // =====================================================================================================

    /**
     * Arm a consensus version switch at the given round. Rounds {@code < switchRound} will be decided by the
     * current (active) consensus; rounds {@code >= switchRound} by the incoming consensus that replaces it.
     *
     * <p>Must be called with a round strictly in the future (not yet decided, with room to capture the
     * {@code switchRound - 1} snapshot). In production the round comes from a committee-signed switch
     * transaction whose consensus position {@code Y} is validated to satisfy {@code switchRound > Y} by every
     * node deterministically.
     *
     * @param round the first round to be decided by the new version
     */
    public void scheduleVersionSwitch(final long round) {
        if (switchRound != NO_SWITCH) {
            throw new IllegalStateException("A version switch to round " + switchRound + " is already armed");
        }
        final long lastDecided = active.getLastRoundDecided();
        if (round <= lastDecided + 1) {
            // We need round-1 to be a round the active consensus will still decide in the future, so that we can
            // extract its snapshot. If round-1 is already decided (or is the very next round), we cannot arm.
            throw new IllegalArgumentException("Switch round " + round + " must be > lastDecided+1 ("
                    + (lastDecided + 1) + "); the boundary must be a future, not-yet-decided round");
        }

        this.switchRound = round;
        this.snapshotAtBoundary = null;
        this.captureBuffer = new SwitchCaptureBuffer();
        this.armAncientThreshold = latestEventWindow == null ? ROUND_FIRST : latestEventWindow.ancientThreshold();
        // From this point on, addEvent appends every event to captureBuffer before feeding the active consensus.
    }

    // =====================================================================================================
    //  ConsensusEngine
    // =====================================================================================================

    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        this.pcesMode = platformStatus == REPLAYING_EVENTS;
        active.setPcesMode(pcesMode);
    }

    @Override
    @NonNull
    public ConsensusEngineOutput addEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);

        // (1) Capture BEFORE the event reaches consensus. Because this is the single admission chokepoint,
        //     "present in the buffer" and "seen by the active consensus" are the same point, by construction.
        if (captureBuffer != null) {
            captureBuffer.capture(event);
        }

        // (2) Drive the active (authoritative) pipeline exactly as the original engine did.
        final ConsensusEngineOutput activeOutput = active.addEventFlow(event);

        // Track the newest event window and prune the capture buffer's old edge so a delayed switch does not
        // grow the buffer without bound. Ancient threshold is monotonic non-decreasing (INV-012), so anything
        // dropped here is guaranteed ancient at the switch boundary too, hence not needed by the new version.
        if (!activeOutput.consensusRounds().isEmpty()) {
            latestEventWindow = activeOutput.consensusRounds().getLast().getEventWindow();
            if (captureBuffer != null) {
                // Cap pruning at the switch boundary. If this batch straddles the switch round, the last round's
                // window (>= switchRound) has a HIGHER ancient threshold than round X-1; pruning to it would drop
                // events the incoming version still needs to reproduce rounds >= switchRound — including the
                // round X-1 init judges. Only ever prune using rounds strictly below the boundary.
                captureBuffer.prune(prunableAncientThreshold(activeOutput.consensusRounds()));
            }
        }

        // (3) No switch armed: behave exactly like the stock engine.
        if (switchRound == NO_SWITCH) {
            return activeOutput;
        }

        // (4) A switch is armed. Record the boundary snapshot the moment the active consensus publishes round X-1.
        for (final ConsensusRound round : activeOutput.consensusRounds()) {
            if (round.getRoundNum() == switchRound - 1) {
                snapshotAtBoundary = round.getSnapshot();
            }
        }

        // Not yet reached the boundary: pass through the active output unchanged. (Rounds >= switchRound cannot
        // appear before we swap, because the active consensus has not decided them yet.)
        if (active.getLastRoundDecided() < switchRound - 1 || snapshotAtBoundary == null) {
            return activeOutput;
        }

        // (5) The active consensus has decided through X-1. Perform the swap and splice the outputs together.
        return performSwitch(activeOutput);
    }

    @Override
    public void outOfBandSnapshotUpdate(@NonNull final ConsensusSnapshot snapshot) {
        // A reconnect/restart snapshot supersedes any in-flight version switch: drop the capture buffer and the
        // armed boundary. The switch schedule must be re-armed afterwards from the (persisted) switch round if the
        // adopted state is still below it — omitted here as it belongs to the persistence layer.
        cancelArmedSwitch();

        // Re-initialise the active pipeline from the snapshot (same body as the original engine).
        active.loadSnapshot(snapshot);
        latestEventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
    }

    // =====================================================================================================
    //  Switch execution
    // =====================================================================================================

    private ConsensusEngineOutput performSwitch(@NonNull final ConsensusEngineOutput activeOutput) {
        final ConsensusSnapshot boundary = snapshotAtBoundary;

        // --- Completeness guard (turns the "silent ISS" failure mode into a loud, node-local abort). --------
        // The incoming version is seeded only from (snapshot(X-1) + captured events). That seed is complete only
        // if capture began at or below the switch boundary's non-ancient window. If it did not, the buffer is
        // missing events that are non-ancient at X-1, and we must NOT swap from the buffer alone — a real
        // implementation would fall back to PCES replay here.
        final EventWindow boundaryWindow = EventWindowUtils.createEventWindow(boundary, roundsNonAncient);
        if (armAncientThreshold > boundaryWindow.ancientThreshold()) {
            throw new IllegalStateException("Version switch armed too late: capture began at ancient threshold "
                    + armAncientThreshold + " but the switch boundary's non-ancient window starts at "
                    + boundaryWindow.ancientThreshold() + ". Seed is incomplete; fall back to PCES replay.");
        }

        // --- Build and seed the incoming (new-version) pipeline. --------------------------------------------
        final ConsensusPipeline incoming = newPipeline("consensus-incoming");
        incoming.setPcesMode(pcesMode);
        incoming.loadSnapshot(boundary);

        // Truncate the active (old) output at the boundary: rounds >= switchRound are the old version's opinion
        // about the new-version regime and are discarded. The incoming version reproduces them.
        final List<ConsensusRound> oldRounds = new ArrayList<>();
        for (final ConsensusRound round : activeOutput.consensusRounds()) {
            if (round.getRoundNum() < switchRound) {
                oldRounds.add(round);
            }
        }

        // --- Replay every captured event, in admission (topological) order, into the incoming pipeline. -----
        // loadSnapshot(X-1) already marked everything <= X-1 as consensus, so the incoming pipeline withholds
        // output while it re-derives the init judges, then emits only rounds >= switchRound.
        final List<ConsensusRound> newRounds = new ArrayList<>();
        final List<PlatformEvent> newPre = new ArrayList<>();
        final List<PlatformEvent> newStale = new ArrayList<>();
        for (final PlatformEvent captured : captureBuffer.events()) {
            captured.clearConsensusData();
            final ConsensusEngineOutput replayOut = incoming.addEventFlow(captured);
            newRounds.addAll(replayOut.consensusRounds());
            newPre.addAll(replayOut.preConsensusEvents());
            newStale.addAll(replayOut.staleEvents());
        }

        // --- Promote incoming to active and tear the switch machinery down. --------------------------------
        active = incoming;
        cancelArmedSwitch();

        // --- Splice: old rounds (< X) then new rounds (>= X). ----------------------------------------------
        // Pre-consensus / stale bookkeeping resets across this boundary exactly as it does across an
        // outOfBandSnapshotUpdate (reconnect/restart): the consumer must re-anchor its in-flight counts. We emit
        // the old side's pre/stale plus the incoming replay's pre/stale; some pending events legitimately appear
        // on both sides, which is the documented reset behaviour, not a double-count bug.
        final List<ConsensusRound> rounds = new ArrayList<>(oldRounds);
        rounds.addAll(newRounds);
        final List<PlatformEvent> pre = new ArrayList<>(activeOutput.preConsensusEvents());
        pre.addAll(newPre);
        final List<PlatformEvent> stale = new ArrayList<>(activeOutput.staleEvents());
        stale.addAll(newStale);

        return new ConsensusEngineOutput(rounds, pre, stale);
    }

    /**
     * The highest ancient threshold that is safe to prune to while a switch is armed: the ancient threshold of the
     * newest round in this batch whose number is strictly below {@code switchRound}. This guarantees we never prune
     * away events that are non-ancient at the {@code switchRound - 1} boundary, even when a single batch decides
     * multiple rounds that straddle the boundary. Returns {@link #ROUND_FIRST} (prune nothing) if the batch contains
     * no sub-boundary round, which cannot occur while the buffer is live because the swap fires as soon as
     * {@code switchRound - 1} is decided.
     */
    private long prunableAncientThreshold(@NonNull final List<ConsensusRound> rounds) {
        long threshold = ROUND_FIRST;
        for (final ConsensusRound round : rounds) {
            if (round.getRoundNum() < switchRound) {
                threshold = round.getEventWindow().ancientThreshold();
            }
        }
        return threshold;
    }

    private void cancelArmedSwitch() {
        switchRound = NO_SWITCH;
        captureBuffer = null;
        snapshotAtBoundary = null;
        armAncientThreshold = ROUND_FIRST;
    }

    private ConsensusPipeline newPipeline(@NonNull final String name) {
        final ConsensusLinker linker = new ConsensusLinker(new DefaultLinkerLogsAndMetrics(metrics, time));
        final FutureEventBuffer futureEventBuffer =
                new FutureEventBuffer(metrics, FutureEventBufferingOption.PENDING_CONSENSUS_ROUND, name);
        final Consensus consensus = consensusFactory.get();
        final FreezeRoundController freezeRoundController = new FreezeRoundController(freezeChecker);
        return new ConsensusPipeline(consensus, linker, futureEventBuffer, freezeRoundController);
    }

    // =====================================================================================================
    //  ConsensusPipeline: linker + future buffer + one Consensus instance + freeze controller.
    //  This is the per-version unit. addEventFlow is the original engine's addEvent body, unchanged, so the
    //  active pipeline behaves identically to today and the incoming pipeline replays through the same path.
    // =====================================================================================================

    private final class ConsensusPipeline {

        private final Consensus consensus;
        private final ConsensusLinker linker;
        private final FutureEventBuffer futureEventBuffer;
        private final FreezeRoundController freezeRoundController;

        private ConsensusPipeline(
                @NonNull final Consensus consensus,
                @NonNull final ConsensusLinker linker,
                @NonNull final FutureEventBuffer futureEventBuffer,
                @NonNull final FreezeRoundController freezeRoundController) {
            this.consensus = consensus;
            this.linker = linker;
            this.futureEventBuffer = futureEventBuffer;
            this.freezeRoundController = freezeRoundController;
        }

        void setPcesMode(final boolean pcesMode) {
            consensus.setPcesMode(pcesMode);
        }

        long getLastRoundDecided() {
            return consensus.getLastRoundDecided();
        }

        void loadSnapshot(@NonNull final ConsensusSnapshot snapshot) {
            final EventWindow eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
            linker.clear();
            linker.setEventWindow(eventWindow);
            futureEventBuffer.clear();
            futureEventBuffer.updateEventWindow(eventWindow);
            consensus.loadSnapshot(snapshot);
        }

        /** Verbatim reproduction of the original {@code DefaultConsensusEngine.addEvent} body. */
        @NonNull
        ConsensusEngineOutput addEventFlow(@NonNull final PlatformEvent event) {
            if (freezeRoundController.isFrozen()) {
                final PlatformEvent nonFutureEvent = futureEventBuffer.addEvent(event);
                return nonFutureEvent == null
                        ? ConsensusEngineOutput.emptyInstance()
                        : new ConsensusEngineOutput(List.of(), List.of(nonFutureEvent), List.of());
            }

            final PlatformEvent consensusRelevantEvent = futureEventBuffer.addEvent(event);
            if (consensusRelevantEvent == null) {
                return ConsensusEngineOutput.emptyInstance();
            }

            final Queue<PlatformEvent> eventsToAdd = new LinkedList<>();
            final List<PlatformEvent> preConsensusEvents = new ArrayList<>();
            eventsToAdd.add(consensusRelevantEvent);

            final List<ConsensusRound> allConsensusRounds = new ArrayList<>();
            final List<PlatformEvent> staleEvents = new ArrayList<>();

            while (!eventsToAdd.isEmpty()) {
                final PlatformEvent eventToAdd = eventsToAdd.poll();
                final EventImpl linkedEvent = linker.linkEvent(eventToAdd);
                if (linkedEvent == null) {
                    continue;
                }

                final boolean waitingForJudgesBeforeAdd = consensus.waitingForInitJudges();
                allConsensusRounds.addAll(consensus.addEvent(linkedEvent));
                final boolean waitingForJudgesAfterAdd = consensus.waitingForInitJudges();

                consensusEngineMetrics.eventAdded(linkedEvent);

                if (waitingForJudgesAfterAdd) {
                    return ConsensusEngineOutput.emptyInstance();
                }
                if (waitingForJudgesBeforeAdd) {
                    allConsensusRounds.stream()
                            .map(ConsensusRound::getConsensusEvents)
                            .flatMap(List::stream)
                            .forEach(preConsensusEvents::add);
                    consensus.getPreConsensusEvents().stream()
                            .map(EventImpl::getBaseEvent)
                            .forEach(preConsensusEvents::add);
                } else {
                    preConsensusEvents.add(linkedEvent.getBaseEvent());
                }

                if (allConsensusRounds.isEmpty()) {
                    continue;
                }

                final EventWindow eventWindow = allConsensusRounds.getLast().getEventWindow();
                final List<EventImpl> ancientEvents = linker.setEventWindow(eventWindow);
                ancientEvents.stream()
                        .filter(e -> !e.isConsensus())
                        .map(EventImpl::getBaseEvent)
                        .forEach(staleEvents::add);
                eventsToAdd.addAll(futureEventBuffer.updateEventWindow(eventWindow));
            }

            final List<ConsensusRound> modifiedRounds = freezeRoundController.filterAndModify(allConsensusRounds);
            staleEvents.forEach(consensusEngineMetrics::reportStaleEvent);
            return new ConsensusEngineOutput(modifiedRounds, preConsensusEvents, staleEvents);
        }
    }

    // =====================================================================================================
    //  SwitchCaptureBuffer: an in-RAM, pre-parsed, version-invariant replay source.
    //
    //  It stores the RAW PlatformEvent cores in admission order. It deliberately does not store EventImpl
    //  wrappers, so none of the old version's memoized consensus metadata can leak into the new version.
    //  Admission order is topological (parents precede children), which is the order the incoming pipeline's
    //  linker requires on replay.
    // =====================================================================================================

    private static final class SwitchCaptureBuffer {

        // TODO: possibly store them per bucket, to make pruning faster, but make sure that topological order on replay
        // is not broken
        private final LinkedList<PlatformEvent> captured = new LinkedList<>();

        /** Append the raw event core. (A defensive copy is advisable if the platform mutates PlatformEvent in place.) */
        void capture(@NonNull final PlatformEvent event) {
            captured.addLast(event);
        }

        /**
         * Drop the buffer's old edge. Anything with a birth round below the current ancient threshold can never be
         * needed by the new version (ancient thresholds only rise, so it is ancient at the boundary too).
         */
        void prune(final long ancientThreshold) {
            captured.removeIf(e -> e.getBirthRound() < ancientThreshold);
        }

        /** The captured events in admission (topological) order. */
        @NonNull
        List<PlatformEvent> events() {
            return captured;
        }
    }
}
