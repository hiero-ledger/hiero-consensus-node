// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A class that stores a simple hand-made graph of {@code PlatformEvent}s for use in tests.
 */
public class SimplePlatformEventGraph implements SimpleGraph<PlatformEvent> {

    private final List<PlatformEvent> events;

    /**
     * Constructor
     *
     * @param events the events in the graph
     */
    public SimplePlatformEventGraph(@NonNull final List<PlatformEvent> events) {
        this.events = List.copyOf(events);
    }

    /**
     * Get the list of all events in the graph.
     *
     * @return the list of events
     */
    @NonNull
    public List<PlatformEvent> events() {
        return events;
    }

    /**
     * Get a specific event by index.
     *
     * @param index the index of the event
     * @return the event
     */
    @NonNull
    public PlatformEvent event(final int index) {
        return events.get(index);
    }

    /**
     * Create a list of events from the provided indices.
     *
     * @param indices the indices of events to include in the list
     * @return the list of events
     */
    @NonNull
    public List<PlatformEvent> events(@NonNull final int... indices) {
        final List<PlatformEvent> selectedEvents = new ArrayList<>();
        for (final int index : indices) {
            selectedEvents.add(events.get(index));
        }
        return Collections.unmodifiableList(selectedEvents);
    }

    @Override
    @NonNull
    public List<PlatformEvent> shuffledEvents(@NonNull final Random random) {
        final List<PlatformEvent> shuffledEvents = new ArrayList<>(events);
        Collections.shuffle(shuffledEvents, random);
        return shuffledEvents;
    }

    /**
     * Create a set of events from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of events
     */
    @NonNull
    public Set<PlatformEvent> eventSet(@NonNull final int... indices) {
        final Set<PlatformEvent> eventSet = new HashSet<>();
        for (final int index : indices) {
            eventSet.add(events.get(index));
        }
        return Collections.unmodifiableSet(eventSet);
    }

    /**
     * Get a set of event hashes from the provided indices.
     *
     * @param indices the indices of events to include in the set
     * @return the set of hashes
     */
    @NonNull
    public Set<Hash> hashes(@NonNull final int... indices) {
        return events(indices).stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
    }
}
