// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * A test harness that runs {@link ConsensusImpl} against a curated graph, one event at a time.
 * <p>
 * The harness wires {@link ConsensusImpl}'s collaborators directly rather than going through
 * {@code DefaultConsensusEngine}. That is the only way to observe the intermediate values the algorithm computes on
 * its way to a decided round — {@code roundCreated}, witness, vote and fame flags all live on {@link EventImpl}, no
 * consensus output carries them, and {@code ConsensusImpl#recalculateAndVote} clears and recomputes them on every
 * decided round. Reading them after a run returns only whatever survived the last rebuild.
 * <p>
 * Skipping the engine is the harness's whole deviation from production, and {@code FlickerIntakePinningTest} is the
 * evidence that it does not change the answer: it feeds one graph through this harness and through the real
 * {@code DefaultHashgraphModule}, and requires identical consensus rounds from each.
 * <p>
 * <b>The graph must be authored so that every event is ready for consensus when it is added.</b> The harness asserts
 * this rather than reproducing the machinery production uses to cope with events that are not: it rejects an event
 * that is ancient, that is a future event, or whose parents have not been added yet. Each of those would be accepted
 * by production but processed at a different moment - dropped, deferred, or held - and a hand-derived expectation
 * cannot survive its subject being silently moved. So every {@link #add(String, PlatformEvent)} feeds exactly one
 * event to the algorithm, and the rounds it returns are attributable to that event.
 * <p>
 * The consequence for the pinning test is worth being explicit about: it establishes that this harness and production
 * agree <i>on graphs that satisfy this contract</i>. A graph with future or ancient events is outside what the harness
 * models, and would be rejected here rather than compared.
 */
public class FlickerIntake {

    private final EventHasher hasher = new DefaultEventHasher();
    private final DefaultOrphanBuffer orphanBuffer =
            new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
    private final ConsensusLinker linker;
    private final ConsensusImpl consensus;

    @Nullable
    private final ConsensusTraceLog traceLog;

    /** Every event this harness has linked, by the name the test gave it. */
    private final Map<String, EventImpl> linkedEventsByName = new LinkedHashMap<>();

    /**
     * Names by content hash. The linker's factory is handed only {@code (PlatformEvent, parents)} — no name — so the
     * recorder's name is resolved here at link time. The hash is available by then because the hasher has already run.
     */
    private final Map<Hash, String> eventNameByHash = new HashMap<>();

    /** Every round this harness has produced, in the order it produced them. */
    private final List<ConsensusRound> consensusRounds = new ArrayList<>();

    /** The window the harness is currently at. Starts at genesis and advances as rounds decide. */
    private EventWindow eventWindow = EventWindow.getGenesisEventWindow();

    /** Needed to rebuild the event window when a snapshot is loaded. */
    private final int roundsNonAncient;

    /**
     * Constructor. The algorithm's intermediate state is not recorded.
     *
     * @param configuration the configuration supplying {@code ConsensusConfig}
     * @param roster        the roster the algorithm runs under
     */
    public FlickerIntake(@NonNull final Configuration configuration, @NonNull final RosterWrapper roster) {
        this(configuration, roster, null);
    }

    /**
     * Constructor.
     *
     * @param configuration the configuration supplying {@code ConsensusConfig}
     * @param roster        the roster the algorithm runs under
     * @param traceLog      where the algorithm's changes are recorded, or null not to record them. When supplied, the
     *                      linker is given a factory producing {@link RecordingEventImpl} instead of {@link EventImpl}.
     */
    public FlickerIntake(
            @NonNull final Configuration configuration,
            @NonNull final RosterWrapper roster,
            @Nullable final ConsensusTraceLog traceLog) {
        Objects.requireNonNull(configuration);
        Objects.requireNonNull(roster);

        this.traceLog = traceLog;
        this.roundsNonAncient =
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
        final BiFunction<PlatformEvent, List<EventImpl>, EventImpl> eventFactory = traceLog == null
                ? EventImpl::new
                : (event, parents) -> new RecordingEventImpl(event, parents, nameOf(event), traceLog);

        this.linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance(), eventFactory);
        this.consensus = new ConsensusImpl(configuration, Time.getCurrent(), new NoOpConsensusMetrics(), roster, 0L);
    }

    /**
     * The name {@link #add(String, PlatformEvent)} was given for this event, resolved by content hash at link time.
     */
    @NonNull
    private String nameOf(@NonNull final PlatformEvent event) {
        final String name = eventNameByHash.get(event.getHash());
        if (name == null) {
            throw new IllegalStateException("The linker asked for an event this harness never named. Every event "
                    + "reaching the linker must arrive through add(name, event).");
        }
        return name;
    }

    /**
     * Feed one event to the algorithm.
     * <p>
     * Exactly one event enters the algorithm per call. The three checks below enforce the authoring contract that
     * makes that true; each one rejects a graph that production would have accepted but processed at a different
     * moment, which is precisely what would invalidate a hand-derived expectation.
     *
     * @param name  the name this test knows the event by; {@link #event(String)} resolves it afterwards
     * @param event the event to add, which must be neither ancient nor a future event, and must be topologically
     *              after every event added so far
     * @return the rounds that reached consensus as a result of this event, in order
     */
    @NonNull
    public List<ConsensusRound> add(@NonNull final String name, @NonNull final PlatformEvent event) {
        hasher.hashEvent(event);
        eventNameByHash.put(event.getHash(), name);

        // Ancient first: the orphan buffer also drops ancient events, so without this check an ancient event would be
        // reported below as a topological ordering problem, which it is not.
        if (eventWindow.isAncient(event)) {
            throw new IllegalArgumentException(("Event '%s' is already ancient (birth round %d, ancient threshold %d). "
                            + "Production would silently drop it; a fixture that feeds one is not asserting what it "
                            + "appears to. Build the graph so it is fed while still non-ancient.")
                    .formatted(name, event.getBirthRound(), eventWindow.ancientThreshold()));
        }

        // A future event is one production would hold in its FutureEventBuffer and release later, at a point the
        // fixture author did not choose. Rather than reproducing the buffer, the harness refuses the event: a
        // hand-derived expectation cannot survive its subject being silently deferred. The predicate is production's
        // own, so it cannot drift from what DefaultConsensusEngine considers a future event.
        final long maximumReleasableRound =
                FutureEventBufferingOption.PENDING_CONSENSUS_ROUND.getMaximumReleasableRound(eventWindow);
        if (event.getBirthRound() > maximumReleasableRound) {
            throw new IllegalArgumentException(("Event '%s' is a future event (birth round %d, pending consensus "
                            + "round %d). Production would buffer it and feed it to consensus later. Build the graph "
                            + "so every event is ready for consensus when it is added.")
                    .formatted(name, event.getBirthRound(), maximumReleasableRound));
        }

        // The orphan buffer assigns nGen. In the real pipeline it is also what releases events to consensus, so the
        // harness checks that it released exactly the event it was handed rather than discarding the return value.
        // Having excluded ancient above, the only remaining reason it holds one back is a missing parent.
        final List<PlatformEvent> released = orphanBuffer.handleEvent(event);
        if (released.size() != 1 || released.getFirst() != event) {
            throw new IllegalArgumentException(
                    "Event '%s' was held back by the orphan buffer, which means its parents ".formatted(name)
                            + "were not all added first. Supply events in topological order.");
        }

        final EventImpl linked = linker.linkEvent(event);
        if (linked == null) {
            throw new IllegalStateException(
                    "Event '%s' passed the ancient check but the linker still rejected it".formatted(name));
        }
        linkedEventsByName.put(name, linked);

        final List<ConsensusRound> decided = consensus.addEvent(linked);
        consensusRounds.addAll(decided);

        if (!decided.isEmpty()) {
            // Advance all three windows, as the production path does: DefaultConsensusEngine advances the linker's,
            // the intake wiring feeds the same window to the orphan buffer, and the checks above read it.
            eventWindow = decided.getLast().getEventWindow();
            linker.setEventWindow(eventWindow);
            final List<PlatformEvent> unorphaned = orphanBuffer.setEventWindow(eventWindow);
            if (!unorphaned.isEmpty()) {
                throw new IllegalStateException("The orphan buffer released %d event(s) when the window advanced after "
                                .formatted(unorphaned.size())
                        + "'%s'. A topologically ordered graph cannot orphan anything.".formatted(name));
            }
        }

        return decided;
    }

    /**
     * Get the linked event this harness created for the given name.
     * <p>
     * The reference is held from the moment the event was linked, so it is always resolvable. Reachability is not
     * sufficiency: {@code clearMetadata} destroys values on the object itself, so a live read of the algorithm's
     * intermediate state is only valid immediately after the {@code add} that produced it.
     *
     * @param name the name given to {@link #add(String, PlatformEvent)}
     * @return the linked event, or null if no event was added under that name
     */
    @Nullable
    public EventImpl event(@NonNull final String name) {
        return linkedEventsByName.get(name);
    }

    /**
     * Restart from a snapshot, as a node does after a restart or reconnect.
     * <p>
     * This is what makes the restart, init-judge and roster-change family reachable: a roster is supplied only at
     * construction, so continuing a graph under a different roster means loading its snapshot into a second
     * harness and replaying the same events on top.
     *
     * @param snapshot the snapshot to continue from
     */
    public void loadSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        // Mirrors DefaultConsensusEngine#outOfBandSnapshotUpdate, minus the future event buffer this harness refuses
        // to model. The window moves to the snapshot's round, so a replay on top of it must supply events that are
        // still non-ancient relative to that round - which add() now checks.
        eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);
        linker.clear();
        linker.setEventWindow(eventWindow);
        orphanBuffer.setEventWindow(eventWindow);
        consensus.loadSnapshot(snapshot);
    }

    /**
     * @return the trace log this harness was constructed with
     * @throws IllegalStateException if it was constructed without one
     */
    @NonNull
    public ConsensusTraceLog getTraceLog() {
        if (traceLog == null) {
            throw new IllegalStateException("This FlickerIntake was constructed without a trace log");
        }
        return traceLog;
    }

    /**
     * @return every round that has reached consensus, in order
     */
    @NonNull
    public List<ConsensusRound> getConsensusRounds() {
        return Collections.unmodifiableList(consensusRounds);
    }
}
