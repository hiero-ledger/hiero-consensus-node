// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Keeps track of events created that have no children. These events are candidates to be used as parents when creating
 * a new event.
 */
public class ChildlessEventTracker {

    private final Set<EventDescriptorWrapper> childlessEvents = new HashSet<>();
    private final Map<NodeId, EventDescriptorWrapper> eventsByCreator = new HashMap<>();

    /**
     * Add a new event. Parents are removed from the set of childless events. Event is ignored if there is another event
     * from the same creator with a higher generation. Causes any event by the same creator, if present, to be removed
     * if it has a lower generation. This is true even if the event being added is not a direct child (possible if there
     * has been branching).
     *
     * @param eventDescriptorWrapper the event to add
     * @param parents         the parents of the event being added
     */
    public void addEvent(
            @NonNull final EventDescriptorWrapper eventDescriptorWrapper,
            @NonNull final List<EventDescriptorWrapper> parents) {
        Objects.requireNonNull(eventDescriptorWrapper);

        final EventDescriptorWrapper existingEvent = eventsByCreator.get(eventDescriptorWrapper.creator());
        if (existingEvent != null) {
            if (existingEvent.eventDescriptor().generation()
                    >= eventDescriptorWrapper.eventDescriptor().generation()) {
                // Only add a new event if it has the highest generation of all events observed so far.
                return;
            } else {
                // Remove the existing event if it has a lower generation than the new event.
                removeEvent(existingEvent);
            }
        }

        insertEvent(eventDescriptorWrapper);

        for (final EventDescriptorWrapper parent : parents) {
            removeEvent(parent);
        }
    }

    /**
     * Register a self event. Removes parents but does not add the event to the set of childless events.
     *
     * @param parents the parents of the self event
     */
    public void registerSelfEventParents(@NonNull final List<EventDescriptorWrapper> parents) {
        for (final EventDescriptorWrapper parent : parents) {
            childlessEvents.remove(parent);
        }
    }

    /**
     * Remove ancient events.
     *
     * @param eventWindow the event window
     */
    public void pruneOldEvents(@NonNull final EventWindow eventWindow) {
        for (final EventDescriptorWrapper event : getChildlessEvents()) {
            if (eventWindow.isAncient(event)) {
                removeEvent(event);
            }
        }
    }

    /**
     * Get a list of non-ancient childless events.
     *
     * @return the childless events, this list is safe to modify
     */
    @NonNull
    public List<EventDescriptorWrapper> getChildlessEvents() {
        return new ArrayList<>(childlessEvents);
    }

    /**
     * Insert an event into this data structure.
     */
    private void insertEvent(@NonNull final EventDescriptorWrapper eventDescriptorWrapper) {
        childlessEvents.add(eventDescriptorWrapper);
        eventsByCreator.put(eventDescriptorWrapper.creator(), eventDescriptorWrapper);
    }

    /**
     * Remove an event from this data structure.
     */
    private void removeEvent(@NonNull final EventDescriptorWrapper eventDescriptorWrapper) {
        final boolean removed = childlessEvents.remove(eventDescriptorWrapper);
        if (removed) {
            eventsByCreator.remove(eventDescriptorWrapper.creator());
        }
    }

    /**
     * Clear the internal state of this object.
     */
    public void clear() {
        childlessEvents.clear();
        eventsByCreator.clear();
    }

    @NonNull
    public String toString() {
        if (childlessEvents.isEmpty()) {
            return "Childless events: none\n";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("Childless events:\n");

        for (final EventDescriptorWrapper event : childlessEvents) {
            sb.append("  - ").append(event).append("\n");
        }
        return sb.toString();
    }
}
