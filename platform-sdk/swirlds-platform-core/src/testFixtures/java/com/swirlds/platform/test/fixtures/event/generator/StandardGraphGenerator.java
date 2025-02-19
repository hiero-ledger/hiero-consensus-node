/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.fixtures.event.generator;

import static com.swirlds.platform.test.fixtures.event.EventUtils.staticDynamicValue;
import static com.swirlds.platform.test.fixtures.event.EventUtils.weightedChoice;
import static com.swirlds.platform.test.fixtures.event.RandomEventUtils.DEFAULT_FIRST_EVENT_TIME_CREATED;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.event.hashing.DefaultEventHasher;
import com.swirlds.platform.event.linking.ConsensusLinker;
import com.swirlds.platform.event.linking.InOrderLinker;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.event.DynamicValue;
import com.swirlds.platform.test.fixtures.event.DynamicValueGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A utility class for generating a graph of events.
 */
public class StandardGraphGenerator extends AbstractGraphGenerator {

    /**
     * A list of sources. There is one source per node that is being simulated.
     */
    private final List<EventSource> sources;

    /**
     * Determines the probability that a node becomes the other parent of an event.
     */
    private DynamicValueGenerator<List<List<Double>>> affinityMatrix;

    /**
     * The address book representing the event sources.
     */
    private AddressBook addressBook;

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

    /**
     * The platform context containing configuration for the internal consensus.
     */
    private PlatformContext platformContext;

    /**
     * The linker for events to use with the internal consensus.
     */
    private InOrderLinker inOrderLinker;

    /**
     * Construct a new StandardEventGenerator.
     * <p>
     * Note: once an event source has been passed to this constructor it should not be modified by the outer context.
     *
     * @param platformContext the platform context
     * @param seed            The random seed used to generate events.
     * @param eventSources    One or more event sources.
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext, final long seed, final EventSource... eventSources) {
        this(platformContext, seed, new ArrayList<>(Arrays.asList(eventSources)));
    }

    /**
     * Construct a new StandardEventGenerator.
     *
     * @param platformContext the platform context
     * @param seed            The random seed used to generate events.
     * @param eventSources    One or more event sources.
     */
    public StandardGraphGenerator(
            @NonNull PlatformContext platformContext, final long seed, @NonNull final List<EventSource> eventSources) {
        super(seed);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.sources = Objects.requireNonNull(eventSources);
        if (eventSources.isEmpty()) {
            throw new IllegalArgumentException("At least one event source is required");
        }

        buildAddressBookInitializeEventSources(eventSources);
        buildDefaultOtherParentAffinityMatrix();
        initializeInternalConsensus();
    }

    /**
     * Construct a new StandardEventGenerator.
     *
     * @param seed         The random seed used to generate events.
     * @param eventSources One or more event sources.
     * @param addressBook  The address book to use with the event sources.
     */
    public StandardGraphGenerator(
            @NonNull final PlatformContext platformContext,
            final long seed,
            @NonNull final List<EventSource> eventSources,
            @NonNull final AddressBook addressBook) {
        super(seed);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.sources = Objects.requireNonNull(eventSources);
        Objects.requireNonNull(addressBook);

        if (eventSources.isEmpty()) {
            throw new IllegalArgumentException("At least one event source is required");
        }

        setAddressBookInitializeEventSources(eventSources, addressBook);
        buildDefaultOtherParentAffinityMatrix();
        initializeInternalConsensus();
    }

    /**
     * Copy constructor.
     */
    private StandardGraphGenerator(final StandardGraphGenerator that) {
        this(that, that.getInitialSeed());
    }

    /**
     * Copy constructor, but with a different seed.
     */
    private StandardGraphGenerator(final StandardGraphGenerator that, final long seed) {
        super(seed);

        this.affinityMatrix = that.affinityMatrix.cleanCopy();
        this.sources = new ArrayList<>(that.sources.size());
        for (final EventSource sourceToCopy : that.sources) {
            final EventSource copy = sourceToCopy.copy();
            this.sources.add(copy);
        }
        this.addressBook = that.getAddressBook().copy();
        this.eventPeriodMean = that.eventPeriodMean;
        this.eventPeriodStandardDeviation = that.eventPeriodStandardDeviation;
        this.simultaneousEventFraction = that.simultaneousEventFraction;
        this.platformContext = that.platformContext;
        initializeInternalConsensus();
    }

    private void initializeInternalConsensus() {
        consensus = new ConsensusImpl(
                platformContext, new NoOpConsensusMetrics(), RosterRetriever.buildRoster(addressBook));
        inOrderLinker = new ConsensusLinker(platformContext, NodeId.of(0));
    }

    /**
     * builds a random address book, updates the weight of the addresses from the event sources, and initialize the node
     * ids of the event sources from the addresses.
     *
     * @param eventSources the event sources to initialize.
     */
    private void buildAddressBookInitializeEventSources(@NonNull final List<EventSource> eventSources) {
        final int eventSourceCount = eventSources.size();

        final AddressBook addressBook = RandomAddressBookBuilder.create(getRandom())
                .withSize(eventSourceCount)
                .build();
        setAddressBookInitializeEventSources(eventSources, addressBook);
    }

    /**
     * sets the address book, updates the weight of the addresses from the event sources, and initialize the node ids of
     * the event sources from the addresses.
     *
     * @param eventSources the event sources to initialize.
     * @param addressBook  the address book to use.
     */
    private void setAddressBookInitializeEventSources(
            @NonNull final List<EventSource> eventSources, @NonNull final AddressBook addressBook) {
        final int eventSourceCount = eventSources.size();

        this.addressBook = addressBook;
        for (int index = 0; index < eventSourceCount; index++) {
            final EventSource source = eventSources.get(index);
            final NodeId nodeId = addressBook.getNodeId(index);
            addressBook.updateWeight(nodeId, source.getWeight());
            source.setNodeId(nodeId);
        }
    }

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix An n by n matrix where n is the number of event sources. Each row defines the preference of
     *                       a particular node when choosing other parents. Node 0 is described by the first row, node 1
     *                       by the next, etc. Each entry should be a weight. Weights of self (i.e. the weights on the
     *                       diagonal) should be 0.
     */
    public void setOtherParentAffinity(final List<List<Double>> affinityMatrix) {
        setOtherParentAffinity(staticDynamicValue(affinityMatrix));
    }

    /**
     * Set the affinity of each node for choosing the parents of its events.
     *
     * @param affinityMatrix A dynamic n by n matrix where n is the number of event sources. Each entry should be a
     *                       weight. Weights of self (i.e. the weights on the diagonal) should be 0.
     */
    public void setOtherParentAffinity(final DynamicValue<List<List<Double>>> affinityMatrix) {
        this.affinityMatrix = new DynamicValueGenerator<>(affinityMatrix);
    }

    /**
     * Get the affinity vector for a particular node.
     *
     * @param eventIndex the current event index
     * @param nodeId     the node ID that is being requested
     */
    private List<Double> getOtherParentAffinityVector(final long eventIndex, final int nodeId) {
        return affinityMatrix.get(getRandom(), eventIndex).get(nodeId);
    }

    private void buildDefaultOtherParentAffinityMatrix() {
        final List<List<Double>> matrix = new ArrayList<>(sources.size());

        for (int nodeIndex = 0; nodeIndex < sources.size(); nodeIndex++) {
            final NodeId nodeId = addressBook.getNodeId(nodeIndex);
            final List<Double> affinityVector = new ArrayList<>(sources.size());
            for (int otherNodeIndex = 0; otherNodeIndex < sources.size(); otherNodeIndex++) {
                final NodeId otherNodeId = addressBook.getNodeId(otherNodeIndex);
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
     * Get the average difference in the timestamp between two adjacent events (in seconds).
     */
    public double getEventPeriodMean() {
        return eventPeriodMean;
    }

    /**
     * Set the average difference in the timestamp between two adjacent events (in seconds).
     *
     * @return this
     */
    public StandardGraphGenerator setEventPeriodMean(final double eventPeriodMean) {
        this.eventPeriodMean = eventPeriodMean;
        return this;
    }

    /**
     * Get the standard deviation of the difference of the timestamp between two adjacent events (in seconds).
     */
    public double getEventPeriodStandardDeviation() {
        return eventPeriodStandardDeviation;
    }

    /**
     * Set the standard deviation of the difference of the timestamp between two adjacent events (in seconds).
     *
     * @return this
     */
    public StandardGraphGenerator setEventPeriodStandardDeviation(final double eventPeriodStandardDeviation) {
        this.eventPeriodStandardDeviation = eventPeriodStandardDeviation;
        return this;
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
     *
     * @return this
     */
    public StandardGraphGenerator setSimultaneousEventFraction(final double simultaneousEventFraction) {
        this.simultaneousEventFraction = simultaneousEventFraction;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StandardGraphGenerator cleanCopy() {
        return new StandardGraphGenerator(this);
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
    public EventSource getSource(final NodeId nodeID) {
        final int nodeIndex = addressBook.getIndexOfNodeId(nodeID);
        return sources.get(nodeIndex);
    }

    /**
     * Get the event source for a particular node index.
     *
     * @return the event source
     */
    public EventSource getSourceByIndex(final int nodeIndex) {
        return sources.get(nodeIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AddressBook getAddressBook() {
        return addressBook;
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
     * {@inheritDoc}
     */
    @Override
    public StandardGraphGenerator cleanCopy(final long newSeed) {
        return new StandardGraphGenerator(this, newSeed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
    private EventSource getNextOtherParentSource(final long eventIndex, final EventSource source) {
        final List<Double> affinityVector =
                getOtherParentAffinityVector(eventIndex, addressBook.getIndexOfNodeId(source.getNodeId()));
        final int nodeIndex = weightedChoice(getRandom(), affinityVector);
        return sources.get(nodeIndex);
    }

    /**
     * Get the next timestamp for the next event.
     */
    private Instant getNextTimestamp(final EventSource source, final NodeId otherParentId) {
        if (previousTimestamp == null) {
            previousTimestamp = DEFAULT_FIRST_EVENT_TIME_CREATED;
            previousCreatorId = source.getNodeId();
            return previousTimestamp;
        }

        final EventImpl previousEvent = source.getLatestEvent(getRandom());
        final Instant previousTimestampForSource =
                previousEvent == null ? Instant.ofEpochSecond(0) : previousEvent.getTimeCreated();

        final boolean shouldRepeatTimestamp = getRandom().nextDouble() < simultaneousEventFraction;

        // don't repeat a timestamp if the previously emitted event is either parent of the new event
        final boolean forbidRepeatTimestamp = previousCreatorId != null
                && (previousCreatorId.equals(source.getNodeId()) || previousCreatorId.equals(otherParentId));
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
     * {@inheritDoc}
     */
    @Override
    public EventImpl buildNextEvent(final long eventIndex) {
        final EventSource source = getNextEventSource(eventIndex);
        final EventSource otherParentSource = getNextOtherParentSource(eventIndex, source);

        final long birthRound = consensus.getLastRoundDecided() + 1;

        final EventImpl next = source.generateEvent(
                getRandom(),
                eventIndex,
                otherParentSource,
                getNextTimestamp(source, otherParentSource.getNodeId()),
                birthRound);

        // The event given to the internal consensus needs its own EventImpl & PlatformEvent for metadata to be kept
        // separate from the event that is returned to the caller.  This InOrderLinker wraps the event in an EventImpl
        // and links it. The event must be hashed and have a descriptor built for its use in the InOrderLinker.
        // This may leak memory, but is fine in the current testing framework.
        // When the test ends any memory used will be released.
        new DefaultEventHasher().hashEvent(next.getBaseEvent());
        final PlatformEvent tmp = next.getBaseEvent().copyGossipedData();
        tmp.setHash(next.getBaseEvent().getHash());
        consensus.addEvent(inOrderLinker.linkEvent(tmp));

        return next;
    }

    @Override
    public void setPreviousTimestamp(final Instant previousTimestamp) {
        this.previousTimestamp = previousTimestamp;
    }
}
