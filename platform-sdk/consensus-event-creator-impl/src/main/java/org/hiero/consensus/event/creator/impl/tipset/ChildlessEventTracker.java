// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Keeps track of events created that have no children. These events are candidates to be used as parents when creating
 * a new event. This class is a helper class and does not do ancient window checking on its own. It is up to the
 * caller to only pass non-ancient events and to prune old events when necessary.
 */
public class ChildlessEventTracker {

    /**
     * A map of events created by our peers that have no known children. These events are eligible to be
     * used as other parents the next time this node creates an event.
     */
    private final Map<EventDescriptorWrapper, PlatformEvent> childlessEvents = new HashMap<>();

    /**
     * A map of childless events keyed by node id. Maintaining a single event per node allows us to
     * handle branching appropriately.
     */
    private final Map<NodeId, PlatformEvent> eventsByCreator = new HashMap<>();

    /**
     * Add a new event. Parents are removed from the set of childless events. Event is ignored if there is another event
     * from the same creator with a higher nGen. Causes any event by the same creator, if present, to be removed
     * if it has a lower nGen. This is true even if the event being added is not a direct child (possible if there
     * has been branching).
     *
     * @param event the event to add
     */
    public void addEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);

        final PlatformEvent existingEvent = eventsByCreator.get(event.getCreatorId());
        if (existingEvent != null) {
            if (existingEvent.getNGen() >= event.getNGen()) {
                // Only add a new event if it has the highest ngen of all events observed so far.
                return;
            } else {
                // Remove the existing event if it has a lower ngen than the new event.
                removeEvent(existingEvent.getDescriptor());
            }
        }

        insertEvent(event);

        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            removeEvent(parent);
        }
    }

    /**
     * Register a self event. Removes parents but does not add the event to the set of childless events, because
     * we only track childless events created by other nodes that we might use as other parents in the future.
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
        final Set<EventDescriptorWrapper> keysToRemove = new HashSet<>();
        childlessEvents.keySet().stream().filter(eventWindow::isAncient).forEach(keysToRemove::add);
        keysToRemove.forEach(this::removeEvent);
    }

    /**
     * Get a list of non-ancient childless events.
     *
     * @return the childless events, this list is safe to modify
     */
    @NonNull
    public Collection<PlatformEvent> getChildlessEvents() {
        return Collections.unmodifiableCollection(childlessEvents.values());
    }

    /**
     * Insert an event into this data structure.
     */
    private void insertEvent(@NonNull final PlatformEvent event) {
        childlessEvents.put(event.getDescriptor(), event);
        eventsByCreator.put(event.getCreatorId(), event);
    }

    /**
     * Remove an event from this data structure.
     */
    private void removeEvent(@NonNull final EventDescriptorWrapper event) {
        final boolean removed = childlessEvents.remove(event) != null;
        if (removed) {
            eventsByCreator.remove(event.creator());
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

        for (final EventDescriptorWrapper event : childlessEvents.keySet()) {
            sb.append("  - ").append(event).append("\n");
        }
        return sb.toString();
    }
}
