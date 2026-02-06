// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.linking.ConsensusLinker;
import org.hiero.consensus.hashgraph.impl.linking.NoOpLinkerLogsAndMetrics;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraph;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;

public class SimpleEventImplGraph implements SimpleGraph<EventImpl> {

    private final Random random;
    private final List<EventImpl> events;

    public SimpleEventImplGraph(@NonNull final Random random, @NonNull final List<PlatformEvent> events) {
        this.random = random;
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
        this.events = Collections.unmodifiableList(eventImpls);
    }

    /**
     * Get all linked events in the graph.
     *
     * @return the list of events
     */
    public @NonNull List<EventImpl> events() {
        return events;
    }

    /**
     * Get all linked events in a random order.
     *
     * @return the list of events
     */
    public @NonNull List<EventImpl> shuffledEvents() {
        final List<EventImpl> shuffledEvents = new ArrayList<>(events);
        Collections.shuffle(shuffledEvents, random);
        return shuffledEvents;
    }

    @Override
    public @NonNull Set<EventImpl> eventSet(@NonNull int... indices) {
        final Set<EventImpl> eventSet = new HashSet<>();
        for (final int index : indices) {
            eventSet.add(events.get(index));
        }
        return Collections.unmodifiableSet(eventSet);
    }

    @Override
    public @NonNull Set<Hash> hashes(@NonNull int... indices) {
        return events(indices).stream()
                .map(EventImpl::getBaseEvent)
                .map(PlatformEvent::getHash)
                .collect(Collectors.toSet());
    }

    /**
     * Create a list of linked events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    public @NonNull List<EventImpl> events(@NonNull final int... indices) {
        final List<EventImpl> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(events.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);
    }

    /**
     * Get a specific linked event by index.
     *
     * @param index the index of the event
     * @return the event
     */
    public @NonNull EventImpl event(final int index) {
        return events.get(index);
    }
}
