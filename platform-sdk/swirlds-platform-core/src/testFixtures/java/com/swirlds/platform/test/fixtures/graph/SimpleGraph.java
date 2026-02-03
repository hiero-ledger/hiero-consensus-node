// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;

/**
 * A class that stores a simple hand-made graph for use in tests.
 */
public class SimpleGraph {
    private final Random random;
    private final List<PlatformEvent> events;
    private final List<EventImpl> eventImpls;

    /**
     * Constructor
     *
     * @param random the source of randomness
     * @param events the events in the graph
     */
    public SimpleGraph(@NonNull final Random random, @NonNull final PlatformEvent... events) {
        this.random = random;
        this.events = List.of(events);
        final List<EventImpl> eventImpls = new ArrayList<>();
        // we use the orphan buffer to assign nGen values to the events
        final DefaultOrphanBuffer orphanBuffer =
                new DefaultOrphanBuffer(new NoOpMetrics(), new NoOpIntakeEventCounter());
        final ConsensusLinker consensusLinker = new ConsensusLinker(NoOpLinkerLogsAndMetrics.getInstance());
        for (final PlatformEvent event : events) {
            final List<PlatformEvent> unorphanedEvents = orphanBuffer.handleEvent(event);
            if (unorphanedEvents.size() != 1) {
                throw new IllegalArgumentException("All events in the simple graph must be non-orphans");
            }
            final EventImpl linkedEvent = consensusLinker.linkEvent(event);
            if (linkedEvent == null) {
                throw new IllegalArgumentException("Failed to link event in simple graph");
            }
            if (event.getConsensusTimestamp() != null) {
                linkedEvent.setConsensus(true);
            }
            eventImpls.add(linkedEvent);
        }
        this.eventImpls = Collections.unmodifiableList(eventImpls);
    }

    /**
     * Get the list of all events in the graph.
     *
     * @return the list of events
     */
    public @NonNull List<PlatformEvent> events() {
        return events;
    }

    /**
     * Get a specific event by index.
     *
     * @param index the index of the event
     * @return the event
     */
    public @NonNull PlatformEvent event(final int index) {
        return events.get(index);
    }

    /**
     * Create a list of events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    public @NonNull List<PlatformEvent> events(@NonNull final int... indices) {
        final List<PlatformEvent> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(events.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);
    }

    /**
     * Create a set of events from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of events
     */
    public @NonNull Set<PlatformEvent> eventSet(@NonNull final int... indices) {
        final Set<PlatformEvent> eventSet = new HashSet<>();
        for (final int index : indices) {
            eventSet.add(events.get(index));
        }
        return Collections.unmodifiableSet(eventSet);
    }

    /**
     * Get all linked events in the graph.
     *
     * @return the list of events
     */
    public @NonNull List<EventImpl> impls() {
        return eventImpls;
    }

    /**
     * Get all linked events in a random order.
     *
     * @return the list of events
     */
    public @NonNull List<EventImpl> shuffledImpls() {
        final List<EventImpl> shuffledEvents = new ArrayList<>(eventImpls);
        Collections.shuffle(shuffledEvents, random);
        return shuffledEvents;
    }

    /**
     * Create a list of linked events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    public @NonNull List<EventImpl> impls(@NonNull final int... indices) {
        final List<EventImpl> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(eventImpls.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);
    }

    /**
     * Get a specific linked event by index.
     *
     * @param index the index of the event
     * @return the event
     */
    public @NonNull EventImpl impl(final int index) {
        return eventImpls.get(index);
    }

    /**
     * Get a set of event hashes from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of hashes
     */
    public @NonNull Set<Hash> hashes(@NonNull final int... indices) {
        return events(indices).stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
    }
}
