// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class is responsible for detecting branch events for visualization in the GUI. It mirrors the logic of
 * DefaultBranchDetector, but is decoupled from the consensus implementation and only tracks the information needed for
 * visualization.
 */
public class GuiBranchDetector {

    private final Map<GossipEvent, BranchedEventMetadata> branchedEventsMetadata = new HashMap<>();

    /** Tracks the most recent event per creator, for branch detection. */
    private final Map<NodeId, EventDescriptorWrapper> mostRecentPerCreator = new HashMap<>();

    /** Tracks the next branch index to assign per creator, for branch visualization. */
    private final Map<NodeId, Integer> nextBranchIndexPerCreator = new HashMap<>();

    /** The current event window, used to avoid false positive branch detection on ancient parents. */
    private EventWindow currentEventWindow = EventWindow.getGenesisEventWindow();

    /**
     * Detects if the given event is a branch event, and if so, assigns it a branch index for visualization.
     *
     * @param creator the creator of the event
     * @param event   the event to check for branching
     */
    public void detectBranches(@NonNull final NodeId creator, @NonNull final PlatformEvent event) {
        final EventDescriptorWrapper previous = mostRecentPerCreator.get(creator);
        final EventDescriptorWrapper selfParent = event.getSelfParent();

        if (previous != null
                && !previous.equals(selfParent)
                && (selfParent == null || !currentEventWindow.isAncient(selfParent))) {
            final int branchIndex = nextBranchIndexPerCreator.merge(creator, 0, (old, v) -> old + 1);
            branchedEventsMetadata.put(event.getGossipEvent(), new BranchedEventMetadata(branchIndex, event.getNGen()));
        }
        mostRecentPerCreator.put(creator, event.getDescriptor());
    }

    public void setEventWindow(@NonNull final EventWindow currentEventWindow) {
        this.currentEventWindow = currentEventWindow;
    }

    /**
     * Clears all stored metadata. Should be called when the GUI is reset to avoid incorrect branch detection on old
     * events.
     */
    public void clear() {
        mostRecentPerCreator.clear();
        nextBranchIndexPerCreator.clear();
        branchedEventsMetadata.clear();
    }

    /**
     * Returns a copy of the map of branched events to their metadata, for use in the GUI visualization.
     *
     * @return a copy of the map of branched events to their metadata
     */
    @NonNull
    public Map<GossipEvent, BranchedEventMetadata> getBranchedEvents() {
        return Map.copyOf(branchedEventsMetadata);
    }
}
