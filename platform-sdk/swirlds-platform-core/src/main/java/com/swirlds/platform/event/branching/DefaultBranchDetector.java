// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * A standard implementation of {@link BranchDetector}.
 */
public class DefaultBranchDetector implements BranchDetector {
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

        final boolean branching = !(previousEvent == null || previousEvent.equals(selfParent));

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
