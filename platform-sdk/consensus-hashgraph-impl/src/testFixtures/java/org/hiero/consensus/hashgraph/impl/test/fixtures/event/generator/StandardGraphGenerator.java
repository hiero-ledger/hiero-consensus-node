// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;

import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.EventUtils.staticDynamicValue;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.EventUtils.weightedChoice;
import static org.hiero.consensus.hashgraph.impl.test.fixtures.event.RandomEventUtils.DEFAULT_FIRST_EVENT_TIME_CREATED;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.metrics.NoOpConsensusMetrics;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.DynamicValue;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.DynamicValueGenerator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.GuiEventStorage;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.HashgraphGuiSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.util.StandardGuiSource;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * A utility class for generating a graph of events.
 */
public class StandardGraphGenerator implements GraphGenerator {
    /** The default maximum number of other-parents for each event */
    private static final int DEFAULT_MAX_OTHER_PARENTS = 1;

    /**
     * A list of sources. There is one source per node that is being simulated.
     */
    private final List<EventSource> sources;
    /**
     * The initial seed of this generator.
     */
    private final long initialSeed;

    /** The maximum number of other-parents that newly generated events should have */
    private final int maxOtherParents;

    /** The highest birth round of created events for each creator */
    private final Map<NodeId, Long> maxBirthRoundPerCreator;

    /**
     * Determines the probability that a node becomes the other parent of an event.
     */
    private DynamicValueGenerator<List<List<Double>>> affinityMatrix;

    /**
     * The roster representing the event sources.
     */
    private Roster roster;

    /**
     * The average difference in the timestamp between two adjacent events (in seconds).
     */
    private double eventPeriodMean = 0.000_1;

    /**
     * The standard deviation of the difference of the timestamp between two adjacent events (in seconds).
     */
    private double eventPeriodStandardDeviation = 0.000_01;

    /**
     * The probability, as a fraction of 1.0, that an event has the same timestamp as the proceeding event. If the
     * proceeding event has the same self parent then this is ignored and the events are not made to be simultaneous.
     */
    private double simultaneousEventFraction = 0.01;

    /**
     * The timestamp of the previously emitted event.
     */
    private Instant previousTimestamp;

    /**
     * The creator of the previously emitted event.
     */
    private NodeId previousCreatorId;

    /**
     * The consensus implementation for determining birth rounds of events.
     */
    private ConsensusImpl consensus;

    /** Used to assign nGen values to events. This value is used by consensus, so it must be set. */
    private OrphanBuffer orphanBuffer;

    /** The latest snapshot to be produced by {@link #consensus} */
    private ConsensusSnapshot consensusSnapshot;

    /**
     * The platform context containing configuration for the internal consensus.
     */
    private final PlatformContext platformContext;

    /**
     * The linker for events to use with the internal consensus.
     */
    private ConsensusLinker linker;
    /**
     * The total number of events that have been emitted by this generator.
     */
    private long numEventsGenerated;
    /**
     * The source of all randomness for this class.
     */
    private Random random;

    /**
     * Same as {@link #StandardGraphGenerator(PlatformContext, long, int, List, Roster)} with:
     * <ul>
     *     <li>maxOtherParents set to {@value #DEFAULT_MAX_OTHER_PARENTS}</li>
     *     <li>roster generated from event sources</li>
     * </ul>
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext, final long seed, final EventSource... eventSources) {
        this(platformContext, seed, Arrays.asList(eventSources));
    }

    /**
     * Same as {@link #StandardGraphGenerator(PlatformContext, long, int, List, Roster)} with:
     * <ul>
     *     <li>maxOtherParents set to {@value #DEFAULT_MAX_OTHER_PARENTS}</li>
     *     <li>roster generated from event sources</li>
     * </ul>
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext,
            final long seed,
            @NonNull final List<EventSource> eventSources) {
        this(platformContext, seed, DEFAULT_MAX_OTHER_PARENTS, eventSources);
    }

    /**
     * Same as {@link #StandardGraphGenerator(PlatformContext, long, int, List, Roster)} with:
     * <ul>
     *     <li>roster generated from event sources</li>
     * </ul>
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext,
            final long seed,
            final int maxOtherParents,
            @NonNull final List<EventSource> eventSources) {
        this(
                platformContext,
                seed,
                maxOtherParents,
                eventSources,
                RandomRosterBuilder.create(new Random(seed))
                        .withSize(eventSources.size())
                        .build());
    }

    /**
     * Same as {@link #StandardGraphGenerator(PlatformContext, long, int, List, Roster)} with:
     * <ul>
     *     <li>maxOtherParents set to {@value #DEFAULT_MAX_OTHER_PARENTS}</li>
     * </ul>
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext,
            final long seed,
            @NonNull final List<EventSource> eventSources,
            @NonNull final Roster roster) {
        this(platformContext, seed, DEFAULT_MAX_OTHER_PARENTS, eventSources, roster);
    }

    /**
     * Construct a new StandardEventGenerator.
     * <p>
     * Note: once an event source has been passed to this constructor it should not be modified by the outer context.
     *
     * @param platformContext The platform context
     * @param seed The random seed used to generate events.
     * @param maxOtherParents The maximum number of other-parents for each event.
     * @param eventSources One or more event sources.
     * @param roster The roster to use.
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext,
            final long seed,
            final int maxOtherParents,
            @NonNull final List<EventSource> eventSources,
            @NonNull final Roster roster) {
        this.initialSeed = seed;
        this.maxOtherParents = maxOtherParents;
        this.random = new Random(seed);
        this.maxBirthRoundPerCreator = new HashMap<>();
        this.platformContext = Objects.requireNonNull(platformContext);
        // we create a new list because we need to be able to remove sources later if nodes are removed
        this.sources = new ArrayList<>(Objects.requireNonNull(eventSources));

        if (eventSources.isEmpty()) {
            throw new IllegalArgumentException("At least one event source is required");
        }
        this.roster = roster;
        setAddressBookInitializeEventSources(eventSources, roster);
        buildDefaultOtherParentAffinityMatrix();
        initializeInternalConsensus();
    }

    /**
     * Copy constructor, but with a different seed.
     */
    private StandardGraphGenerator(final StandardGraphGenerator that, final long seed) {
        this.initialSeed = seed;
        this.maxOtherParents = that.maxOtherParents;
        this.random = new Random(seed);
        this.maxBirthRoundPerCreator = new HashMap<>();

        this.affinityMatrix = that.affinityMatrix.cleanCopy();
        this.sources = new ArrayList<>(that.sources.size());
        for (final EventSource sourceToCopy : that.sources) {
            final EventSource copy = sourceToCopy.copy();
            this.sources.add(copy);
        }
        this.roster = that.roster;
        this.eventPeriodMean = that.eventPeriodMean;
        this.eventPeriodStandardDeviation = that.eventPeriodStandardDeviation;
        this.simultaneousEventFraction = that.simultaneousEventFraction;
        this.platformContext = that.platformContext;
        initializeInternalConsensus();
    }

    private void initializeInternalConsensus() {
        consensus = new ConsensusImpl(
                platformContext.getConfiguration(), platformContext.getTime(), new NoOpConsensusMetrics(), roster);
        linker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        orphanBuffer = new DefaultOrphanBuffer(platformContext.getMetrics(), mock(IntakeEventCounter.class));
    }

    /**
     * sets the address book, updates the weight of the addresses from the event sources, and initialize the node ids of
     * the event sources from the addresses.
     *
     * @param eventSources the event sources to initialize.
     * @param roster the roster to use.
     */
    private void setAddressBookInitializeEventSources(
            @NonNull final List<EventSource> eventSources, @NonNull final Roster roster) {
        final int eventSourceCount = eventSources.size();

        for (int index = 0; index < eventSourceCount; index++) {
            final EventSource source = eventSources.get(index);
            final NodeId nodeId = NodeId.of(roster.rosterEntries().get(index).nodeId());
            source.setNodeId(nodeId);
        }
    }

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix An n by n matrix where n is the number of event sources. Each row defines the preference of
     * a particular node when choosing other parents. Node 0 is described by the first row, node 1 by the next, etc.
     * Each entry should be a weight. Weights of self (i.e. the weights on the diagonal) should be 0.
     */
    public void setOtherParentAffinity(final List<List<Double>> affinityMatrix) {
        setOtherParentAffinity(staticDynamicValue(affinityMatrix));
    }

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix A dynamic n by n matrix where n is the number of event sources. Each entry should be a
     * weight. Weights of self (i.e. the weights on the diagonal) should be 0.
     */
    public void setOtherParentAffinity(final DynamicValue<List<List<Double>>> affinityMatrix) {
        this.affinityMatrix = new DynamicValueGenerator<>(affinityMatrix);
    }

    /**
     * Get the affinity vector for a particular node.
     *
     * @param eventIndex the current event index
     * @param nodeId the node ID that is being requested
     */
    private List<Double> getOtherParentAffinityVector(final long eventIndex, final int nodeId) {
        return affinityMatrix.get(getRandom(), eventIndex).get(nodeId);
    }

    private void buildDefaultOtherParentAffinityMatrix() {
        final List<List<Double>> matrix = new ArrayList<>(sources.size());

        for (int nodeIndex = 0; nodeIndex < sources.size(); nodeIndex++) {
            final long nodeId = roster.rosterEntries().get(nodeIndex).nodeId();
            final List<Double> affinityVector = new ArrayList<>(sources.size());
            for (int otherNodeIndex = 0; otherNodeIndex < sources.size(); otherNodeIndex++) {
                final long otherNodeId =
                        roster.rosterEntries().get(otherNodeIndex).nodeId();
                if (Objects.equals(nodeId, otherNodeId)) {
                    affinityVector.add(0.0);
                } else {
                    affinityVector.add(1.0);
                }
            }
            matrix.add(affinityVector);
        }

        affinityMatrix = new DynamicValueGenerator<>(staticDynamicValue(matrix));
    }

    /**
     * Set the probability, as a fraction of 1.0, that an event has the same timestamp as the proceeding event. If the
     * proceeding event has the same self parent then this is ignored and the events are not made to be simultaneous.
     */
    public double getSimultaneousEventFraction() {
        return simultaneousEventFraction;
    }

    /**
     * Get the probability, as a fraction of 1.0, that an event has the same timestamp as the proceeding event. If the
     * proceeding event has the same self parent then this is ignored and the events are not made to be simultaneous.
     */
    public void setSimultaneousEventFraction(final double simultaneousEventFraction) {
        this.simultaneousEventFraction = simultaneousEventFraction;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StandardGraphGenerator cleanCopy() {
        return new StandardGraphGenerator(this, this.initialSeed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfSources() {
        return sources.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource getSource(@NonNull final NodeId nodeID) {
        final int nodeIndex = RosterUtils.getIndex(roster, nodeID.id());
        return sources.get(nodeIndex);
    }

    @Override
    @NonNull
    public EventSource getSourceByIndex(final int nodeIndex) {
        return sources.get(nodeIndex);
    }

    @Override
    public @NonNull Roster getRoster() {
        return roster;
    }

    /**
     * Returns the weight of each source, used to determine the likelihood of that source producing the next event
     * compared to the other sources. Could be static or dynamic depending on how many events have already been
     * generated.
     *
     * @param eventIndex the index of the event
     * @return list of new event weights
     */
    private List<Double> getSourceWeights(final long eventIndex) {
        final List<Double> sourceWeights = new ArrayList<>(sources.size());
        for (final EventSource source : sources) {
            sourceWeights.add(source.getNewEventWeight(getRandom(), eventIndex));
        }

        return sourceWeights;
    }

    /**
     * Child classes should reset internal metadata in this method.
     */
    protected void resetInternalData() {
        for (final EventSource source : sources) {
            source.reset();
        }
        previousTimestamp = null;
        previousCreatorId = null;
        initializeInternalConsensus();
    }

    /**
     * Get the next node that is creating an event.
     */
    private EventSource getNextEventSource(final long eventIndex) {
        final int nodeIndex = weightedChoice(getRandom(), getSourceWeights(eventIndex));
        return sources.get(nodeIndex);
    }

    /**
     * Get the node that will be the other parent for the new event.
     *
     * @param source The node that is creating the event.
     */
    private @Nullable EventSource getNextOtherParentSource(final long eventIndex, final EventSource source) {
        if (roster.rosterEntries().size() == 1) {
            return null;
        }
        final List<Double> affinityVector = getOtherParentAffinityVector(
                eventIndex, RosterUtils.getIndex(roster, source.getNodeId().id()));
        final int nodeIndex = weightedChoice(getRandom(), affinityVector);
        return sources.get(nodeIndex);
    }

    /**
     * Get the next timestamp for the next event.
     */
    private Instant getNextTimestamp(
            @NonNull final EventSource source, @NonNull final Collection<NodeId> otherParentIds) {
        if (previousTimestamp == null) {
            previousTimestamp = DEFAULT_FIRST_EVENT_TIME_CREATED;
            previousCreatorId = source.getNodeId();
            return previousTimestamp;
        }

        final PlatformEvent previousEvent = source.getLatestEvent(getRandom());
        final Instant previousTimestampForSource =
                previousEvent == null ? Instant.ofEpochSecond(0) : previousEvent.getTimeCreated();

        final boolean shouldRepeatTimestamp = getRandom().nextDouble() < simultaneousEventFraction;

        // don't repeat a timestamp if the previously emitted event is either parent of the new event
        final boolean forbidRepeatTimestamp = previousCreatorId != null
                && (previousCreatorId.equals(source.getNodeId()) || otherParentIds.contains(previousCreatorId));
        if (!previousTimestampForSource.equals(previousTimestamp) && shouldRepeatTimestamp && !forbidRepeatTimestamp) {
            return previousTimestamp;
        } else {
            final double delta = Math.max(
                    0.000_000_001,
                    eventPeriodMean + eventPeriodStandardDeviation * getRandom().nextGaussian());

            final long deltaSeconds = (int) delta;
            final long deltaNanoseconds = (int) ((delta - deltaSeconds) * 1_000_000_000);
            final Instant timestamp =
                    previousTimestamp.plusSeconds(deltaSeconds).plusNanos(deltaNanoseconds);
            previousTimestamp = timestamp;
            previousCreatorId = source.getNodeId();
            return timestamp;
        }
    }

    /**
     * Build the event that will be returned by getNextEvent.
     *
     * @param eventIndex the index of the event to build
     */
    public PlatformEvent buildNextEvent(final long eventIndex) {
        final EventSource source = getNextEventSource(eventIndex);
        // using map for parents in case of duplicate sources
        final Map<NodeId, EventSource> otherParentSources = new HashMap<>();
        for (int i = 0; i < maxOtherParents; i++) {
            final EventSource otherParentSource = getNextOtherParentSource(eventIndex, source);
            if (otherParentSource != null) {
                otherParentSources.put(otherParentSource.getNodeId(), otherParentSource);
            }
        }

        final long birthRound = consensus.getLastRoundDecided() + 1;

        final PlatformEvent next = source.generateEvent(
                getRandom(),
                eventIndex,
                otherParentSources.values(),
                getNextTimestamp(source, otherParentSources.keySet()),
                birthRound);

        new DefaultEventHasher().hashEvent(next);
        updateConsensus(next);
        return next;
    }

    private void updateConsensus(@NonNull final PlatformEvent e) {
        /* The event given to the internal consensus needs its own EventImpl & PlatformEvent for
        metadata to be kept separate from the event that is returned to the caller.  The orphan
        buffer assigns an nGen value. The SimpleLinker wraps the event in an EventImpl and links
        it. The event must be hashed and have a descriptor built for its use in the SimpleLinker. */
        final PlatformEvent copy = e.copyGossipedData();
        final List<PlatformEvent> events = orphanBuffer.handleEvent(copy);
        for (final PlatformEvent event : events) {
            final EventImpl linkedEvent = linker.linkEvent(event);
            if (linkedEvent == null) {
                continue;
            }
            final List<ConsensusRound> consensusRounds = consensus.addEvent(linkedEvent);
            if (consensusRounds.isEmpty()) {
                continue;
            }
            // if we reach consensus, save the snapshot for future use
            consensusSnapshot = consensusRounds.getLast().getSnapshot();
            linker.setEventWindow(consensusRounds.getLast().getEventWindow());
        }
    }

    @Override
    public void removeNode(@NonNull final NodeId nodeId) {
        // currently, we only support removing a node at restart, so this process mimics what happens at restart

        // remove the node from the address book and the sources
        final int nodeIndex = RosterUtils.getIndex(roster, nodeId.id());
        sources.remove(nodeIndex);

        final List<RosterEntry> newRosterEntries = new ArrayList<>(roster.rosterEntries());
        newRosterEntries.remove(nodeIndex);
        this.roster = new Roster(newRosterEntries);

        buildDefaultOtherParentAffinityMatrix();
        // save all non-ancient events
        final List<EventImpl> nonAncientEvents = new ArrayList<>(linker.getNonAncientEvents());
        nonAncientEvents.sort(Comparator.comparingLong(e -> e.getBaseEvent().getNGen()));
        // reinitialize the internal consensus with the last snapshot
        initializeInternalConsensus();
        consensus.loadSnapshot(consensusSnapshot);
        EventWindowUtils.createEventWindow(
                consensusSnapshot,
                platformContext
                        .getConfiguration()
                        .getConfigData(ConsensusConfig.class)
                        .roundsNonAncient());
        linker.setEventWindow(EventWindowUtils.createEventWindow(
                consensusSnapshot,
                platformContext
                        .getConfiguration()
                        .getConfigData(ConsensusConfig.class)
                        .roundsNonAncient()));
        // re-add all non-ancient events
        for (final EventImpl event : nonAncientEvents) {
            updateConsensus(event.getBaseEvent());
        }
    }

    @SuppressWarnings("unused") // useful for debugging
    private HashgraphGuiSource createGuiSource() {
        return new StandardGuiSource(
                getRoster(), new GuiEventStorage(consensus, linker, platformContext.getConfiguration()));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Child classes must call super.reset() if they override this method.
     */
    @Override
    public final void reset() {
        numEventsGenerated = 0;
        random = new Random(initialSeed);
        maxBirthRoundPerCreator.clear();
        resetInternalData();
    }

    /**
     * {@inheritDoc}
     */
    public final PlatformEvent generateEvent() {
        return generateEventWithoutIndex();
    }

    /**
     * The same as {@link #generateEvent()}, but does not set the stream sequence number.
     */
    public final PlatformEvent generateEventWithoutIndex() {
        final PlatformEvent next = buildNextEvent(numEventsGenerated);
        next.signalPrehandleCompletion();
        numEventsGenerated++;
        updateMaxBirthRound(next);
        return next;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final long getNumEventsGenerated() {
        return numEventsGenerated;
    }

    /**
     * Get the Random object to be used by this class.
     */
    protected final Random getRandom() {
        return random;
    }

    private void updateMaxBirthRound(@NonNull final PlatformEvent event) {
        maxBirthRoundPerCreator.merge(event.getCreatorId(), event.getBirthRound(), Math::max);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMaxBirthRound(@Nullable final NodeId creatorId) {
        return maxBirthRoundPerCreator.getOrDefault(creatorId, ConsensusConstants.ROUND_NEGATIVE_INFINITY);
    }
}
