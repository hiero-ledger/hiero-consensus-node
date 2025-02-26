// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.system.events.EventDescriptorWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A standard implementation of {@link BranchDetector}.
 */
public class DefaultBranchDetector implements BranchDetector {
    private static final Logger logger = LogManager.getLogger(DefaultBranchDetector.class);
    /**
     * The current event window.
     */
    private EventWindow currentEventWindow;

    /**
     * The node IDs of the nodes in the network in sorted order, provides deterministic iteration order.
     */
    private final List<NodeId> nodes = new ArrayList<>();

    /**
     * The most recent non-ancient events for each node (not present or null if there are none).
     */
    private final Map<NodeId, EventDescriptorWrapper> mostRecentEvents = new HashMap<>();

    /**
     * Create a new branch detector.
     *
     * @param currentRoster the current roster
     */
    public DefaultBranchDetector(@NonNull final Roster currentRoster) {
        nodes.addAll(currentRoster.rosterEntries().stream()
                .map(re -> NodeId.of(re.nodeId()))
                .toList());
        Collections.sort(nodes);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public PlatformEvent checkForBranches(@NonNull final PlatformEvent event) {
        if (currentEventWindow == null) {
            throw new IllegalStateException("Event window must be set before adding events");
        }

        if (currentEventWindow.isAncient(event)) {
            // Ignore ancient events.
            return null;
        }

        final NodeId creator = event.getCreatorId();
        final EventDescriptorWrapper previousEvent = mostRecentEvents.get(creator);
        final EventDescriptorWrapper selfParent = event.getSelfParent();

        // Events may have been migrated to use birth rounds and so the birth round on the event may have changed.
        // Therefore, we will compare the hashes only (the hashes shouldn't be re-calculated to include the updated
        // birth round)
        final boolean branching = !(previousEvent == null
                || (selfParent != null && Objects.equals(selfParent.hash(), previousEvent.hash())));

        if (branching) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Branch detected: incomingEvent={}, previousEvent={}, selfParent={}",
                    event.getDescriptor(),
                    previousEvent,
                    selfParent);
        }

        mostRecentEvents.put(creator, event.getDescriptor());

        return branching ? event : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        currentEventWindow = eventWindow;

        for (final NodeId nodeId : nodes) {
            final EventDescriptorWrapper mostRecentEvent = mostRecentEvents.get(nodeId);
            if (mostRecentEvent != null && eventWindow.isAncient(mostRecentEvent)) {
                // Event is ancient, forget it.
                mostRecentEvents.put(nodeId, null);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        currentEventWindow = null;
        mostRecentEvents.clear();
    }
}
