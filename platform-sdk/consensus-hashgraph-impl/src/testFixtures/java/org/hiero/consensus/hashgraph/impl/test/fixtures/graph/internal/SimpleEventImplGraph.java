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

/**
 * A class that stores a simple hand-made graph of linked {@code EventImpl}s for use in tests.
 */
public class SimpleEventImplGraph implements SimpleGraph<EventImpl> {

    private final List<EventImpl> events;

    /**
     * Constructor
     *
     * @param events the events in the graph
     */
    public SimpleEventImplGraph(@NonNull final List<PlatformEvent> events) {
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
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<EventImpl> events() {
        return events;
    }

    /**
     * Get all linked events in a random order.
     *
     * @return the list of events
     */
    @NonNull
    public List<EventImpl> shuffledEvents(@NonNull final Random random) {
        final List<EventImpl> shuffledEvents = new ArrayList<>(events);
        Collections.shuffle(shuffledEvents, random);
        return shuffledEvents;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<EventImpl> eventSet(@NonNull int... indices) {
        final Set<EventImpl> eventSet = new HashSet<>();
        for (final int index : indices) {
            eventSet.add(events.get(index));
        }
        return Collections.unmodifiableSet(eventSet);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Hash> hashes(@NonNull int... indices) {
        return events(indices).stream()
                .map(EventImpl::getBaseEvent)
                .map(PlatformEvent::getHash)
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<EventImpl> events(@NonNull final int... indices) {
        final List<EventImpl> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(events.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventImpl event(final int index) {
        return events.get(index);
    }
}
