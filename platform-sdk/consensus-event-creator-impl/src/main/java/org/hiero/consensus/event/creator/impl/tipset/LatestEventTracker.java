// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

public class LatestEventTracker {

    private final Map<NodeId, PlatformEvent> latestEvents = new HashMap<>();

    /**
     * Add a new event. Parents are removed from the set of childless events. Event is ignored if there is another event
     * from the same creator with a higher nGen. Causes any event by the same creator, if present, to be removed if it
     * has a lower nGen. This is true even if the event being added is not a direct child (possible if there has been
     * branching).
     *
     * @param event the event to add
     */
    public void addEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);

        final PlatformEvent existingEvent = latestEvents.get(event.getCreatorId());
        if (existingEvent != null) {
            if (existingEvent.getNGen() >= event.getNGen()) {
                // Only add a new event if it has the highest ngen of all events observed so far.
                return;
            }
        }
        latestEvents.put(event.getCreatorId(), event);
    }

    /**
     * Remove ancient events.
     *
     * @param eventWindow the event window
     */
    public void pruneOldEvents(@NonNull final EventWindow eventWindow) {
        final Set<NodeId> keysToRemove = new HashSet<>();
        latestEvents.forEach((key, event) -> {
            if (eventWindow.isAncient(event)) {
                keysToRemove.add(key);
            }
        });
        latestEvents.keySet().removeAll(keysToRemove);
    }

    /**
     * Get random event from any creator except ourselves
     *
     * @param selfId our own node id
     * @return event from other creator or null if nothing is available
     */
    public PlatformEvent getRandomNonSelfEvent(@NonNull final NodeId selfId, @NonNull final SecureRandom random) {
        final List<NodeId> creators = new ArrayList<>(latestEvents.keySet());
        creators.remove(selfId);
        if (creators.isEmpty()) {
            return null;
        }
        final int randomIndex = random.nextInt(creators.size());
        return latestEvents.get(creators.get(randomIndex));
    }
}
